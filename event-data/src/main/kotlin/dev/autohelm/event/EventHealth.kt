package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A small atomic status file for the read-only monitoring exporter. */
class EventHealth(private val properties: EventProperties, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val path = properties.dataDir.resolve(properties.environment).resolve("events/health.json")
    private val failureRoot = properties.dataDir.resolve(properties.environment).resolve("events/health-events")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val sources = mutableMapOf(
        EventSource.DART to SourceHealth(properties.dart.enabled),
        EventSource.GDELT to SourceHealth(properties.gdelt.enabled),
        EventSource.EINFOMAX to SourceHealth(properties.rss.enabled),
    )
    private val rssFeeds = mutableMapOf<String, RssFeedHealth>()
    private var running = false
    private var lastWrite = Instant.EPOCH

    fun started(existingSourceCounts: Map<EventSource, Long>) {
        for ((source, state) in sources) state.discovered = existingSourceCounts[source] ?: 0
        running = true
        write()
    }
    fun stopped() { running = false; write() }

    fun heartbeat() {
        if (Instant.now().isAfter(lastWrite.plusSeconds(5))) write()
    }

    fun attempt(source: EventSource) {
        val state = sources.getValue(source)
        state.attempts++
        state.lastAttemptAt = Instant.now()
        write()
    }

    fun success(source: EventSource, discovered: Int) {
        val state = sources.getValue(source)
        state.successes++
        state.discovered += discovered
        state.lastSuccessAt = Instant.now()
        state.lastResult = "OK"
        write()
    }

    fun failure(source: EventSource, kind: String) {
        val state = sources.getValue(source)
        state.failures++
        if (kind == "RATE_LIMITED") state.rateLimits++
        state.lastResult = kind
        appendFailure(source.name, kind, state.failures)
        write()
    }

    fun rssFeed(feed: String, items: Int, overlap: Int?, discovered: Int, possibleGap: Boolean) {
        val state = rssFeeds.getOrPut(feed) { RssFeedHealth() }
        state.lastItemCount = items
        state.lastOverlap = overlap
        state.lastNewCount = discovered
        state.lastSuccessAt = Instant.now()
        if (possibleGap) state.possibleGaps++
        if (possibleGap) appendFailure("EINFOMAX:$feed", "POSSIBLE_FEED_GAP", state.possibleGaps)
        write()
    }

    private fun appendFailure(source: String, kind: String, count: Long) {
        val at = Instant.now()
        val path = failureRoot.resolve("${at.atZone(seoul).toLocalDate()}.jsonl")
        Files.createDirectories(path.parent)
        val row = mapper.writeValueAsString(mapOf(
            "id" to UUID.randomUUID().toString(), "at" to at.toString(),
            "source" to source, "kind" to kind, "count" to count,
        )) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val buffer = ByteBuffer.wrap(row.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun write() {
        val now = Instant.now()
        try {
            Files.createDirectories(path.parent)
            val temporary = Files.createTempFile(path.parent, "health-", ".tmp")
            try {
                mapper.writeValue(temporary.toFile(), mapOf(
                    "pid" to ProcessHandle.current().pid(),
                    "running" to running,
                    "heartbeatAt" to now,
                    "sources" to sources.mapKeys { it.key.name },
                    "rssFeeds" to rssFeeds,
                ))
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
                lastWrite = now
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (ex: Exception) {
            log.warn("Event health file update failed: {}", ex.javaClass.simpleName)
        }
    }
}

private data class RssFeedHealth(
    var lastItemCount: Int = 0,
    var lastOverlap: Int? = null,
    var lastNewCount: Int = 0,
    var lastSuccessAt: Instant? = null,
    var possibleGaps: Long = 0,
)

private data class SourceHealth(
    val enabled: Boolean,
    var attempts: Long = 0,
    var successes: Long = 0,
    var failures: Long = 0,
    var rateLimits: Long = 0,
    var discovered: Long = 0,
    var lastAttemptAt: Instant? = null,
    var lastSuccessAt: Instant? = null,
    var lastResult: String = "NOT_STARTED",
)
