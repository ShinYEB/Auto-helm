package dev.autohelm.prediction

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.autohelm.model.ModelBundleIO
import dev.autohelm.model.ModelHead
import dev.autohelm.model.ModelManifest
import dev.autohelm.model.ModelWeights
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
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun `loads active model and predicts only after consecutive ready minutes`() {
        val io = ModelBundleIO(mapper)
        val weights = ModelWeights(mapOf(
            "NEXT_1M" to ModelHead(0.01, 0.0, 100, 20, 1.0, 2.0),
            "AFTER_1H" to ModelHead(0.02, 0.0, 100, 20, 1.0, 2.0),
        ))
        val version = "test-model-v1"
        val root = directory.resolve("dev/models")
        val bundle = io.write(root.resolve("bundles/$version"), ModelManifest(
            version = version, symbol = "005930", venue = "KRX", session = "regular",
            datasetSha256 = "a".repeat(64), weightsSha256 = ModelBundleIO.sha256(mapper.writeValueAsBytes(weights)),
            createdAt = start, eligible = true,
        ), weights)
        io.activate(root, bundle)
        val models = io.loadActive(root)
        val now = AtomicReference(start.plusSeconds(62))
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now.get()
        }
        val engine = BaselinePredictionEngine(properties, PredictionJournal(properties, mapper), clock, models)
        val first = InputBar("bar-first", "dev", "005930", "KRX", "regular", start,
            start.plusSeconds(60), 10_000, "READY", start.plusSeconds(61))
        val second = first.copy(id = "bar-second", minuteStart = start.plusSeconds(60),
            minuteEnd = start.plusSeconds(120), close = 10_010, sealedAt = start.plusSeconds(121))
        assertThat(engine.accept(first).take(2).map { it.status }).containsOnly(PredictionStatus.WARMING_UP)
        now.set(start.plusSeconds(122))
        val predictions = engine.accept(second)
        assertThat(predictions.take(2).map { it.status }).containsOnly(PredictionStatus.READY)
        assertThat(predictions.take(2).map { it.modelVersion }).containsOnly(version)
        assertThat(predictions.take(2).map { it.predictedPrice }).containsExactly(10_110L, 10_212L)
        assertThat(predictions.take(2).map { it.baselinePrice }).containsOnly(10_010L)
    }
}
