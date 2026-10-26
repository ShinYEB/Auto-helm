package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.bridge.JsonlTailer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Separate from collection, price inference and Jev. Web search is always budgeted. */
@Component
class EventSearchWorker(private val properties: EventProperties, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = properties.dataDir.resolve(properties.environment).resolve("events")
    private val journal = EventJournal(properties, mapper)
    private val client = OpenAiEventSearchClient(properties, mapper)
    private val events = JsonlTailer(root.resolve("events"), root.resolve("bridge/search-events.cursor"))
    private val analyses = JsonlTailer(root.resolve("analysis"),
        root.resolve("bridge/search-analysis-${NewsIdentity.hash(properties.search.policyVersion).take(12)}.cursor"))
    private val records = mutableMapOf<String, EventRecord>()
    private val latest = mutableMapOf<String, IssueSearchResult>()
    private val seoul = ZoneId.of("Asia/Seoul")
    private var callDay = LocalDate.now(seoul)
    private var callsToday = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.search.enabled || running) return
        records.putAll(journal.existingRecords())
        val history = journal.existingSearchResults()
        history.forEach { latest[it.searchId] = it }
        callsToday = history.count { it.status == SearchStatus.SEARCH_PENDING && it.completedAt.atZone(seoul).toLocalDate() == callDay }
        running = true
        thread = Thread(::loop, "event-web-search-worker").also { it.start() }
    }

    internal fun processAvailable(): Int {
        if (records.isEmpty()) records.putAll(journal.existingRecords())
        events.drain { json ->
            val event = mapper.readValue<EventRecord>(json)
            require(event.environment == properties.environment)
            records[event.id] = event
        }
        return analyses.drain { json ->
            val analysis = mapper.readValue<IssueAnalysis>(json)
            if (analysis.environment != properties.environment ||
                analysis.status != IssueStatus.SCORED ||
                analysis.signedScore == null ||
                kotlin.math.abs(analysis.signedScore) < properties.search.threshold) return@drain
            val id = "${properties.environment}:${analysis.eventId}:${analysis.symbol}:${properties.search.policyVersion}"
            val previous = latest[id]
            if (previous != null && previous.status in setOf(SearchStatus.VERIFIED, SearchStatus.NOT_AVAILABLE)) return@drain
            if (previous?.status == SearchStatus.SEARCH_FAILED && previous.attempt >= properties.search.maxAttempts) return@drain
            val record = records[analysis.eventId] ?: error("Event missing for search: ${analysis.eventId}")
            if (record.firstSeenAt.isBefore(Instant.now().minus(properties.search.maxEventAge))) {
                val expired = IssueSearchResult(id, properties.environment, record.id, analysis.symbol,
                    properties.search.policyVersion, SearchStatus.NOT_AVAILABLE, analysis.signedScore,
                    null, emptyList(), null, properties.search.model, Instant.now(), error = "EVENT_TOO_OLD")
                journal.appendSearchResult(expired)
                latest[id] = expired
                return@drain
            }
            val today = LocalDate.now(seoul)
            if (today != callDay) { callDay = today; callsToday = 0 }
            if (callsToday >= properties.search.maxCallsPerDay) throw IllegalStateException("Daily search budget reached")
            val attempt = if (previous?.status == SearchStatus.SEARCH_PENDING) previous.attempt else (previous?.attempt ?: 0) + 1
            if (previous?.status != SearchStatus.SEARCH_PENDING) {
                val pending = IssueSearchResult(id, properties.environment, record.id, analysis.symbol,
                    properties.search.policyVersion, SearchStatus.SEARCH_PENDING, analysis.signedScore,
                    null, emptyList(), null, properties.search.model, Instant.now(), attempt = attempt)
                journal.appendSearchResult(pending)
                latest[id] = pending
                callsToday++
            }
            try {
                val result = client.verify(record, analysis).copy(attempt = attempt)
                journal.appendSearchResult(result)
                latest[id] = result
            } catch (ex: Exception) {
                val failed = IssueSearchResult(id, properties.environment, record.id, analysis.symbol,
                    properties.search.policyVersion, SearchStatus.SEARCH_FAILED, analysis.signedScore,
                    null, emptyList(), null, properties.search.model, Instant.now(),
                    error = ex.javaClass.simpleName, attempt = attempt)
                journal.appendSearchResult(failed)
                latest[id] = failed
                log.warn("Event search failed: event={}, symbol={}, error={}", record.id, analysis.symbol, ex.javaClass.simpleName)
                if (attempt < properties.search.maxAttempts) throw ex
            }
        }
    }

    private fun loop() {
        while (running) {
            try {
                processAvailable()
                Thread.sleep(properties.search.pollInterval.toMillis())
            } catch (_: InterruptedException) {
                break
            } catch (ex: Exception) {
                log.warn("Event search journal delayed: {}", ex.javaClass.simpleName)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
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
