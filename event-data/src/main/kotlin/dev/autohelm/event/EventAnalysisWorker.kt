package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.bridge.JsonlTailer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Independent journal consumer. A failed sink leaves the link cursor in place for replay. */
@Component
class EventAnalysisWorker(private val properties: EventProperties, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = properties.dataDir.resolve(properties.environment).resolve("events")
    private val journal = EventJournal(properties, mapper)
    private val client = JevIssueClient(properties, mapper)
    private val events = JsonlTailer(root.resolve("events"), root.resolve("bridge/analysis-events.cursor"))
    private val links = JsonlTailer(root.resolve("links"),
        root.resolve("bridge/analysis-links-${NewsIdentity.hash(properties.jev.analysisVersion).take(12)}.cursor"))
    private val records = mutableMapOf<String, EventRecord>()
    private val latest = mutableMapOf<String, IssueAnalysis>()
    private val seoul = ZoneId.of("Asia/Seoul")
    private var callDay: LocalDate = LocalDate.now(seoul)
    private var callsToday: Int = 0
    @Volatile private var running = false
    private var thread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.jev.enabled || running) return
        records.putAll(journal.existingRecords())
        val previousAnalyses = journal.existingAnalyses()
        previousAnalyses.forEach { latest[it.analysisId] = it }
        callsToday = previousAnalyses.count {
            it.completedAt.atZone(seoul).toLocalDate() == callDay && it.model != null
        }
        running = true
        thread = Thread(::loop, "event-jev-worker").also { it.start() }
    }

    internal fun processAvailable(): Int {
        if (records.isEmpty()) records.putAll(journal.existingRecords())
        events.drain { json ->
            val event = mapper.readValue<EventRecord>(json)
            require(event.environment == properties.environment)
            records[event.id] = event
        }
        return links.drain { json ->
            val link = mapper.readValue<EventLink>(json)
            val record = records[link.eventId] ?: error("Event missing for link: ${link.eventId}")
            val id = "${properties.environment}:${link.eventId}:${link.symbol}:${properties.jev.analysisVersion}"
            val previous = latest[id]
            if (previous != null && (previous.status != IssueStatus.ANALYSIS_FAILED || previous.attempt >= properties.jev.maxAttempts)) {
                return@drain
            }
            val attempt = (previous?.attempt ?: 0) + 1
            if (record.firstSeenAt.isBefore(Instant.now().minus(properties.jev.maxEventAge))) {
                val unavailable = IssueAnalysis(
                    analysisId = id, environment = properties.environment, eventId = link.eventId,
                    symbol = link.symbol, analysisVersion = properties.jev.analysisVersion,
                    status = IssueStatus.NOT_AVAILABLE, relevance = null, impact = null,
                    positiveProbability = null, evidenceSufficient = null, signedScore = null,
                    model = null, inputScope = record.inputScope, basis = "Event outside analysis age limit",
                    completedAt = Instant.now(), attempt = attempt,
                )
                journal.appendAnalysis(unavailable)
                latest[id] = unavailable
                return@drain
            }
            val today = LocalDate.now(seoul)
            if (today != callDay) { callDay = today; callsToday = 0 }
            if (callsToday >= properties.jev.maxCallsPerDay) throw IllegalStateException("Daily Jev budget reached")
            try {
                val description = journal.readWorking(link.eventId)
                callsToday++
                val result = client.score(record, link.symbol, description, attempt)
                journal.appendAnalysis(result)
                latest[id] = result
            } catch (ex: Exception) {
                val result = IssueAnalysis(
                    analysisId = id, environment = properties.environment, eventId = link.eventId,
                    symbol = link.symbol, analysisVersion = properties.jev.analysisVersion,
                    status = IssueStatus.ANALYSIS_FAILED, relevance = null, impact = null,
                    positiveProbability = null, evidenceSufficient = null, signedScore = null,
                    model = properties.jev.model, inputScope = record.inputScope,
                    basis = "Jev call failed", completedAt = Instant.now(), attempt = attempt,
                    error = ex.javaClass.simpleName,
                )
                journal.appendAnalysis(result)
                latest[id] = result
                log.warn("Jev analysis failed: event={}, symbol={}, attempt={}, error={}", link.eventId, link.symbol, attempt, ex.javaClass.simpleName)
                if (attempt < properties.jev.maxAttempts) throw ex
            }
        }
    }

    private fun loop() {
        while (running) {
            try {
                processAvailable()
                expireWorkingFiles()
                Thread.sleep(properties.jev.pollInterval.toMillis())
            } catch (_: InterruptedException) {
                break
            } catch (ex: Exception) {
                log.warn("Jev journal processing delayed: {}", ex.javaClass.simpleName)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun expireWorkingFiles() {
        val directory = root.resolve("working")
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".txt") }.forEach { file ->
                if (Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(1, ChronoUnit.DAYS))) {
                    Files.deleteIfExists(file)
                }
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
