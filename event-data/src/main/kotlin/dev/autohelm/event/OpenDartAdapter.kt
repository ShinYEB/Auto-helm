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

    /** Only these official major-disclosure fields are eligible for external scoring. */
    private val keyFactTypes = listOf(
        Triple("유상증자", "piicDecsn.json", linkedMapOf(
            "nstk_ostk_cnt" to "신주 보통주 수", "bfic_tisstk_ostk" to "증자 전 보통주 수",
            "fdpp_fclt" to "시설자금", "fdpp_op" to "운영자금", "fdpp_dtrp" to "채무상환자금")),
        Triple("자기주식 취득 결정", "tsstkAqDecsn.json", linkedMapOf(
            "aqpln_stk_ostk" to "취득 예정 보통주 수", "aqpln_prc_ostk" to "취득 예정 금액",
            "aqexpd_bgd" to "취득 시작일", "aqexpd_edd" to "취득 종료일")),
        Triple("회사합병 결정", "cmpMgDecsn.json", linkedMapOf(
            "mg_mth" to "합병 방법", "mg_stn" to "합병 형태", "mg_rt" to "합병 비율")),
    )

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
                    inputScope = "TITLE_WITH_OPTIONAL_KEY_FACTS",
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

    fun keyFacts(record: EventRecord, corpCode: String): String? {
        val normalizedTitle = record.title.replace(Regex("\\s+"), "")
        val type = keyFactTypes.firstOrNull { normalizedTitle.contains(it.first.replace(" ", "")) } ?: return null
        val receipt = record.id.removePrefix("DART:")
        require(receipt.matches(Regex("[0-9]{14}")))
        val date = receipt.take(8)
        val query = "crtfc_key=${encode(properties.dart.apiKey)}&corp_code=$corpCode&bgn_de=$date&end_de=$date"
        val response = client.send(request("${type.second}?$query"), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "OpenDART key facts HTTP ${response.statusCode()}" }
        val body = mapper.readTree(response.body())
        return when (val status = body.path("status").asText()) {
            "013" -> null
            "020" -> throw RateLimitedException()
            "000" -> {
                val item = body.path("list").firstOrNull { it.path("rcept_no").asText() == receipt } ?: return null
                val facts = type.third.mapNotNull { (field, label) ->
                    item.path(field).asText("").trim().takeIf { it.isNotBlank() && it != "-" }
                        ?.let { "$label: ${it.take(120)}" }
                }
                facts.takeIf { it.isNotEmpty() }?.joinToString("; ")?.take(1500)
            }
            else -> error("OpenDART key facts status $status")
        }
    }

    private fun request(path: String): HttpRequest = HttpRequest.newBuilder(
        URI.create(properties.dart.endpoint.toString().trimEnd('/') + "/" + path),
    ).timeout(Duration.ofSeconds(25)).GET().build()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
