package dev.autohelm.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class KisPage(val body: String, val rows: List<JsonNode>)
class KisRateLimit : RuntimeException("KIS request rate limited")

/** Read-only KIS historical minute API. Credentials and bearer tokens never enter journal data. */
class KisHistoricalClient(
    private val properties: TrainingProperties,
    private val mapper: ObjectMapper,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private var token: String? = null
    private var lastRequestAt = 0L

    fun fetch(symbol: String, venue: String, date: LocalDate, cursor: LocalTime): KisPage {
        require(properties.appKey.isNotBlank() && properties.appSecret.isNotBlank())
        require(symbol.matches(Regex("[0-9A-Z]{6}")) && venue in setOf("KRX", "NXT"))
        val host = properties.restUrl.host
        require((properties.environment == "dev" && host == "openapivts.koreainvestment.com") ||
            (properties.environment == "prd" && host == "openapi.koreainvestment.com") ||
            (host in setOf("127.0.0.1", "localhost") && properties.restUrl.scheme == "http")) {
            "KIS endpoint does not match environment"
        }
        val params = linkedMapOf(
            "FID_COND_MRKT_DIV_CODE" to if (venue == "KRX") "J" else "NX",
            "FID_INPUT_ISCD" to symbol,
            "FID_INPUT_HOUR_1" to cursor.format(DateTimeFormatter.ofPattern("HHmmss")),
            "FID_INPUT_DATE_1" to date.format(DateTimeFormatter.BASIC_ISO_DATE),
            "FID_PW_DATA_INCU_YN" to "Y",
            "FID_FAKE_TICK_INCU_YN" to "",
        )
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        val uri = URI.create("${properties.restUrl.toString().trimEnd('/')}/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice?$query")
        throttle()
        var response = send(uri, accessToken())
        if (response.statusCode() == 401) {
            token = null
            response = send(uri, accessToken())
        }
        if (response.statusCode() == 429) throw KisRateLimit()
        check(response.statusCode() == 200) { "KIS historical request failed: HTTP ${response.statusCode()}" }
        val root = mapper.readTree(response.body())
        if (root.path("msg_cd").asText() == "EGW00201") throw KisRateLimit()
        check(root.path("rt_cd").asText() == "0") {
            "KIS historical request failed: ${root.path("msg_cd").asText("UNKNOWN")}" }
        val output = root.path("output2")
        check(output.isArray) { "KIS historical output2 missing" }
        return KisPage(response.body(), output.toList())
    }

    private fun send(uri: URI, bearer: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("authorization", "Bearer $bearer")
            .header("appkey", properties.appKey)
            .header("appsecret", properties.appSecret)
            .header("tr_id", "FHKST03010230")
            .header("custtype", "P")
            .GET().build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun accessToken(): String {
        token?.let { return it }
        val uri = URI.create("${properties.restUrl.toString().trimEnd('/')}/oauth2/tokenP")
        val body = mapper.writeValueAsString(mapOf("grant_type" to "client_credentials",
            "appkey" to properties.appKey, "appsecret" to properties.appSecret))
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "KIS token request failed: HTTP ${response.statusCode()}" }
        return mapper.readTree(response.body()).path("access_token").asText()
            .takeIf(String::isNotBlank)?.also { token = it } ?: error("KIS token missing")
    }

    private fun throttle() {
        val gap = properties.minRequestGap.toMillis()
        val wait = lastRequestAt + gap - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }
}
