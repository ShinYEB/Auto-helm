package dev.autohelm.market

import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Single-threaded market pipeline. Socket callbacks submit work through MarketWorker. */
class MarketEngine(
    private val properties: MarketProperties,
    private val journal: MarketJournal,
    private val parser: KisTickParser = KisTickParser(properties),
    private val onBar: (MinuteBar) -> Unit = {},
    private val onTick: (MarketTick) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val builder = MinuteBarBuilder(properties)
    private val seoul = ZoneId.of("Asia/Seoul")
    private val latestTicks = mutableMapOf<Pair<String, Venue>, MarketTick>()
    private var lastRawAt: Instant? = null
    private var lastBarSealedAt: Instant? = null

    fun recover(now: Instant) {
        val today = LocalDate.ofInstant(now, seoul)
        for (date in listOf(today.minusDays(1), today)) {
            journal.readBars(date).forEach { bar ->
                builder.rememberSealed(bar)
                lastBarSealedAt = maxOf(lastBarSealedAt ?: bar.sealedAt, bar.sealedAt)
            }
            journal.readGaps(date).forEach(builder::markGap)
            journal.forEachRaw(date) { frame ->
                lastRawAt = maxOf(lastRawAt ?: frame.receivedAt, frame.receivedAt)
                try {
                    parser.parse(frame).forEach { applyTick(it, false) }
                } catch (_: Exception) {
                    // The corresponding gap was written when the frame was first received.
                }
            }
        }
        val last = listOfNotNull(lastRawAt, lastBarSealedAt).maxOrNull()
        if (last != null && last.isBefore(now)) markGap(last, now, "PROCESS_RESTART")
    }

    fun accept(frame: RawFrame) {
        journal.appendRaw(frame)
        lastRawAt = frame.receivedAt
        try {
            parser.parse(frame).forEach { applyTick(it, true) }
        } catch (ex: Exception) {
            log.warn("Rejected market frame {}: {}", frame.id, ex.message)
            markGap(frame.receivedAt.minusSeconds(60), frame.receivedAt.plusSeconds(60), "INVALID_FRAME")
        }
    }

    fun markGap(start: Instant, end: Instant, reason: String) {
        if (!end.isAfter(start)) return
        val gap = MarketGap(properties.environment, start, end, reason)
        journal.appendGap(gap)
        builder.markGap(gap)
    }

    fun seal(now: Instant) {
        for (bar in builder.readyToSeal(now)) {
            journal.appendBar(bar)
            builder.markSealed(bar)
            lastBarSealedAt = now
            onBar(bar)
        }
    }

    fun latestSnapshot(): List<MarketTick> = latestTicks.values.toList()

    private fun applyTick(tick: MarketTick, live: Boolean) {
        val minute = tick.sourceAt.truncatedTo(ChronoUnit.MINUTES)
        val end = minute.plus(1, ChronoUnit.MINUTES)
        if (tick.receivedAt.isAfter(end.plus(properties.lateGrace))) {
            if (live) markGap(minute, end, "LATE_TICK")
            return
        }
        if (builder.accept(tick)) {
            latestTicks[tick.symbol to tick.venue] = tick
            if (live) onTick(tick)
        }
    }
}
