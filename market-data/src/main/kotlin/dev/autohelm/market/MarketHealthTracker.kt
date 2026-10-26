package dev.autohelm.market

import java.time.Instant

/** Current status is in memory; durable outages are written as MarketGap events. */
class MarketHealthTracker(
    private val properties: MarketProperties,
    private val subscriptions: () -> Map<String, Set<Venue>> = {
        properties.symbols.associateWith { Venue.entries.toSet() }
    },
) {
    private val states = mutableMapOf<Pair<String, Venue>, MarketHealth>()

    @Synchronized
    fun subscribing(at: Instant) = updateAll(at) { it.copy(
        subscriptionState = SubscriptionState.SUBSCRIBING, reason = null, updatedAt = at,
    ) }

    @Synchronized
    fun confirmed(symbol: String, venue: Venue, at: Instant) {
        val key = symbol to venue
        val current = states[key] ?: initial(symbol, venue, at)
        states[key] = current.copy(subscriptionState = SubscriptionState.SUBSCRIBED, reason = null,
            updatedAt = at, subscriptionConfirmedAt = at)
    }

    @Synchronized
    fun received(tick: MarketTick) {
        val key = tick.symbol to tick.venue
        val current = states[key] ?: initial(tick.symbol, tick.venue, tick.receivedAt)
        states[key] = current.copy(lastReceivedAt = tick.receivedAt, updatedAt = tick.receivedAt)
    }

    @Synchronized
    fun disconnected(at: Instant, reason: String) = updateAll(at) { it.copy(
        subscriptionState = SubscriptionState.DISCONNECTED,
        gapStartedAt = it.gapStartedAt ?: at, reason = reason, updatedAt = at,
    ) }

    @Synchronized
    fun restored(at: Instant) = updateAll(at) { it.copy(gapStartedAt = null, updatedAt = at) }

    @Synchronized
    fun snapshot(now: Instant): List<MarketHealth> = subscriptions().flatMap { (symbol, venues) ->
        venues.map { venue -> states[symbol to venue] ?: initial(symbol, venue, now) }
    }

    private fun updateAll(at: Instant, transform: (MarketHealth) -> MarketHealth) {
        for ((symbol, venues) in subscriptions()) for (venue in venues) {
            val key = symbol to venue
            states[key] = transform(states[key] ?: initial(symbol, venue, at))
        }
    }

    private fun initial(symbol: String, venue: Venue, at: Instant) = MarketHealth(
        properties.environment, symbol, venue, SubscriptionState.DISCONNECTED,
        null, null, "NOT_CONNECTED", at,
    )
}
