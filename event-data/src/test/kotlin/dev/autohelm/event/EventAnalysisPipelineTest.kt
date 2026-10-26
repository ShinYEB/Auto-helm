package dev.autohelm.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.time.Instant

class EventAnalysisPipelineTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `journal drives Jev and threshold search once with separate records`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var jevCalls = 0
        var searchCalls = 0
        server.createContext("/jev") { exchange ->
            jevCalls++
            exchange.requestBody.readAllBytes()
            val body = """{"model":"jev-1.13.0","answers":{
                "relevant":{"noul":0.95},"material":{"noul":0.95},
                "positive":{"noul":0.95},"sufficient":{"noul":0.95}},
                "usage":{"input_tokens":10,"output_tokens":4}}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/search") { exchange ->
            searchCalls++
            exchange.requestBody.readAllBytes()
            val body = """{"model":"gpt-4.1-mini","output":[
                {"type":"web_search_call","action":{"sources":[{"url":"https://second.example/evidence"}]}},
                {"type":"message","content":[{"type":"output_text","text":"{\"summary\":\"확인\",\"corroborated\":true,\"direction\":1,\"impact\":0.8,\"confidence\":0.9}"}]}
                ],"usage":{"input_tokens":20,"output_tokens":10}}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
            val properties = EventProperties(dataDir = directory,
                jev = JevProperties(enabled = true, apiKey = "test", endpoint = URI.create("http://127.0.0.1:${server.address.port}/jev")),
                search = EventSearchProperties(enabled = true, apiKey = "test", endpoint = URI.create("http://127.0.0.1:${server.address.port}/search")))
            val journal = EventJournal(properties, mapper)
            val now = Instant.now()
            val event = EventRecord("NEWS:example", "dev", EventSource.EINFOMAX, "삼성전자 실적 개선",
                "https://first.example/news", null, now, null, now, "TITLE_AND_EXCERPT")
            journal.appendEvent(event)
            journal.saveWorking(event.id, "매출 증가")
            journal.appendLink(EventLink(event.id, "005930", now))
            assertEquals(1, EventAnalysisWorker(properties, mapper).processAvailable())
            assertEquals(1, journal.existingAnalyses().size)
            assertEquals(IssueStatus.SCORED, journal.existingAnalyses().single().status)
            assertEquals(1, EventSearchWorker(properties, mapper).processAvailable())
            assertEquals(listOf(SearchStatus.SEARCH_PENDING, SearchStatus.VERIFIED), journal.existingSearchResults().map { it.status })
            assertEquals(0, EventAnalysisWorker(properties, mapper).processAvailable())
            assertEquals(0, EventSearchWorker(properties, mapper).processAvailable())
            assertEquals(1, jevCalls)
            assertEquals(1, searchCalls)
        } finally { server.stop(0) }
    }
}
