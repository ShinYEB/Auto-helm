package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Independent process and journal; no calls into the WebSocket service. */
@Component
class EventCollector(
    private val properties: EventProperties,
    mapper: ObjectMapper,
    private val managedSymbols: ManagedEventSymbols = ManagedEventSymbols(properties, mapper),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clock = Clock.systemUTC()
    private val journal = EventJournal(properties, mapper)
    private val health = EventHealth(properties, mapper)
    private val gdelt = GdeltAdapter(properties, mapper)
    private val dart = OpenDartAdapter(properties, mapper)
    private val seen = mutableSetOf<String>()
    private val seenLinks = mutableSetOf<String>()
    private val nextGdelt = mutableMapOf<String, Instant>()
    private val nextDart = mutableMapOf<String, Instant>()
    private var lastGdeltRequest = Instant.EPOCH
    private var gdeltBlockedUntil = Instant.EPOCH
    private var gdeltBackoff = Duration.ofMinutes(1)
    @Volatile private var running = false
    private var thread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.enabled || running) return
        seen += journal.existingIds()
        seenLinks += journal.existingLinkIds()
        running = true
        health.started(seen)
        thread = Thread(::loop, "event-poller").also { it.start() }
    }

    private fun loop() {
        while (running) {
            health.heartbeat()
            val now = clock.instant()
            val gdeltSearches = managedSymbols.gdeltSearches()
            val dartCorpCodes = managedSymbols.dartCorpCodes()
            nextGdelt.keys.retainAll(gdeltSearches.keys)
            nextDart.keys.retainAll(dartCorpCodes.keys)
            gdeltSearches.keys.forEach { nextGdelt.putIfAbsent(it, Instant.EPOCH) }
            dartCorpCodes.keys.forEach { nextDart.putIfAbsent(it, Instant.EPOCH) }
            if (properties.dart.enabled) {
                val symbol = nextDart.entries.firstOrNull { !now.isBefore(it.value) }?.key
                if (symbol != null) pollDart(symbol, dartCorpCodes.getValue(symbol), now)
            }
            if (properties.gdelt.enabled && !now.isBefore(gdeltBlockedUntil) &&
                !now.isBefore(lastGdeltRequest.plus(properties.gdelt.minRequestGap))) {
                val symbol = nextGdelt.entries.firstOrNull { !now.isBefore(it.value) }?.key
                if (symbol != null) pollGdelt(symbol, gdeltSearches.getValue(symbol), now)
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
    }

    private fun pollGdelt(symbol: String, keyword: String, now: Instant) {
        lastGdeltRequest = now
        nextGdelt[symbol] = now.plus(properties.gdelt.pollInterval)
        health.attempt(EventSource.GDELT)
        try {
            val result = gdelt.fetch(symbol, keyword, now)
            val discovered = save(result)
            gdeltBackoff = Duration.ofMinutes(1)
            health.success(EventSource.GDELT, discovered)
            log.info("GDELT query completed: symbol={}, candidates={}", symbol, result.records.size)
        } catch (ex: RateLimitedException) {
            val wait = parseRetryAfter(ex.retryAfter, now) ?: gdeltBackoff
            gdeltBlockedUntil = clock.instant().plus(wait)
            gdeltBackoff = gdeltBackoff.multipliedBy(2).coerceAtMost(Duration.ofMinutes(15))
            health.failure(EventSource.GDELT, "RATE_LIMITED")
            log.warn("GDELT rate limited; waiting {} seconds", wait.seconds)
        } catch (ex: Exception) {
            health.failure(EventSource.GDELT, "ERROR")
            log.warn("GDELT query failed for {}: {}", symbol, ex.javaClass.simpleName)
        }
    }

    private fun pollDart(symbol: String, corpCode: String, now: Instant) {
        nextDart[symbol] = now.plus(properties.dart.pollInterval)
        health.attempt(EventSource.DART)
        try {
            var discovered = 0
            for (result in dart.fetch(symbol, corpCode, now)) {
                journal.appendRaw(RawFetch(result.source, result.query, result.receivedAt, result.status, result.body))
                for (candidate in result.records) {
                    if (candidate.id !in seen) {
                        val receipt = candidate.id.removePrefix("DART:")
                        val content = journal.saveDisclosure(receipt, dart.downloadDisclosure(receipt))
                        val record = candidate.copy(contentPath = content.toString())
                        journal.appendEvent(record)
                        seen += record.id
                        discovered++
                    }
                    saveLink(candidate.id, symbol, now)
                }
            }
            health.success(EventSource.DART, discovered)
            log.info("OpenDART query completed: symbol={}", symbol)
        } catch (ex: RateLimitedException) {
            nextDart[symbol] = clock.instant().plus(Duration.ofMinutes(15))
            health.failure(EventSource.DART, "RATE_LIMITED")
            log.warn("OpenDART rate limited: symbol={}", symbol)
        } catch (ex: Exception) {
            health.failure(EventSource.DART, "ERROR")
            // Never log the HTTP request URI: it contains the OpenDART API key.
            log.warn("OpenDART query failed: symbol={}, error={}", symbol, ex.javaClass.simpleName)
        }
    }

    private fun save(result: FetchResult): Int {
        journal.appendRaw(RawFetch(result.source, result.query, result.receivedAt, result.status, result.body))
        var discovered = 0
        for (record in result.records) {
            if (seen.add(record.id)) {
                try { journal.appendEvent(record) } catch (ex: Exception) {
                    seen.remove(record.id)
                    throw ex
                }
                discovered++
            }
            saveLink(record.id, result.query, result.receivedAt)
        }
        return discovered
    }

    private fun saveLink(eventId: String, symbol: String, now: Instant) {
        val link = EventLink(eventId, symbol, now)
        if (seenLinks.add(link.id)) {
            try { journal.appendLink(link) } catch (ex: Exception) {
                seenLinks.remove(link.id)
                throw ex
            }
        }
    }

    private fun parseRetryAfter(value: String?, now: Instant): Duration? {
        if (value == null) return null
        value.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(1)) }
        return runCatching { Duration.between(now, Instant.from(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(value))) }
            .getOrNull()?.takeUnless { it.isNegative || it.isZero }
    }

    @PreDestroy
    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(3000)
        health.stopped()
    }
}
