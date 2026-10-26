package dev.autohelm.prediction

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class BaselinePredictionEngineTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).addModule(JavaTimeModule()).build()
    private val start = Instant.parse("2026-10-26T01:00:00Z") // 10:00 KST
    private val properties get() = PredictionProperties(
        dataDir = directory,
        sessions = listOf(PredictionSession("KRX", "regular", listOf(LocalDate.parse("2026-10-26")),
            LocalTime.parse("09:00"), LocalTime.parse("15:30"))),
    )

    @Test
    fun `predicts only valid short targets and persists once across restart`() {
        val now = start.plusSeconds(62)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val bar = InputBar("dev:005930:KRX:regular:$start", "dev", "005930", "KRX", "regular",
            start, start.plusSeconds(60), 80_000, "READY", start.plusSeconds(61))
        val first = BaselinePredictionEngine(properties, PredictionJournal(properties, mapper), clock).accept(bar)
        assertThat(first.map { it.status }).containsExactly(
            PredictionStatus.READY, PredictionStatus.READY, PredictionStatus.ACTUAL_DEFINITION_UNCONFIRMED)
        assertThat(first.take(2).map { it.predictedPrice }).containsExactly(80_000, 80_000)
        assertThat(first.last().predictedPrice).isNull()
        BaselinePredictionEngine(properties, PredictionJournal(properties, mapper), clock).accept(bar)
        val file = directory.resolve("dev/prediction/predictions/2026-10-26.jsonl")
        assertThat(Files.readAllLines(file)).hasSize(3)
    }

    @Test
    fun `incomplete or late input never creates a usable price`() {
        val bar = InputBar("bar-2", "dev", "005930", "KRX", "regular", start,
            start.plusSeconds(60), 80_000, "INCOMPLETE", start.plusSeconds(61))
        val clock = Clock.fixed(start.plus(Duration.ofMinutes(5)), ZoneOffset.UTC)
        val engine = BaselinePredictionEngine(properties, PredictionJournal(properties, mapper), clock)
        assertThat(engine.accept(bar).map { it.status }).containsOnly(PredictionStatus.INPUT_INCOMPLETE)
        val late = bar.copy(id = "bar-3", quality = "READY")
        assertThat(engine.accept(late).take(2).map { it.status }).containsOnly(PredictionStatus.STALE)
    }
}
