package dev.autohelm.market

import com.fasterxml.jackson.databind.ObjectMapper
import dev.autohelm.control.contract.WatchlistReader
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ManagedMarketSymbols(properties: MarketProperties, mapper: ObjectMapper) {
    private val base = properties.symbols.associateWith { Venue.entries.toSet() }
    private val reader = WatchlistReader(
        properties.dataDir.resolve(properties.environment).resolve("control/watchlist.json"),
        properties.environment, mapper,
    )

    fun current(): Map<String, Set<Venue>> {
        val result = base.toMutableMap()
        for ((symbol, entry) in reader.current().entries) {
            if (entry.enabled) result[symbol] = entry.venues.map(Venue::valueOf).toSet()
            else result.remove(symbol)
        }
        return result
    }

    fun subscriptions(): Set<String> = current().flatMap { (symbol, venues) ->
        venues.map { venue -> "${if (venue == Venue.KRX) "H0STCNT0" else "H0NXCNT0"}:$symbol" }
    }.toSet()

    fun activeFrom(symbol: String, venue: Venue): Instant? =
        reader.current().entries[symbol]?.takeIf { it.enabled }?.activeFrom?.get(venue.name)
}
