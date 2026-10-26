package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Jev returns typed probabilities, not a free-text rationale or a price forecast. */
class JevIssueClient(
    private val properties: EventProperties,
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    fun score(record: EventRecord, symbol: String, description: String?, attempt: Int): IssueAnalysis {
        val config = properties.jev
        val analysisId = "${properties.environment}:${record.id}:$symbol:${config.analysisVersion}"
        val state = mapOf(
            "company_symbol" to symbol,
            "title" to record.title,
            "excerpt" to (description ?: ""),
            "source" to record.source.name,
            "published_at" to record.publishedAt?.toString(),
            "provider_seen_at" to record.providerSeenAt?.toString(),
        )
        val instructions = mapOf(
            "relevant" to "Does this event directly concern the company with the supplied stock symbol?",
            "material" to "Could this event materially affect this company's stock price over the next trading day?",
            "positive" to "If the event is true, is its likely near-term effect on this company's stock price positive rather than negative?",
            "sufficient" to "Is the supplied title and excerpt enough to assess the event's direction without reading the full article?",
        )
        val body = mapper.writeValueAsString(mapOf(
            "model" to config.model,
            "state" to state,
            "questions" to instructions.mapValues { (_, instruction) -> mapOf("type" to "noul", "instructions" to instruction) },
        ))
        val request = HttpRequest.newBuilder(config.endpoint).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Jev HTTP ${response.statusCode()}" }
        val json = mapper.readTree(response.body())
        fun probability(name: String): Double = json.path("answers").path(name).path("noul").let {
            require(it.isNumber && it.asDouble() in 0.0..1.0) { "Invalid Jev answer: $name" }
            it.asDouble()
        }
        val relevance = probability("relevant")
        val impact = probability("material")
        val positive = probability("positive")
        val sufficient = probability("sufficient")
        val usable = relevance >= 0.6 && sufficient >= 0.6
        // Product policy: these thresholds and the derived score need dev calibration before trading use.
        return IssueAnalysis(
            analysisId = analysisId, environment = properties.environment,
            eventId = record.id, symbol = symbol, analysisVersion = config.analysisVersion,
            status = if (usable) IssueStatus.SCORED else IssueStatus.NOT_AVAILABLE,
            relevance = relevance, impact = impact, positiveProbability = positive,
            evidenceSufficient = sufficient,
            signedScore = if (usable) relevance * impact * (2 * positive - 1) else null,
            model = json.path("model").asText(config.model), inputScope = record.inputScope,
            basis = "Jev typed probabilities from source metadata; no article verification",
            completedAt = Instant.now(),
            inputTokens = json.path("usage").path("input_tokens").takeIf { it.isInt }?.asInt(),
            outputTokens = json.path("usage").path("output_tokens").takeIf { it.isInt }?.asInt(),
            attempt = attempt,
        )
    }
}
