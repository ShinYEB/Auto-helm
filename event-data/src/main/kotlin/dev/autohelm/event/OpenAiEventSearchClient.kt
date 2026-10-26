package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Searches only high-scoring events; findings remain untrusted until source links are present. */
class OpenAiEventSearchClient(
    private val properties: EventProperties,
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    fun verify(record: EventRecord, analysis: IssueAnalysis): IssueSearchResult {
        val config = properties.search
        val searchId = "${properties.environment}:${record.id}:${analysis.symbol}:${config.policyVersion}"
        val prompt = """Search for recent independent evidence about this Korean listed company's news event.
            Company symbol: ${analysis.symbol}
            Original title: ${record.title}
            Original URL: ${record.url}
            Original first seen: ${record.firstSeenAt}
            Return ONLY a JSON object with keys summary (one short sentence), corroborated (boolean), direction (-1 to 1), impact (0 to 1), confidence (0 to 1).
            Use confidence 0 if evidence is absent or contradictory. Do not give trading instructions.""".trimIndent()
        val requestBody = mapper.writeValueAsString(mapOf(
            "model" to config.model,
            "tools" to listOf(mapOf("type" to "web_search", "search_context_size" to "low")),
            "tool_choice" to "required",
            "include" to listOf("web_search_call.action.sources"),
            "store" to false,
            "input" to prompt,
        ))
        val request = HttpRequest.newBuilder(config.endpoint).timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Search HTTP ${response.statusCode()}" }
        val json = mapper.readTree(response.body())
        val output = json.path("output")
        val searchCalls = output.filter { it.path("type").asText() == "web_search_call" }
        val sourceUrls = searchCalls.flatMap { call ->
            call.path("action").path("sources").mapNotNull { source ->
                source.path("url").asText(null)?.let(NewsIdentity::canonicalUrl)
            }
        }.distinct().take(20)
        val text = output.flatMap { item -> item.path("content").filter { it.path("type").asText() == "output_text" } }
            .joinToString("\n") { it.path("text").asText() }.trim()
        val parsed = runCatching { mapper.readTree(text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
        fun bounded(name: String, min: Double, max: Double): Double? = parsed?.path(name)?.takeIf { it.isNumber }
            ?.asDouble()?.takeIf { it in min..max }
        val direction = bounded("direction", -1.0, 1.0)
        val impact = bounded("impact", 0.0, 1.0)
        val confidence = bounded("confidence", 0.0, 1.0)
        val corroborated = parsed?.path("corroborated")?.takeIf { it.isBoolean }?.asBoolean() == true
        val verified = corroborated && sourceUrls.any { it != record.url } &&
            direction != null && impact != null && confidence != null && confidence >= 0.6
        return IssueSearchResult(
            searchId = searchId, environment = properties.environment, eventId = record.id,
            symbol = analysis.symbol, policyVersion = config.policyVersion,
            status = if (verified) SearchStatus.VERIFIED else SearchStatus.NOT_AVAILABLE,
            originalScore = analysis.signedScore!!,
            verifiedScore = if (verified) direction!! * impact!! * confidence!! else null,
            sourceUrls = sourceUrls,
            summary = parsed?.path("summary")?.asText()?.take(400),
            model = json.path("model").asText(config.model), completedAt = Instant.now(),
            inputTokens = json.path("usage").path("input_tokens").takeIf { it.isInt }?.asInt(),
            outputTokens = json.path("usage").path("output_tokens").takeIf { it.isInt }?.asInt(),
        )
    }
}
