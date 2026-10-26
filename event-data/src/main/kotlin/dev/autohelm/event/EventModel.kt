package dev.autohelm.event

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

enum class EventSource { GDELT, DART }

data class EventRecord(
    val id: String,
    val environment: String,
    val source: EventSource,
    val title: String,
    val url: String,
    val symbol: String?,
    val publishedAt: Instant?,
    val providerSeenAt: Instant?,
    val firstSeenAt: Instant,
    val inputScope: String,
    val contentPath: String? = null,
)

data class EventLink(
    val eventId: String,
    val symbol: String,
    val firstSeenAt: Instant,
) {
    @get:JsonIgnore
    val id: String get() = "$eventId:$symbol"
}

data class RawFetch(
    val source: EventSource,
    val query: String,
    val receivedAt: Instant,
    val status: Int,
    val body: String,
)

data class FetchResult(
    val source: EventSource,
    val query: String,
    val receivedAt: Instant,
    val status: Int,
    val body: String,
    val records: List<EventRecord>,
    val retryAfter: String? = null,
)

class RateLimitedException(val retryAfter: String? = null) : RuntimeException("Provider rate limit")
