package dev.autohelm.market

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Component
class MarketWorker(
    private val properties: MarketProperties,
    mapper: ObjectMapper,
    publisher: ApplicationEventPublisher,
    managedSymbols: ManagedMarketSymbols,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clock = Clock.systemUTC()
    private val health = MarketHealthTracker(properties, managedSymbols::current)
    private val healthFile = MarketHealthFile(properties, mapper)
    @Volatile private var firstBarAt: Instant? = null
    @Volatile private var lastBarAt: Instant? = null
    @Volatile private var lastReadyBarAt: Instant? = null
    private val engine = MarketEngine(properties, MarketJournal(properties, mapper), managedSymbols::current,
        activeFrom = managedSymbols::activeFrom,
        onBar = {
            if (firstBarAt == null) firstBarAt = it.sealedAt
            lastBarAt = it.sealedAt
            if (it.quality == BarQuality.READY) lastReadyBarAt = it.sealedAt
            publisher.publishEvent(it)
        }, onTick = health::received)
    private val queue = ArrayBlockingQueue<RawFrame>(properties.queueCapacity)
    private val overflowStarted = AtomicReference<Instant?>(null)
    private val disconnectedAt = AtomicReference<Instant?>(null)
    private val restoredGap = AtomicReference<MarketGap?>(null)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        engine.recover(clock.instant())
        disconnectedAt.set(clock.instant())
        running = true
        thread = Thread(::runLoop, "market-worker").also { it.isDaemon = true; it.start() }
    }

    /** Never waits for disk or analysis on the WebSocket callback thread. */
    fun offer(frame: RawFrame) {
        if (!running) return
        if (!queue.offer(frame)) overflowStarted.compareAndSet(null, frame.receivedAt)
    }

    fun connectionLost(at: Instant) {
        disconnectedAt.compareAndSet(null, at)
        health.disconnected(at, "CONNECTION_LOST")
    }

    fun connectionRestored(at: Instant) {
        val start = disconnectedAt.getAndSet(null)
        if (start != null && at.isAfter(start)) {
            restoredGap.set(MarketGap(properties.environment, start, at, "DISCONNECTED"))
        }
        health.restored(at)
    }

    fun subscriptionStarted(at: Instant) = health.subscribing(at)

    fun subscriptionConfirmed(symbol: String, venue: Venue, at: Instant) = health.confirmed(symbol, venue, at)

    fun healthSnapshot(): List<MarketHealth> = health.snapshot(clock.instant())

    private fun runLoop() {
        var lastHealthWrite = Instant.EPOCH
        while (running || queue.isNotEmpty()) {
            try {
                val frame = queue.poll(500, TimeUnit.MILLISECONDS)
                restoredGap.getAndSet(null)?.let { engine.markGap(it.startedAt, it.endedAt, it.reason) }
                val gapStart = overflowStarted.getAndSet(null)
                if (gapStart != null) engine.markGap(gapStart, clock.instant(), "QUEUE_OVERFLOW")
                if (frame != null) engine.accept(frame)
                if (disconnectedAt.get() == null) engine.seal(clock.instant())
                val now = clock.instant()
                if (now.isAfter(lastHealthWrite.plusSeconds(5))) {
                    lastHealthWrite = now
                    healthFile.write(now, health.snapshot(now), firstBarAt, lastBarAt, lastReadyBarAt)
                }
            } catch (ex: InterruptedException) {
                if (!running && queue.isEmpty()) break
            } catch (ex: Exception) {
                log.error("Market pipeline stopped accepting this cycle: {}", ex.message)
                overflowStarted.compareAndSet(null, clock.instant())
            }
        }
    }

    @PreDestroy
    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(3000)
    }
}
