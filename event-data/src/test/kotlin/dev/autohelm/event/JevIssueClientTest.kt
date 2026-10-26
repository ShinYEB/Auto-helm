package dev.autohelm.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant

class JevIssueClientTest {
    @Test
    fun `typed probabilities produce a versioned score without invented rationale`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        server.createContext("/systemone") { exchange ->
            val request = mapper.readTree(exchange.requestBody.readAllBytes())
            assertEquals("jev-1.13.0", request.path("model").asText())
            assertEquals("삼성전자 실적", request.path("state").path("title").asText())
            assertEquals(4, request.path("questions").size())
            val body = """{"model":"jev-1.13.0","answers":{
                "relevant":{"type":"noul","noul":0.9},
                "material":{"type":"noul","noul":0.8},
                "positive":{"type":"noul","noul":0.75},
                "sufficient":{"type":"noul","noul":0.7}},
                "usage":{"input_tokens":100,"output_tokens":4}}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val properties = EventProperties(jev = JevProperties(enabled = true, apiKey = "test-only",
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/systemone")))
            val event = EventRecord("NEWS:test", "dev", EventSource.EINFOMAX,
                "삼성전자 실적", "https://example.com/a", null, null, null,
                Instant.now(), "TITLE_AND_EXCERPT")
            val result = JevIssueClient(properties, mapper).score(event, "005930", "매출 증가", 1)
            assertEquals(IssueStatus.SCORED, result.status)
            assertEquals(0.36, result.signedScore!!, 0.0001)
            assertEquals(100, result.inputTokens)
            assertTrue(result.basis.contains("no article verification"))
        } finally { server.stop(0) }
    }
}
