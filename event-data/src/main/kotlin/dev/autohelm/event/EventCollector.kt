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
    private val rss = InfomaxRssAdapter(properties)
    private val dart = OpenDartAdapter(properties, mapper)
    private val seen = mutableSetOf<String>()
    private val knownRecords = journal.existingRecords().toMutableMap()
    private val seenLinks = mutableSetOf<String>()
    private val seenObservations = mutableSetOf<String>()
    private val legacyNewsIds = journal.legacyNewsIdsByUrl()
    private val nextGdelt = mutableMapOf<String, Instant>()
    private val nextDart = mutableMapOf<String, Instant>()
    private val nextRss = mutableMapOf<String, Instant>()
    private val previousRssIds = mutableMapOf<String, Set<String>>()
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
        seenObservations += journal.existingObservationIds()
        running = true
        health.started(journal.existingSourceCounts())
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
            nextRss.keys.retainAll(properties.rss.feeds.keys)
            gdeltSearches.keys.forEach { nextGdelt.putIfAbsent(it, Instant.EPOCH) }
            dartCorpCodes.keys.forEach { nextDart.putIfAbsent(it, Instant.EPOCH) }
            properties.rss.feeds.keys.forEach { nextRss.putIfAbsent(it, Instant.EPOCH) }
            if (properties.rss.enabled) {
                val feed = nextRss.entries.firstOrNull { !now.isBefore(it.value) }?.key
                if (feed != null) pollRss(feed, now)
            }
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

    private fun pollRss(feed: String, now: Instant) {
        nextRss[feed] = now.plus(properties.rss.pollInterval)
        health.attempt(EventSource.EINFOMAX)
        try {
            val result = rss.fetch(feed, properties.rss.feeds.getValue(feed))
            val currentIds = result.items.map { it.record.id }.toSet()
            val prior = previousRssIds.put(feed, currentIds)
            val overlap = prior?.intersect(currentIds)?.size
            var discovered = 0
            val companyNames = managedSymbols.companyNames()
            for (item in result.items) {
                val record = item.record.let { candidate ->
                    legacyNewsIds[candidate.url]?.let { candidate.copy(id = it) } ?: candidate
                }
                if (seen.add(record.id)) {
                    try { journal.appendEvent(record) } catch (ex: Exception) {
                        seen.remove(record.id)
                        throw ex
                    }
                    knownRecords[record.id] = record
                    discovered++
                } else if (knownRecords[record.id]?.publishedAt == null && record.publishedAt != null) {
                    val updated = knownRecords.getValue(record.id).copy(publishedAt = record.publishedAt)
                    journal.appendEventUpdate(updated)
                    knownRecords[record.id] = updated
                }
                saveObservation(record, result.receivedAt, feed)
                for ((symbol, name) in companyNames) {
                    if (mentionsCompany(record.title, name)) {
                        if (properties.jev.enabled && item.description != null) journal.saveWorking(record.id, item.description)
                        saveLink(record.id, symbol, result.receivedAt)
                    }
                }
            }
            health.success(EventSource.EINFOMAX, discovered)
            val possibleGap = prior != null && (overlap == 0 || discovered >= result.items.size && result.items.isNotEmpty())
            health.rssFeed(feed, result.items.size, overlap, discovered, possibleGap)
            if (possibleGap) {
                log.warn("RSS possible feed gap: feed={}, items={}, overlap={}, new={}", feed, result.items.size, overlap, discovered)
            } else {
                log.info("RSS query completed: feed={}, items={}, overlap={}, new={}", feed, result.items.size, overlap, discovered)
            }
        } catch (ex: RateLimitedException) {
            val wait = parseRetryAfter(ex.retryAfter, now) ?: Duration.ofMinutes(15)
            nextRss[feed] = clock.instant().plus(wait)
            health.failure(EventSource.EINFOMAX, "RATE_LIMITED")
            log.warn("RSS rate limited: feed={}, waitSeconds={}", feed, wait.seconds)
        } catch (ex: Exception) {
            health.failure(EventSource.EINFOMAX, "ERROR")
            log.warn("RSS query failed: feed={}, error={}", feed, ex.javaClass.simpleName)
        }
    }

    private fun mentionsCompany(title: String, name: String): Boolean {
        if (name.length < 2) return false
        val index = title.indexOf(name, ignoreCase = true)
        if (index < 0) return false
        if (name.all { it.isLetterOrDigit() && it.code < 128 }) {
            val before = title.getOrNull(index - 1)
            val after = title.getOrNull(index + name.length)
            return (before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())
        }
        return true
    }

    private fun pollGdelt(symbol: String, keyword: String, now: Instant) {
        lastGdeltRequest = now
        nextGdelt[symbol] = now.plus(properties.gdelt.pollInterval)
        health.attempt(EventSource.GDELT)
        try {
            val result = gdelt.fetch(symbol, keyword)
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
                        knownRecords[record.id] = record
                        discovered++
                    }
                    saveLink(candidate.id, symbol, candidate.firstSeenAt)
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
        for (candidate in result.records) {
            val record = legacyNewsIds[candidate.url]?.let { candidate.copy(id = it) } ?: candidate
            if (seen.add(record.id)) {
                try { journal.appendEvent(record) } catch (ex: Exception) {
                    seen.remove(record.id)
                    throw ex
                }
                knownRecords[record.id] = record
                discovered++
            }
            saveObservation(record, result.receivedAt, result.query)
            saveLink(record.id, result.query, result.receivedAt)
        }
        return discovered
    }

    private fun saveObservation(record: EventRecord, now: Instant, query: String) {
        val providerId = record.providerId ?: return
        val observation = EventObservation(record.id, record.source, providerId, now, query)
        if (seenObservations.add(observation.id)) {
            try { journal.appendObservation(observation) } catch (ex: Exception) {
                seenObservations.remove(observation.id)
                throw ex
            }
        }
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
