package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class GdeltAdapter(
    private val properties: EventProperties,
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun fetch(symbol: String, keyword: String): FetchResult {
        require(keyword.isNotBlank() && keyword.all { it.code < 128 }) {
            "GDELT DOC query must use English keywords"
        }
        val query = URLEncoder.encode(keyword, StandardCharsets.UTF_8)
        val uri = URI.create("${properties.gdelt.endpoint}?query=$query&mode=artlist&format=json&sort=datedesc&maxrecords=100&timespan=1h")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val receivedAt = Instant.now()
        if (response.statusCode() == 429) throw RateLimitedException(response.headers().firstValue("Retry-After").orElse(null))
        check(response.statusCode() == 200) { "GDELT HTTP ${response.statusCode()}" }
        val records = mapper.readTree(response.body()).path("articles").mapNotNull { article ->
            val original = article.path("url").asText("")
            val title = article.path("title").asText("")
            if (original.isBlank() || title.isBlank()) return@mapNotNull null
            val url = NewsIdentity.canonicalUrl(original) ?: return@mapNotNull null
            val providerSeen = article.path("seendate").asText("").let { raw ->
                runCatching { LocalDateTime.parse(raw, dateFormat).toInstant(ZoneOffset.UTC) }.getOrNull()
            }
            EventRecord(
                id = NewsIdentity.id(url), environment = properties.environment,
                source = EventSource.GDELT, title = title, url = url, symbol = null,
                publishedAt = null, providerSeenAt = providerSeen, firstSeenAt = receivedAt,
                inputScope = "METADATA_ONLY", providerId = "GDELT:${NewsIdentity.hash(url)}",
            )
        }
        return FetchResult(EventSource.GDELT, symbol, receivedAt, response.statusCode(), response.body(), records)
    }

}
