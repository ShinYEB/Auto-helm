package dev.autohelm.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

class EventCollectorTest {
    @TempDir lateinit var directory: Path

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
                enabled = true, searches = mapOf("005930" to "삼성전자"),
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
                    val linkDirectory = directory.resolve("dev/events/links")
                    while ((!Files.isDirectory(linkDirectory) || Files.list(linkDirectory).use { files ->
                            files.noneMatch { Files.size(it) > 0 }
                        }) && System.nanoTime() < deadline) {
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
        } finally { server.stop(0) }
    }
}
