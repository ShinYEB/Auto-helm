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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class OpenDartAdapter(
    private val properties: EventProperties,
    private val mapper: ObjectMapper,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val dayFormat = DateTimeFormatter.BASIC_ISO_DATE

    fun fetch(symbol: String, corpCode: String, now: Instant): List<FetchResult> {
        val date = now.atZone(seoul).toLocalDate()
        val from = dayFormat.format(date.minusDays(1))
        val to = dayFormat.format(date)
        val results = mutableListOf<FetchResult>()
        var page = 1
        do {
            val query = "crtfc_key=${encode(properties.dart.apiKey)}&corp_code=$corpCode" +
                "&bgn_de=$from&end_de=$to&page_no=$page&page_count=100&last_reprt_at=N"
            val response = client.send(request("list.json?$query"), HttpResponse.BodyHandlers.ofString())
            val receivedAt = Instant.now()
            check(response.statusCode() == 200) { "OpenDART HTTP ${response.statusCode()}" }
            val body = mapper.readTree(response.body())
            when (val status = body.path("status").asText()) {
                "000" -> Unit
                "013" -> return results + FetchResult(EventSource.DART, symbol, receivedAt, 200, response.body(), emptyList())
                "020" -> throw RateLimitedException()
                else -> error("OpenDART status $status")
            }
            val records = body.path("list").mapNotNull { item ->
                val receipt = item.path("rcept_no").asText("")
                val title = item.path("report_nm").asText("")
                if (!receipt.matches(Regex("[0-9]{14}")) || title.isBlank()) return@mapNotNull null
                EventRecord(
                    id = "DART:$receipt", environment = properties.environment,
                    source = EventSource.DART, title = title,
                    url = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=$receipt",
                    symbol = symbol, publishedAt = null, providerSeenAt = null, firstSeenAt = receivedAt,
                    inputScope = "FULL_DISCLOSURE", // AI input is gated separately by usage rights.
                )
            }
            results += FetchResult(EventSource.DART, symbol, receivedAt, 200, response.body(), records)
            val totalPages = body.path("total_page").asInt(1).coerceAtLeast(1)
            if (page >= totalPages) break
            page++
            check(page <= 1000) { "OpenDART page limit exceeded" }
        } while (true)
        return results
    }

    fun downloadDisclosure(receipt: String): ByteArray {
        require(receipt.matches(Regex("[0-9]{14}")))
        val query = "crtfc_key=${encode(properties.dart.apiKey)}&rcept_no=$receipt"
        val response = client.send(request("document.xml?$query"), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "OpenDART document HTTP ${response.statusCode()}" }
        val body = response.body()
        check(body.size >= 4 && body[0] == 'P'.code.toByte() && body[1] == 'K'.code.toByte()) {
            "OpenDART document is not a ZIP"
        }
        return body
    }

    private fun request(path: String): HttpRequest = HttpRequest.newBuilder(
        URI.create(properties.dart.endpoint.toString().trimEnd('/') + "/" + path),
    ).timeout(Duration.ofSeconds(25)).GET().build()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
