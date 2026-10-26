package dev.autohelm.prediction

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** First model: persistence baseline, with explicit unusable states for missing input/targets. */
class BaselinePredictionEngine(
    private val properties: PredictionProperties,
    private val journal: PredictionJournal,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val zone = ZoneId.of("Asia/Seoul")

    fun accept(bar: InputBar): List<PredictionRecord> {
        require(bar.environment == properties.environment)
        require(bar.symbol.matches(Regex("[0-9A-Z]{6}")))
        require(bar.venue in setOf("KRX", "NXT"))
        require(bar.minuteStart.plusSeconds(60) == bar.minuteEnd)
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
            val status = when {
                bar.quality != "READY" || bar.close == null || bar.close <= 0 -> PredictionStatus.INPUT_INCOMPLETE
                targetStart == null -> PredictionStatus.ACTUAL_DEFINITION_UNCONFIRMED
                now.isAfter(bar.minuteEnd.plus(properties.maxLag)) || !now.isBefore(targetAt ?: Instant.MAX) -> PredictionStatus.STALE
                !withinSession -> PredictionStatus.TARGET_CLOSED
                else -> PredictionStatus.READY
            }
            val usable = status == PredictionStatus.READY
            PredictionRecord(
                id = "${bar.environment}:${bar.id}:$target:baseline-persistence-v1",
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
                predictedPrice = if (usable) bar.close else null,
                baselinePrice = if (usable) bar.close else null,
                status = status,
            ).also(journal::append)
        }
    }
}
