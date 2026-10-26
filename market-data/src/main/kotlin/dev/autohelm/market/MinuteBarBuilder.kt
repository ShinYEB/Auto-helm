package dev.autohelm.market

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class MinuteBarBuilder(
    private val properties: MarketProperties,
    private val subscriptions: () -> Map<String, Set<Venue>> = {
        properties.symbols.associateWith { Venue.entries.toSet() }
    },
    private val activeFrom: (String, Venue) -> Instant? = { _, _ -> null },
) {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val pending = mutableMapOf<BarKey, MutableList<MarketTick>>()
    private val sealed = mutableSetOf<String>()
    private val gaps = mutableListOf<MarketGap>()

    fun rememberSealed(bar: MinuteBar) { sealed += bar.id }
    fun markGap(gap: MarketGap) { gaps += gap }

    fun accept(tick: MarketTick): Boolean {
        val activated = activeFrom(tick.symbol, tick.venue)
        if (activated != null && tick.sourceAt.isBefore(
                activated.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES))) return false
        val key = BarKey(tick.symbol, tick.venue, tick.session, tick.sourceAt.truncatedTo(ChronoUnit.MINUTES))
        if (key.id(properties.environment) in sealed) return false
        val bucket = pending.getOrPut(key) { mutableListOf() }
        if (bucket.any { it.id == tick.id }) return false
        bucket += tick
        return true
    }

    fun readyToSeal(now: Instant): List<MinuteBar> {
        val cutoff = now.minus(properties.lateGrace)
        val result = mutableListOf<MinuteBar>()
        val selected = subscriptions()
        for (window in properties.sessions) {
            for (date in window.dates) {
                var minute = LocalDateTime.of(date, window.start).atZone(seoul).toInstant()
                val end = LocalDateTime.of(date, window.end).atZone(seoul).toInstant()
                while (minute < end && !minute.plus(1, ChronoUnit.MINUTES).isAfter(cutoff)) {
                    for ((symbol, venues) in selected) {
                        if (window.venue !in venues) continue
                        val activated = activeFrom(symbol, window.venue)
                        if (activated != null && minute.isBefore(
                                activated.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES))) continue
                        val key = BarKey(symbol, window.venue, window.name, minute)
                        val id = key.id(properties.environment)
                        if (id !in sealed) result += build(key, id, now)
                    }
                    minute = minute.plus(1, ChronoUnit.MINUTES)
                }
            }
        }
        return result
    }

    fun markSealed(bar: MinuteBar) {
        sealed += bar.id
        pending.remove(BarKey(bar.symbol, bar.venue, bar.session, bar.minuteStart))
    }

    private fun build(key: BarKey, id: String, now: Instant): MinuteBar {
        val ticks = pending[key].orEmpty().sortedWith(compareBy<MarketTick> { it.sourceAt }.thenBy { it.receivedAt }.thenBy { it.id })
        val end = key.minuteStart.plus(1, ChronoUnit.MINUTES)
        val quality = when {
            gaps.any { it.startedAt < end && it.endedAt > key.minuteStart } -> BarQuality.INCOMPLETE
            ticks.isEmpty() -> BarQuality.EMPTY_UNVERIFIED
            else -> BarQuality.READY
        }
        return MinuteBar(
            id = id, environment = properties.environment, symbol = key.symbol, venue = key.venue,
            session = key.session, minuteStart = key.minuteStart, minuteEnd = end,
            open = ticks.firstOrNull()?.price, high = ticks.maxOfOrNull { it.price },
            low = ticks.minOfOrNull { it.price }, close = ticks.lastOrNull()?.price,
            volume = ticks.sumOf { it.volume }, tradeCount = ticks.size, quality = quality,
            sealedAt = now, firstTickId = ticks.firstOrNull()?.id, lastTickId = ticks.lastOrNull()?.id,
        )
    }
}
