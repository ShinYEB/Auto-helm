package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** A small atomic status file for the read-only monitoring exporter. */
class EventHealth(private val properties: EventProperties, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val path = properties.dataDir.resolve(properties.environment).resolve("events/health.json")
    private val sources = mutableMapOf(
        EventSource.DART to SourceHealth(properties.dart.enabled),
        EventSource.GDELT to SourceHealth(properties.gdelt.enabled),
    )
    private var running = false
    private var lastWrite = Instant.EPOCH

    fun started(existingIds: Set<String>) {
        sources.getValue(EventSource.DART).discovered = existingIds.count { it.startsWith("DART:") }.toLong()
        sources.getValue(EventSource.GDELT).discovered = existingIds.count { it.startsWith("GDELT:") }.toLong()
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
        write()
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
