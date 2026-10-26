package dev.autohelm.event

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class RssItem(val record: EventRecord, val description: String?)
data class RssResult(val feed: String, val receivedAt: Instant, val items: List<RssItem>)

class InfomaxRssAdapter(
    private val properties: EventProperties,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    fun fetch(feed: String, uri: URI): RssResult {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
            .header("User-Agent", "Auto-helm personal RSS reader")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val receivedAt = Instant.now()
        if (response.statusCode() == 429) throw RateLimitedException(response.headers().firstValue("Retry-After").orElse(null))
        check(response.statusCode() == 200) { "RSS HTTP ${response.statusCode()}" }
        require(response.body().size <= 2_000_000) { "RSS feed too large" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(response.body()))
        val nodes = document.getElementsByTagName("item")
        val items = (0 until nodes.length).mapNotNull { index ->
            val item = nodes.item(index) as? Element ?: return@mapNotNull null
            val title = item.childText("title")?.trim().orEmpty()
            val url = item.childText("link")?.let(NewsIdentity::canonicalUrl) ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val nsid = item.childText("nsid")?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
            val publishedAt = item.childText("pubDate")?.trim()?.let { raw ->
                runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
                    .recoverCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.of("Asia/Seoul")).toInstant() }
                    .getOrNull()
            }
            val record = EventRecord(
                id = NewsIdentity.id(url), environment = properties.environment,
                source = EventSource.EINFOMAX, title = title, url = url, symbol = null,
                publishedAt = publishedAt, providerSeenAt = null, firstSeenAt = receivedAt,
                inputScope = "TITLE_AND_EXCERPT", providerId = "EINFOMAX:${nsid ?: NewsIdentity.hash(url)}",
            )
            RssItem(record, item.childText("description")?.trim()?.takeIf(String::isNotBlank))
        }
        return RssResult(feed, receivedAt, items)
    }

    private fun Element.childText(name: String): String? = getElementsByTagName(name).item(0)?.textContent
}
