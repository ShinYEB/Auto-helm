package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import dev.autohelm.control.contract.WatchlistReader
import org.springframework.stereotype.Component

@Component
class ManagedEventSymbols(properties: EventProperties, mapper: ObjectMapper) {
    private val baseGdelt = properties.gdelt.searches
    private val baseDart = properties.dart.corpCodes
    private val reader = WatchlistReader(
        properties.dataDir.resolve(properties.environment).resolve("control/watchlist.json"),
        properties.environment, mapper,
    )

    fun gdeltSearches(): Map<String, String> {
        val result = baseGdelt.toMutableMap()
        for ((symbol, entry) in reader.current().entries) {
            result.remove(symbol)
            if (entry.enabled) entry.gdeltQuery?.let { result[symbol] = it }
        }
        return result
    }

    fun dartCorpCodes(): Map<String, String> {
        val result = baseDart.toMutableMap()
        for ((symbol, entry) in reader.current().entries) {
            result.remove(symbol)
            if (entry.enabled) entry.dartCorpCode?.let { result[symbol] = it }
        }
        return result
    }
}
