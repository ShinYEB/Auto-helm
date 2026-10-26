package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class EventAdaptersTest {
    @TempDir lateinit var directory: Path
    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `GDELT records metadata only and removes known tracking parameters`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/doc") { exchange ->
            assertTrue(exchange.requestURI.rawQuery.contains("query="))
            val body = """{"articles":[{"url":"https://NEWS.example/a?utm_source=x&id=7","title":"Sample","seendate":"20261026T090000Z"}]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val properties = EventProperties(dataDir = directory, gdelt = GdeltProperties(
                enabled = true, searches = mapOf("005930" to "삼성전자"),
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/doc"),
            ))
            val startedAt = Instant.now()
            val result = GdeltAdapter(properties, mapper).fetch("005930", "삼성전자")
            assertEquals(1, result.records.size)
            val record = result.records.single()
            assertEquals("https://news.example/a?id=7", record.url)
            assertEquals("METADATA_ONLY", record.inputScope)
            assertNull(record.publishedAt)
            assertEquals(Instant.parse("2026-10-26T09:00:00Z"), record.providerSeenAt)
            assertEquals(result.receivedAt, record.firstSeenAt)
            assertTrue(!result.receivedAt.isBefore(startedAt))
            assertEquals("005930", result.query)
        } finally { server.stop(0) }
    }

    @Test
    fun `OpenDART pages and original ZIP are separate requests`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/list.json") { exchange ->
            val page = if (exchange.requestURI.rawQuery.contains("page_no=2")) 2 else 1
            val receipt = if (page == 1) "20261026000001" else "20261026000002"
            val body = """{"status":"000","total_page":2,"list":[{"rcept_no":"$receipt","report_nm":"Report $page"}]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/api/document.xml") { exchange ->
            val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
            exchange.sendResponseHeaders(200, zip.size.toLong())
            exchange.responseBody.use { it.write(zip) }
        }
        server.start()
        try {
            val properties = EventProperties(dataDir = directory, dart = DartProperties(
                enabled = true, apiKey = "test-only", corpCodes = mapOf("005930" to "00126380"),
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/api/"),
            ))
            val adapter = OpenDartAdapter(properties, mapper)
            val pages = adapter.fetch("005930", "00126380", Instant.parse("2026-10-26T09:01:00Z"))
            assertEquals(2, pages.size)
            assertTrue(pages.all { page -> page.records.all { it.firstSeenAt == page.receivedAt } })
            assertEquals(listOf("DART:20261026000001", "DART:20261026000002"), pages.flatMap { it.records }.map { it.id })
            assertFalse(pages.first().body.contains("test-only"))
            assertEquals('P'.code.toByte(), adapter.downloadDisclosure("20261026000001").first())
        } finally { server.stop(0) }
    }

    @Test
    fun `journal recovers complete records and isolates environments`() {
        val properties = EventProperties(dataDir = directory)
        val journal = EventJournal(properties, mapper)
        val at = Instant.parse("2026-10-26T09:01:00Z")
        val record = EventRecord("GDELT:one", "dev", EventSource.GDELT, "Sample", "https://example.com/a",
            null, null, null, at, "METADATA_ONLY")
        journal.appendEvent(record)
        journal.appendLink(EventLink(record.id, "005930", at))
        val file = directory.resolve("dev/events/events/2026-10-26.jsonl")
        Files.writeString(file, "{\"incomplete\"", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(setOf(record.id), EventJournal(properties, mapper).existingIds())
        assertEquals(setOf("${record.id}:005930"), EventJournal(properties, mapper).existingLinkIds())
        assertFalse(Files.readString(file).contains("incomplete"))
        assertTrue(EventJournal(properties.copy(environment = "prd"), mapper).existingIds().isEmpty())
    }
}
