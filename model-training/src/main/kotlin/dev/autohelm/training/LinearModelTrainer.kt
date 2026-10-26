package dev.autohelm.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.model.ModelBundle
import dev.autohelm.model.ModelBundleIO
import dev.autohelm.model.ModelHead
import dev.autohelm.model.ModelManifest
import dev.autohelm.model.ModelWeights
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Tiny two-head linear return model; training never runs in the Stream consumer. */
class LinearModelTrainer(
    private val properties: TrainingProperties,
    private val mapper: ObjectMapper,
    private val io: ModelBundleIO,
) {
    private data class Sample(val date: LocalDate, val feature: Double, val targetReturn: Double,
        val currentClose: Long, val actualClose: Long)

    fun fit(): ModelBundle {
        properties.requireMarket()
        require(properties.environment == "dev") { "Train candidate models in dev" }
        val from = requireNotNull(properties.from)
        val to = requireNotNull(properties.to)
        require(!from.isAfter(to) && ChronoUnit.DAYS.between(from, to) <= 366)
        val days = from.datesUntil(to.plusDays(1)).toList().mapNotNull(::readDay)
        require(days.size >= 3) { "At least three complete trading days are required" }
        val dates = days.map { it.first }.sorted()
        val validationDates = dates.takeLast(maxOf(1, dates.size / 5)).toSet()
        val all = days.flatMap { (_, bars) -> bars }
        val datasetHash = ModelBundleIO.sha256(all.joinToString("\n") { mapper.writeValueAsString(it) }.toByteArray())
        val heads = mapOf(
            "NEXT_1M" to fitHead(days, validationDates, 1),
            "AFTER_1H" to fitHead(days, validationDates, 60),
        )
        val weights = ModelWeights(heads)
        val weightsHash = ModelBundleIO.sha256(mapper.writeValueAsBytes(weights))
        val eligible = heads.values.all { it.validationMae < it.baselineMae * 0.995 }
        val version = "linret1-${Instant.now().toEpochMilli()}"
        val manifest = ModelManifest(version = version, symbol = properties.symbol, venue = properties.venue,
            session = properties.session, datasetSha256 = datasetHash, weightsSha256 = weightsHash,
            createdAt = Instant.now(), eligible = eligible)
        val directory = properties.dataDir.resolve("dev/training/bundles").resolve(version)
        return io.write(directory, manifest, weights)
    }

    private fun readDay(date: LocalDate): Pair<LocalDate, List<HistoricalBar>>? {
        val root = properties.dataDir.resolve("dev/historical")
        val relative = "${properties.symbol}/${properties.venue}/${properties.session}/$date"
        val manifestPath = root.resolve("manifest/$relative.json")
        if (!Files.exists(manifestPath)) return null
        val manifest = mapper.readValue<HistoricalManifest>(Files.readAllBytes(manifestPath))
        if (manifest.state == "EMPTY") return null
        require(manifest.state == "COMPLETE" && manifest.environment == "dev" &&
            manifest.symbol == properties.symbol && manifest.venue == properties.venue &&
            manifest.session == properties.session && manifest.date == date)
        val barPath = root.resolve("bars/$relative.jsonl")
        val bytes = Files.readAllBytes(barPath)
        require(ModelBundleIO.sha256(bytes) == manifest.barsSha256) { "Historical day checksum mismatch" }
        val bars = String(bytes, Charsets.UTF_8).lineSequence().filter(String::isNotBlank)
            .map { mapper.readValue<HistoricalBar>(it) }.sortedBy(HistoricalBar::minuteStart).toList()
        require(bars.size == manifest.barCount && bars.all {
            it.environment == "dev" && it.symbol == properties.symbol && it.venue == properties.venue &&
                it.session == properties.session && it.quality == "PROVIDER_REPORTED" && it.close > 0
        })
        require(bars.map(HistoricalBar::minuteStart).distinct().size == bars.size)
        return date to bars
    }

    private fun fitHead(days: List<Pair<LocalDate, List<HistoricalBar>>>, validationDates: Set<LocalDate>,
                        horizonMinutes: Long): ModelHead {
        val samples = days.flatMap { (date, bars) ->
            val byTime = bars.associateBy(HistoricalBar::minuteStart)
            bars.mapNotNull { current ->
                val previous = byTime[current.minuteStart.minusSeconds(60)] ?: return@mapNotNull null
                val target = byTime[current.minuteStart.plusSeconds(horizonMinutes * 60)] ?: return@mapNotNull null
                Sample(date, ln(current.close.toDouble() / previous.close),
                    ln(target.close.toDouble() / current.close), current.close, target.close)
            }
        }
        val training = samples.filter { it.date !in validationDates }
        val validation = samples.filter { it.date in validationDates }
        require(training.size >= 30 && validation.size >= 10) { "Insufficient samples for $horizonMinutes-minute target" }
        val meanX = training.map(Sample::feature).average()
        val meanY = training.map(Sample::targetReturn).average()
        val covariance = training.sumOf { (it.feature - meanX) * (it.targetReturn - meanY) }
        val variance = training.sumOf { (it.feature - meanX) * (it.feature - meanX) }
        val slope = if (variance > 0) covariance / (variance * 1.01) else 0.0
        val intercept = meanY - slope * meanX
        require(slope.isFinite() && intercept.isFinite())
        val modelMae = validation.map {
            val estimate = it.currentClose * exp(intercept + slope * it.feature)
            abs(estimate - it.actualClose)
        }.average()
        val baselineMae = validation.map { abs(it.currentClose - it.actualClose).toDouble() }.average()
        return ModelHead(intercept, slope, training.size, validation.size, modelMae, baselineMae)
    }
}
