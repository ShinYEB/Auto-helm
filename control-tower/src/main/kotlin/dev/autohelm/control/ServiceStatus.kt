package dev.autohelm.control

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ServiceObservation(
    val service: String,
    val state: String,
    val observedAt: Instant?,
    val detail: String,
)

/** Reads each service's own durable output. It does not infer process health from old data. */
@Component
class ServiceStatus(
    private val properties: ControlProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val zone = ZoneId.of("Asia/Seoul")
    private val root get() = properties.dataDir.resolve(properties.environment)

    fun snapshot(): List<ServiceObservation> = listOf(event(), market(), prediction())

    private fun event(): ServiceObservation = observe("event-data") {
        val path = root.resolve("events/health.json")
        if (!Files.exists(path)) return@observe missing("event-data")
        val node = mapper.readTree(Files.readAllBytes(path))
        val at = Instant.parse(node.path("heartbeatAt").asText())
        val pid = node.path("pid").asLong(-1)
        val alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        val running = node.path("running").asBoolean(false)
        val state = when {
            !running || !alive -> "STOPPED"
            Duration.between(at, clock.instant()) > Duration.ofSeconds(20) -> "STALE"
            else -> "RUNNING"
        }
        ServiceObservation("event-data", state, at, "DART/GDELT 상태 파일")
    }

    private fun market(): ServiceObservation = observe("market-data") {
        val date = LocalDate.now(clock.withZone(zone))
        val path = root.resolve("market-day-$date.status.json")
        if (!Files.exists(path)) return@observe missing("market-data")
        val node = mapper.readTree(Files.readAllBytes(path))
        val at = Instant.parse(node.path("at").asText())
        val pid = node.path("collector_pid").asLong(-1)
        val alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        val state = if (node.path("state").asText() == "running" &&
            alive && Duration.between(at, clock.instant()) <= Duration.ofSeconds(30)) "RUNNING" else "RECORDED"
        ServiceObservation("market-data", state, at, "시세 감독 상태 파일과 수집기 PID")
    }

    private fun prediction(): ServiceObservation = observe("price-prediction") {
        val date = LocalDate.now(clock.withZone(zone))
        val path = root.resolve("prediction/predictions/$date.jsonl")
        if (!Files.exists(path)) return@observe missing("price-prediction")
        val at = Files.getLastModifiedTime(path).toInstant()
        val state = if (Duration.between(at, clock.instant()) <= Duration.ofMinutes(2)) "RECENT_OUTPUT" else "STALE_OUTPUT"
        ServiceObservation("price-prediction", state, at, "예측 기록의 최근 수정 시각; 프로세스 상태는 아님")
    }

    private fun missing(service: String) = ServiceObservation(service, "NO_DATA", null, "현재 환경의 기록 없음")

    private inline fun observe(service: String, block: () -> ServiceObservation): ServiceObservation =
        try { block() } catch (_: Exception) {
            ServiceObservation(service, "READ_ERROR", null, "상태 파일을 읽을 수 없음")
        }
}
