package dev.autohelm.prediction

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@ConfigurationProperties("prediction")
data class PredictionProperties(
    val enabled: Boolean = false,
    val environment: String = "dev",
    val dataDir: Path = Path.of("./data"),
    val maxLag: Duration = Duration.ofSeconds(30),
    val sessions: List<PredictionSession> = emptyList(),
) {
    init {
        require(environment in setOf("dev", "prd"))
        require(!maxLag.isNegative && !maxLag.isZero)
    }
}

data class PredictionSession(
    val venue: String,
    val name: String,
    val dates: List<LocalDate>,
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        require(venue in setOf("KRX", "NXT") && name.isNotBlank() && start.isBefore(end))
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class InputBar(
    val id: String,
    val environment: String,
    val symbol: String,
    val venue: String,
    val session: String,
    val minuteStart: Instant,
    val minuteEnd: Instant,
    val close: Long?,
    val quality: String,
    val sealedAt: Instant,
)

enum class TargetType { NEXT_1M, AFTER_1H, KRX_CLOSE, NXT_END }
enum class PredictionStatus { READY, WARMING_UP, INPUT_INCOMPLETE, STALE, TARGET_CLOSED, ACTUAL_DEFINITION_UNCONFIRMED, MODEL_ERROR }

data class PredictionRecord(
    val id: String,
    val environment: String,
    val symbol: String,
    val venue: String,
    val session: String,
    val barId: String,
    val barMinuteStart: Instant,
    val barSealedAt: Instant,
    val createdAt: Instant,
    val target: TargetType,
    val targetAt: Instant?,
    val predictedPrice: Long?,
    val baselinePrice: Long?,
    val status: PredictionStatus,
    val modelVersion: String = "baseline-persistence-v1",
)

data class RejectedBar(
    val streamId: String,
    val reason: String,
    val receivedAt: Instant,
)
