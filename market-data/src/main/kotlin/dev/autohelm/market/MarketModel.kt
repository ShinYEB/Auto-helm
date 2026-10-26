package dev.autohelm.market

import java.time.Instant

enum class Venue { KRX, NXT }
enum class BarQuality { READY, INCOMPLETE, EMPTY_UNVERIFIED }
enum class SubscriptionState { DISCONNECTED, SUBSCRIBING, SUBSCRIBED }

data class MarketHealth(
    val environment: String,
    val symbol: String,
    val venue: Venue,
    val subscriptionState: SubscriptionState,
    val lastReceivedAt: Instant?,
    val gapStartedAt: Instant?,
    val reason: String?,
    val updatedAt: Instant,
)

data class RawFrame(
    val id: String,
    val receivedAt: Instant,
    val text: String,
)

data class MarketTick(
    val id: String,
    val environment: String,
    val symbol: String,
    val venue: Venue,
    val session: String,
    val sourceAt: Instant,
    val receivedAt: Instant,
    val price: Long,
    val volume: Long,
    val cumulativeVolume: Long,
    val rawFrameId: String,
)

data class MinuteBar(
    val id: String,
    val environment: String,
    val symbol: String,
    val venue: Venue,
    val session: String,
    val minuteStart: Instant,
    val minuteEnd: Instant,
    val open: Long?,
    val high: Long?,
    val low: Long?,
    val close: Long?,
    val volume: Long,
    val tradeCount: Int,
    val quality: BarQuality,
    val sealedAt: Instant,
    val firstTickId: String?,
    val lastTickId: String?,
    val priceP25: Long? = null,
    val priceP50: Long? = null,
    val priceP75: Long? = null,
)

data class MarketGap(
    val environment: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val reason: String,
)

data class BarKey(
    val symbol: String,
    val venue: Venue,
    val session: String,
    val minuteStart: Instant,
) {
    fun id(environment: String): String = "$environment:$symbol:$venue:$session:$minuteStart"
}
