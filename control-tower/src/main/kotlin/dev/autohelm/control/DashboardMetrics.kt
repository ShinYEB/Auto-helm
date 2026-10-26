package dev.autohelm.control

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class DashboardStat(val id: String, val title: String, val value: Double?, val unit: String,
                         val sampledAt: Instant?)
data class DashboardPoint(val at: Instant, val value: Double)
data class DashboardSeries(val label: String, val points: List<DashboardPoint>)
data class DashboardChart(val id: String, val title: String, val unit: String,
                          val series: List<DashboardSeries>)
data class DashboardSnapshot(val available: Boolean, val environment: String, val range: String,
                             val at: Instant, val stats: List<DashboardStat>, val charts: List<DashboardChart>)

/** Fixed PromQL catalog. The browser cannot submit expressions or contact Prometheus directly. */
@Component
class DashboardMetrics(
    private val properties: ControlProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) {
    private data class StatSpec(val id: String, val title: String, val unit: String, val query: String)
    private data class ChartSpec(val id: String, val title: String, val unit: String, val query: String)

    private val stats = listOf(
        StatSpec("collector", "시세 수집기", "boolean", "autohelm_collector_up"),
        StatSpec("restarts", "시세 수집기 재시작", "count", "autohelm_collector_restarts_total"),
        StatSpec("krxLag", "KRX 장중 수신 지연", "seconds", "(time() - autohelm_market_last_frame_timestamp_seconds{venue=\"KRX\"}) and on(venue) (autohelm_market_session_open{venue=\"KRX\"} == 1)"),
        StatSpec("nxtLag", "NXT 장중 수신 지연", "seconds", "(time() - autohelm_market_last_frame_timestamp_seconds{venue=\"NXT\"}) and on(venue) (autohelm_market_session_open{venue=\"NXT\"} == 1)"),
        StatSpec("event", "사건 수집기", "boolean", "autohelm_event_up"),
        StatSpec("dartSuccessAge", "DART 마지막 성공 후", "seconds", "time() - autohelm_event_last_success_timestamp_seconds{source=\"DART\"}"),
        StatSpec("dartDiscovered", "DART 발견 공시", "count", "autohelm_event_discovered_total{source=\"DART\"}"),
        StatSpec("dartFailures", "DART 실패 조회", "count", "autohelm_event_queries_total{source=\"DART\",result=\"failure\"}"),
    )
    private val charts = listOf(
        ChartSpec("frames", "시장별 수신 프레임/초", "perSecond", "rate(autohelm_market_frames_total[1m])"),
        ChartSpec("bars", "1분봉 품질 누적", "count", "autohelm_market_bars_total"),
        ChartSpec("gaps", "결손 기록 누적", "count", "autohelm_market_gaps_total"),
        ChartSpec("errors", "오류 이벤트 누적", "count", "autohelm_market_log_events_total"),
        ChartSpec("queries", "제공처별 30분 조회", "count", "increase(autohelm_event_queries_total{result=~\"success|failure\"}[30m])"),
        ChartSpec("events", "30분 새 사건과 제한 응답", "count", "increase(autohelm_event_discovered_total[30m]) or increase(autohelm_event_rate_limits_total[30m])"),
    )

    fun snapshot(range: String): DashboardSnapshot {
        val (seconds, step) = when (range) {
            "1h" -> 3600L to 30L
            "6h" -> 21600L to 120L
            "24h" -> 86400L to 300L
            else -> throw IllegalArgumentException("range must be 1h, 6h, or 24h")
        }
        val now = clock.instant()
        return try {
            val statValues = stats.map { spec ->
                val result = request("query", "query" to spec.query).path("data").path("result").firstOrNull()
                val value = result?.path("value")
                DashboardStat(spec.id, spec.title, value?.get(1)?.asText()?.toDoubleOrNull()?.takeIf(Double::isFinite),
                    spec.unit, value?.get(0)?.asDouble()?.let { Instant.ofEpochMilli((it * 1000).toLong()) })
            }
            val chartValues = charts.map { spec ->
                val result = request("query_range", "query" to spec.query,
                    "start" to (now.epochSecond - seconds).toString(), "end" to now.epochSecond.toString(),
                    "step" to step.toString()).path("data").path("result")
                val series = result.map { item ->
                    DashboardSeries(label(spec.id, item.path("metric")), item.path("values").mapNotNull { sample ->
                        val value = sample.get(1)?.asText()?.toDoubleOrNull()?.takeIf(Double::isFinite)
                        value?.let { DashboardPoint(Instant.ofEpochMilli((sample[0].asDouble() * 1000).toLong()), it) }
                    })
                }.filter { it.points.isNotEmpty() }
                DashboardChart(spec.id, spec.title, spec.unit, series)
            }
            DashboardSnapshot(true, properties.environment, range, now, statValues, chartValues)
        } catch (_: Exception) {
            DashboardSnapshot(false, properties.environment, range, now, emptyList(), emptyList())
        }
    }

    private fun label(id: String, metric: JsonNode): String = when (id) {
        "frames" -> metric.path("venue").asText("시장")
        "bars" -> "${metric.path("venue").asText("시장")} ${metric.path("quality").asText("품질")}"
        "gaps" -> metric.path("reason").asText("결손")
        "errors" -> metric.path("kind").asText("오류")
        "queries" -> "${metric.path("source").asText("제공처")} ${metric.path("result").asText("결과")}"
        else -> "${metric.path("source").asText("제공처")} " +
            if (metric.path("__name__").asText().contains("rate_limits")) "제한" else "새 사건"
    }

    private fun request(action: String, vararg params: Pair<String, String>): JsonNode {
        val query = params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        val uri = URI.create("${properties.prometheusUrl.toString().trimEnd('/')}/api/v1/$action?$query")
        val response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
            HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200)
        val json = mapper.readTree(response.body())
        check(json.path("status").asText() == "success")
        return json
    }
}
