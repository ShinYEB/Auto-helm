package dev.autohelm.prediction

import dev.autohelm.model.ModelBundle
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** First model: persistence baseline, with explicit unusable states for missing input/targets. */
class BaselinePredictionEngine(
    private val properties: PredictionProperties,
    private val journal: PredictionJournal,
    private val clock: Clock = Clock.systemUTC(),
    private val models: Map<String, ModelBundle> = emptyMap(),
) {
    private val zone = ZoneId.of("Asia/Seoul")
    private val lastReady = mutableMapOf<String, InputBar>()

    fun accept(bar: InputBar): List<PredictionRecord> {
        require(bar.environment == properties.environment)
        require(bar.symbol.matches(Regex("[0-9A-Z]{6}")))
        require(bar.venue in setOf("KRX", "NXT"))
        require(bar.minuteStart.plusSeconds(60) == bar.minuteEnd)
        val marketKey = "${bar.symbol}:${bar.venue}:${bar.session}"
        val model = models[marketKey]
        val last = lastReady[marketKey]
        val previous = last?.takeIf { it.minuteStart.plusSeconds(60) == bar.minuteStart }
        if (last == null || !bar.minuteStart.isBefore(last.minuteStart)) {
            if (bar.quality == "READY" && bar.close != null && bar.close > 0) lastReady[marketKey] = bar
            else lastReady.remove(marketKey)
        }
        val modelVersion = model?.manifest?.version ?: "baseline-persistence-v1"
        val now = clock.instant()
        val session = properties.sessions.firstOrNull {
            it.venue == bar.venue && it.name == bar.session &&
                bar.minuteStart.atZone(zone).toLocalDate() in it.dates
        }
        val targets = listOf(TargetType.NEXT_1M, TargetType.AFTER_1H) +
            if (bar.venue == "KRX") TargetType.KRX_CLOSE else TargetType.NXT_END
        return targets.map { target ->
            val targetStart = when (target) {
                TargetType.NEXT_1M -> bar.minuteStart.plusSeconds(60)
                TargetType.AFTER_1H -> bar.minuteStart.plusSeconds(3600)
                else -> null
            }
            val targetAt = targetStart?.plusSeconds(60)
            val withinSession = session != null && targetStart != null &&
                !targetStart.atZone(zone).toLocalTime().isBefore(session.start) &&
                !targetAt!!.atZone(zone).toLocalTime().isAfter(session.end) &&
                targetStart.atZone(zone).toLocalDate() == bar.minuteStart.atZone(zone).toLocalDate()
            var status = when {
                bar.quality != "READY" || bar.close == null || bar.close <= 0 -> PredictionStatus.INPUT_INCOMPLETE
                targetStart == null -> PredictionStatus.ACTUAL_DEFINITION_UNCONFIRMED
                now.isAfter(bar.minuteEnd.plus(properties.maxLag)) || !now.isBefore(targetAt ?: Instant.MAX) -> PredictionStatus.STALE
                !withinSession -> PredictionStatus.TARGET_CLOSED
                model != null && previous == null -> PredictionStatus.WARMING_UP
                else -> PredictionStatus.READY
            }
            val modelPrice = if (status == PredictionStatus.READY && model != null)
                model.predict(target.name, bar.close!!, previous!!.close!!) else null
            if (status == PredictionStatus.READY && model != null && modelPrice == null) status = PredictionStatus.MODEL_ERROR
            val usable = status == PredictionStatus.READY
            PredictionRecord(
                id = "${bar.environment}:${bar.id}:$target:$modelVersion",
                environment = bar.environment,
                symbol = bar.symbol,
                venue = bar.venue,
                session = bar.session,
                barId = bar.id,
                barMinuteStart = bar.minuteStart,
                barSealedAt = bar.sealedAt,
                createdAt = now,
                target = target,
                targetAt = targetAt,
                predictedPrice = if (usable) modelPrice ?: bar.close else null,
                baselinePrice = if (usable) bar.close else null,
                status = status,
                modelVersion = modelVersion,
            ).also(journal::append)
        }
    }
}
