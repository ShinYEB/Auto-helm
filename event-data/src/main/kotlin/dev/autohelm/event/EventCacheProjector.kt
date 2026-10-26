package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.bridge.JsonlTailer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** Rebuildable read model. Raw responses and disclosure ZIPs never enter Redis. */
@Component
class EventCacheProjector(
    private val properties: EventProperties,
    private val mapper: ObjectMapper,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = properties.dataDir.resolve(properties.environment).resolve("events")
    private val events = JsonlTailer(root.resolve("events"), root.resolve("bridge/events.cursor"))
    private val links = JsonlTailer(root.resolve("links"), root.resolve("bridge/links.cursor"))
    private val analyses = JsonlTailer(root.resolve("analysis"), root.resolve("bridge/analysis.cursor"))
    private val searches = JsonlTailer(root.resolve("search"), root.resolve("bridge/search.cursor"))
    private val prefix = "autohelm:${properties.environment}"
    private val readyKey = "$prefix:event-cache:v1:ready"
    private val heartbeatKey = "$prefix:event-cache:v1:heartbeat"
    @Volatile private var running = false
    private var rebuilding = false
    private var worker: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.redis.enabled || running) return
        running = true
        worker = Thread(::loop, "event-cache-projector").also { it.start() }
    }

    internal fun projectAvailable(): Int {
        if (redis.opsForValue().get(readyKey) != "ready" && !rebuilding) {
            // Redis may have restarted. Full replay is idempotent and reconstructs both indexes.
            events.reset()
            links.reset()
            analyses.reset()
            searches.reset()
            rebuilding = true
        }
        val eventCount = events.drain { json ->
            val event = mapper.readValue<EventRecord>(json)
            require(event.environment == properties.environment) { "Wrong environment in event journal" }
            if (!event.firstSeenAt.isBefore(Instant.now().minus(Duration.ofDays(30)))) {
                redis.opsForValue().set("$prefix:event:v1:${event.id}", json, Duration.ofDays(30))
            }
        }
        val linkCount = links.drain { json ->
            val link = mapper.readValue<EventLink>(json)
            require(link.symbol.matches(Regex("[0-9A-Z]{6}")))
            val key = "$prefix:symbol:v1:${link.symbol}:recent"
            val eventKey = "$prefix:event:v1:${link.eventId}"
            if (!link.firstSeenAt.isBefore(Instant.now().minus(Duration.ofDays(30))) &&
                redis.hasKey(eventKey)) {
                redis.opsForZSet().add(key, link.eventId, link.firstSeenAt.toEpochMilli().toDouble())
                redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY,
                    Instant.now().minus(Duration.ofDays(30)).toEpochMilli().toDouble())
                redis.expire(key, Duration.ofDays(31))
                redis.expire(eventKey, Duration.ofDays(30))
            }
        }
        val analysisCount = analyses.drain { json ->
            val result = mapper.readValue<IssueAnalysis>(json)
            require(result.environment == properties.environment) { "Wrong environment in analysis journal" }
            require(result.symbol.matches(Regex("[0-9A-Z]{6}")))
            if (!result.completedAt.isBefore(Instant.now().minus(Duration.ofDays(30)))) {
                val key = "$prefix:symbol:v1:${result.symbol}:issue-score"
                redis.opsForHash<String, String>().put(key, result.eventId, json)
                redis.expire(key, Duration.ofDays(31))
            }
        }
        val searchCount = searches.drain { json ->
            val result = mapper.readValue<IssueSearchResult>(json)
            require(result.environment == properties.environment) { "Wrong environment in search journal" }
            require(result.symbol.matches(Regex("[0-9A-Z]{6}")))
            if (!result.completedAt.isBefore(Instant.now().minus(Duration.ofDays(30)))) {
                val key = "$prefix:symbol:v1:${result.symbol}:issue-search"
                redis.opsForHash<String, String>().put(key, result.eventId, json)
                redis.expire(key, Duration.ofDays(31))
            }
        }
        redis.opsForValue().set(readyKey, "ready")
        redis.opsForValue().set(heartbeatKey, Instant.now().toString(), Duration.ofSeconds(30))
        rebuilding = false
        return eventCount + linkCount + analysisCount + searchCount
    }

    private fun loop() {
        while (running) {
            try {
                projectAvailable()
                Thread.sleep(properties.redis.pollInterval.toMillis())
            } catch (_: InterruptedException) {
                break
            } catch (ex: Exception) {
                rebuilding = false
                log.warn("Event Redis projection delayed: {}", ex.javaClass.simpleName)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    @PreDestroy
    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(3000)
    }
}
