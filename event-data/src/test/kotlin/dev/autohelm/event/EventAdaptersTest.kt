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
                enabled = true, searches = mapOf("005930" to "\"Samsung Electronics\""),
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/doc"),
            ))
            val startedAt = Instant.now()
            val result = GdeltAdapter(properties, mapper).fetch("005930", "\"Samsung Electronics\"")
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
    fun `RSS parses article metadata and shares news identity with GDELT`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rss") { exchange ->
            val body = """<?xml version="1.0" encoding="UTF-8"?><rss><channel><item>
                <nsid>12345</nsid><title>삼성전자 실적 발표</title>
                <link>https://news.example/a?utm_source=rss&amp;id=7</link>
                <description>짧은 발췌문</description><pubDate>Mon, 26 Oct 2026 18:00:00 +0900</pubDate>
                </item><item><title>누락</title></item></channel></rss>"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val properties = EventProperties(dataDir = directory)
            val result = InfomaxRssAdapter(properties).fetch("stocks", URI.create("http://127.0.0.1:${server.address.port}/rss"))
            assertEquals(1, result.items.size)
            val item = result.items.single()
            assertEquals("https://news.example/a?id=7", item.record.url)
            assertEquals(NewsIdentity.id(item.record.url), item.record.id)
            assertEquals("EINFOMAX:12345", item.record.providerId)
            assertEquals(Instant.parse("2026-10-26T09:00:00Z"), item.record.publishedAt)
            assertEquals("짧은 발췌문", item.description)
            assertFalse(mapper.writeValueAsString(item.record).contains("발췌문"))
        } finally { server.stop(0) }
    }

    @Test
    fun `RSS parses Infomax local publication time as Korea time`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rss") { exchange ->
            val body = """<?xml version="1.0"?><rss><channel><item><nsid>88</nsid>
                <title>실적</title><link>https://news.example/88</link>
                <pubDate>2026-10-26 16:33:29</pubDate></item></channel></rss>"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val result = InfomaxRssAdapter(EventProperties()).fetch("stocks",
                URI.create("http://127.0.0.1:${server.address.port}/rss"))
            assertEquals(Instant.parse("2026-10-26T07:33:29Z"), result.items.single().record.publishedAt)
        } finally { server.stop(0) }
    }

    @Test
    fun `NAVER API HUB search parses metadata without persisting the excerpt`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search/v1/news") { exchange ->
            assertTrue(exchange.requestURI.rawQuery.contains("sort=date"))
            assertEquals("client", exchange.requestHeaders.getFirst("X-NCP-APIGW-API-KEY-ID"))
            assertEquals("secret", exchange.requestHeaders.getFirst("X-NCP-APIGW-API-KEY"))
            val body = """{"items":[{"title":"<b>삼성전자</b> 실적","originallink":"https://news.example/a?utm_source=n&id=7","link":"https://n.news.naver.com/a","description":"<b>삼성전자</b> 매출 증가","pubDate":"Mon, 26 Oct 2026 18:00:00 +0900"}]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val properties = EventProperties(naver = NaverNewsProperties(
                enabled = true, clientId = "client", clientSecret = "secret",
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/search/v1/news"),
            ))
            val item = NaverNewsAdapter(properties, mapper).fetch("삼성전자").items.single()
            assertEquals("삼성전자 실적", item.record.title)
            assertEquals("https://news.example/a?id=7", item.record.url)
            assertEquals("삼성전자 매출 증가", item.description)
            assertEquals(Instant.parse("2026-10-26T09:00:00Z"), item.record.publishedAt)
            assertFalse(mapper.writeValueAsString(item.record).contains("매출 증가"))
        } finally { server.stop(0) }
    }

    @Test
    fun `OpenDART key facts use matching receipt and selected fields only`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tsstkAqDecsn.json") { exchange ->
            assertTrue(exchange.requestURI.rawQuery.contains("corp_code=00126380"))
            val body = """{"status":"000","list":[
                {"rcept_no":"20261026000001","aqpln_prc_ostk":"999","private_name":"ignored"},
                {"rcept_no":"20261026000002","aqpln_stk_ostk":"1000","aqpln_prc_ostk":"5000000","private_name":"ignored"}
            ]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val properties = EventProperties(dart = DartProperties(enabled = true, apiKey = "test-only",
                corpCodes = mapOf("005930" to "00126380"),
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/api/")))
            val record = EventRecord("DART:20261026000002", "dev", EventSource.DART, "자기주식 취득 결정",
                "https://example.com", "005930", null, null, Instant.now(), "TITLE_WITH_OPTIONAL_KEY_FACTS")
            val facts = OpenDartAdapter(properties, mapper).keyFacts(record, "00126380")!!
            assertTrue(facts.contains("취득 예정 보통주 수: 1000"))
            assertTrue(facts.contains("취득 예정 금액: 5000000"))
            assertFalse(facts.contains("999"))
            assertFalse(facts.contains("private_name"))
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
        journal.appendNaverAttempt("005930", at)
        assertEquals(1, journal.naverAttemptsOn(java.time.LocalDate.parse("2026-10-26")))
        val file = directory.resolve("dev/events/events/2026-10-26.jsonl")
        Files.writeString(file, "{\"incomplete\"", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(setOf(record.id), EventJournal(properties, mapper).existingIds())
        assertEquals(setOf("${record.id}:005930"), EventJournal(properties, mapper).existingLinkIds())
        assertFalse(Files.readString(file).contains("incomplete"))
        assertTrue(EventJournal(properties.copy(environment = "prd"), mapper).existingIds().isEmpty())
    }
}
