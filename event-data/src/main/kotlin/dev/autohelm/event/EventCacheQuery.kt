package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

data class EventLookup(val available: Boolean, val events: List<EventRecord>)
data class IssueLookup(val available: Boolean, val analyses: List<IssueAnalysis>, val searches: List<IssueSearchResult>)

/** A missing or stale cache is unavailable, never evidence that no event occurred. */
@Component
class EventCacheQuery(
    private val properties: EventProperties,
    private val mapper: ObjectMapper,
    private val redis: StringRedisTemplate,
) {
    fun issueScores(symbol: String, asOf: Instant): IssueLookup {
        require(symbol.matches(Regex("[0-9A-Z]{6}")))
        if (!properties.redis.enabled) return IssueLookup(false, emptyList(), emptyList())
        val prefix = "autohelm:${properties.environment}"
        return try {
            if (redis.opsForValue().get("$prefix:event-cache:v1:ready") != "ready") return IssueLookup(false, emptyList(), emptyList())
            val heartbeat = redis.opsForValue().get("$prefix:event-cache:v1:heartbeat")
                ?.let(Instant::parse) ?: return IssueLookup(false, emptyList(), emptyList())
            if (Duration.between(heartbeat, Instant.now()) > Duration.ofSeconds(15)) return IssueLookup(false, emptyList(), emptyList())
            val values = redis.opsForHash<String, String>().values("$prefix:symbol:v1:$symbol:issue-score")
            val analyses = values.map { mapper.readValue<IssueAnalysis>(it) }
                .filter { !it.completedAt.isAfter(asOf) && it.analysisVersion == properties.jev.analysisVersion }
            val searches = redis.opsForHash<String, String>().values("$prefix:symbol:v1:$symbol:issue-search")
                .map { mapper.readValue<IssueSearchResult>(it) }
                .filter { !it.completedAt.isAfter(asOf) && it.policyVersion == properties.search.policyVersion }
            IssueLookup(true, analyses, searches)
        } catch (_: Exception) {
            IssueLookup(false, emptyList(), emptyList())
        }
    }

    fun recent(symbol: String, asOf: Instant, limit: Long = 50): EventLookup {
        require(symbol.matches(Regex("[0-9A-Z]{6}")) && limit in 1..500)
        if (!properties.redis.enabled) return EventLookup(false, emptyList())
        val prefix = "autohelm:${properties.environment}"
        return try {
            if (redis.opsForValue().get("$prefix:event-cache:v1:ready") != "ready") return EventLookup(false, emptyList())
            val heartbeat = redis.opsForValue().get("$prefix:event-cache:v1:heartbeat")
                ?.let(Instant::parse) ?: return EventLookup(false, emptyList())
            if (Duration.between(heartbeat, Instant.now()) > Duration.ofSeconds(15)) return EventLookup(false, emptyList())
            val ids = redis.opsForZSet().reverseRangeByScore(
                "$prefix:symbol:v1:$symbol:recent", Double.NEGATIVE_INFINITY,
                asOf.toEpochMilli().toDouble(), 0, limit
            ) ?: emptySet()
            val records = ids.map { id ->
                val json = redis.opsForValue().get("$prefix:event:v1:$id") ?: return EventLookup(false, emptyList())
                mapper.readValue<EventRecord>(json)
            }.filter { !it.firstSeenAt.isAfter(asOf) }
            EventLookup(true, records)
        } catch (_: Exception) {
            EventLookup(false, emptyList())
        }
    }
}
