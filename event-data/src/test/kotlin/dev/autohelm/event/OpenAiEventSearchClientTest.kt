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

class OpenAiEventSearchClientTest {
    @Test
    fun `search requires an independent source before marking verified`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        var responseWithIndependentSource = false
        server.createContext("/responses") { exchange ->
            val request = mapper.readTree(exchange.requestBody.readAllBytes())
            assertEquals("required", request.path("tool_choice").asText())
            assertEquals(false, request.path("store").asBoolean())
            val sourceUrl = if (responseWithIndependentSource) "https://second.example/article" else "https://first.example/article"
            val answer = """{"summary":"확인","corroborated":true,"direction":1,"impact":0.8,"confidence":0.9}"""
            val body = mapper.writeValueAsString(mapOf(
                "model" to "gpt-4.1-mini",
                "output" to listOf(
                    mapOf("type" to "web_search_call", "action" to mapOf("sources" to listOf(mapOf("url" to sourceUrl)))),
                    mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to answer))),
                ),
                "usage" to mapOf("input_tokens" to 50, "output_tokens" to 20),
            ))
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val properties = EventProperties(search = EventSearchProperties(enabled = true, apiKey = "test-only",
                endpoint = URI.create("http://127.0.0.1:${server.address.port}/responses")))
            val event = EventRecord("NEWS:test", "dev", EventSource.EINFOMAX, "실적 발표",
                "https://first.example/article", null, null, null, Instant.now(), "TITLE_AND_EXCERPT")
            val analysis = IssueAnalysis("id", "dev", event.id, "005930", "v1", IssueStatus.SCORED,
                0.9, 0.8, 0.9, 0.9, 0.7, "jev", event.inputScope, "test", Instant.now())
            val client = OpenAiEventSearchClient(properties, mapper)
            val unverified = client.verify(event, analysis)
            assertEquals(SearchStatus.NOT_AVAILABLE, unverified.status)
            assertNull(unverified.verifiedScore)
            responseWithIndependentSource = true
            val verified = client.verify(event, analysis)
            assertEquals(SearchStatus.VERIFIED, verified.status)
            assertEquals(0.72, verified.verifiedScore!!, 0.0001)
            assertTrue(verified.sourceUrls.contains("https://second.example/article"))
        } finally { server.stop(0) }
    }
}
