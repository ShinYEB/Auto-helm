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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class NaverNewsResult(val receivedAt: Instant, val items: List<RssItem>)

/** NAVER API HUB search result; only article metadata is retained. */
class NaverNewsAdapter(
    private val properties: EventProperties,
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    fun fetch(query: String): NaverNewsResult {
        val config = properties.naver
        val separator = if (config.endpoint.rawQuery == null) "?" else "&"
        val uri = URI.create(config.endpoint.toString() + separator +
            "query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}&display=${config.display}&start=1&sort=date&format=json")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
            .header("X-NCP-APIGW-API-KEY-ID", config.clientId)
            .header("X-NCP-APIGW-API-KEY", config.clientSecret)
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val receivedAt = Instant.now()
        if (response.statusCode() == 429) throw RateLimitedException(response.headers().firstValue("Retry-After").orElse(null))
        check(response.statusCode() == 200) { "NAVER HTTP ${response.statusCode()}" }
        val body = mapper.readTree(response.body())
        val items = body.path("items").mapNotNull { item ->
            val title = plainText(item.path("title").asText(""))
            val url = NewsIdentity.canonicalUrl(item.path("originallink").asText("")).orEmpty()
                .ifBlank { NewsIdentity.canonicalUrl(item.path("link").asText("")) ?: "" }
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val publishedAt = runCatching {
                ZonedDateTime.parse(item.path("pubDate").asText(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull()
            RssItem(
                EventRecord(
                    id = NewsIdentity.id(url), environment = properties.environment,
                    source = EventSource.NAVER, title = title, url = url, symbol = null,
                    publishedAt = publishedAt, providerSeenAt = null, firstSeenAt = receivedAt,
                    inputScope = "TITLE_AND_EXCERPT", providerId = "NAVER:${NewsIdentity.hash(url)}",
                ),
                plainText(item.path("description").asText("")).takeIf { it.isNotBlank() },
            )
        }
        return NaverNewsResult(receivedAt, items)
    }

    private fun plainText(value: String): String = value.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()
}
