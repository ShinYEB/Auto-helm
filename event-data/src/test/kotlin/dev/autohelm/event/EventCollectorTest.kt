package dev.autohelm.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.autohelm.control.contract.WatchlistEntry
import dev.autohelm.control.contract.WatchlistSnapshot
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class EventCollectorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `GDELT rate limit pauses requests instead of polling every cycle`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/doc") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()
        val properties = EventProperties(enabled = true, dataDir = directory,
            gdelt = GdeltProperties(enabled = true, searches = mapOf("005930" to "\"Samsung Electronics\""),
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/doc"),
                pollInterval = Duration.ofMillis(100)))
        val collector = EventCollector(properties, jacksonObjectMapper().registerModule(JavaTimeModule()))
        try {
            collector.start()
            Thread.sleep(2300)
            assertEquals(1, calls.get())
        } finally {
            collector.stop()
            server.stop(0)
        }
    }

    @Test
    fun `poller records one event and link even after restart`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/doc") { exchange ->
            val body = """{"articles":[{"url":"https://news.example/a","title":"News","seendate":"20261026T090000Z"}]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val properties = EventProperties(
            enabled = true, dataDir = directory,
            gdelt = GdeltProperties(
                enabled = true, searches = mapOf("005930" to "\"Samsung Electronics\""),
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/doc"),
                pollInterval = Duration.ofMillis(200),
            ),
        )
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        try {
            repeat(2) {
                val collector = EventCollector(properties, mapper)
                collector.start()
                try {
                    val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                    val healthFile = directory.resolve("dev/events/health.json")
                    while (System.nanoTime() < deadline) {
                        if (Files.exists(healthFile) && mapper.readTree(healthFile.toFile())
                                .path("sources").path("GDELT").path("successes").asInt() >= 1) break
                        Thread.sleep(25)
                    }
                } finally { collector.stop() }
                assertEquals(1, EventJournal(properties, mapper).existingIds().size)
            }
            val events = directory.resolve("dev/events/events")
            val links = directory.resolve("dev/events/links")
            val eventLines = Files.list(events).use { stream -> stream.flatMap { Files.lines(it) }.count() }
            val linkLines = Files.list(links).use { stream -> stream.flatMap { Files.lines(it) }.count() }
            assertEquals(1, eventLines)
            assertEquals(1, linkLines)
            assertTrue(EventJournal(properties, mapper).existingLinkIds().single().endsWith(":005930"))
            val health = mapper.readTree(directory.resolve("dev/events/health.json").toFile())
            assertEquals(1, health.path("sources").path("GDELT").path("discovered").asInt())
            assertTrue(health.path("sources").path("GDELT").path("successes").asInt() >= 1)
            assertEquals(false, health.path("running").asBoolean())
        } finally { server.stop(0) }
    }

    @Test
    fun `RSS records metadata and matched symbol without persisting excerpt`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rss") { exchange ->
            val body = """<?xml version="1.0"?><rss><channel><item><nsid>77</nsid>
                <title>삼성전자 실적 발표</title><link>https://news.example/77</link>
                <description>저장하지 않을 발췌문</description></item></channel></rss>"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val watchlist = directory.resolve("dev/control/watchlist.json")
        Files.createDirectories(watchlist.parent)
        val entry = WatchlistEntry("005930", "삼성전자", setOf("KRX"), updatedAt = Instant.now())
        mapper.writeValue(watchlist.toFile(), WatchlistSnapshot(environment = "dev", revision = 1,
            updatedAt = Instant.now(), entries = mapOf("005930" to entry)))
        val properties = EventProperties(enabled = true, dataDir = directory,
            rss = RssProperties(enabled = true, feeds = mapOf("stocks" to URI.create("http://127.0.0.1:${server.address.port}/rss"))))
        try {
            repeat(2) {
                val collector = EventCollector(properties, mapper)
                collector.start()
                try {
                    val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                    while (System.nanoTime() < deadline) {
                        val health = directory.resolve("dev/events/health.json")
                        if (Files.exists(health) && mapper.readTree(health.toFile()).path("sources")
                                .path("EINFOMAX").path("successes").asInt() >= 1) break
                        Thread.sleep(25)
                    }
                } finally { collector.stop() }
            }
            val journal = EventJournal(properties, mapper)
            assertEquals(1, journal.existingIds().size)
            assertEquals(1, journal.existingLinkIds().size)
            assertEquals(1, journal.existingObservationIds().size)
            assertTrue(journal.existingLinkIds().single().endsWith(":005930"))
            assertTrue(Files.notExists(directory.resolve("dev/events/raw")))
            assertTrue(Files.notExists(directory.resolve("dev/events/working")))
        } finally { server.stop(0) }
    }
}
