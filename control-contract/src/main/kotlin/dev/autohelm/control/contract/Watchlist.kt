package dev.autohelm.control.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

data class WatchlistEntry(
    val symbol: String,
    val name: String,
    val venues: Set<String>,
    val gdeltQuery: String? = null,
    val dartCorpCode: String? = null,
    val enabled: Boolean = true,
    val updatedAt: Instant,
    val activeFrom: Map<String, Instant> = emptyMap(),
) {
    init {
        require(symbol.matches(Regex("[0-9A-Z]{6}")))
        require(name.isNotBlank() && name.length <= 80)
        require(venues.isNotEmpty() && venues.all { it in setOf("KRX", "NXT") })
        require(gdeltQuery == null || gdeltQuery.isNotBlank() && gdeltQuery.length <= 200)
        require(dartCorpCode == null || dartCorpCode.matches(Regex("[0-9]{8}")))
        require(activeFrom.keys.all { it in venues })
    }
}

data class WatchlistSnapshot(
    val schema: String = "Watchlist.v1",
    val environment: String,
    val revision: Long,
    val updatedAt: Instant,
    val entries: Map<String, WatchlistEntry>,
) {
    init {
        require(schema == "Watchlist.v1" && environment in setOf("dev", "prd") && revision >= 0)
        require(entries.all { (symbol, entry) -> symbol == entry.symbol })
    }
}

/** A small, atomic desired-state file shared by the tower and collectors. */
class WatchlistReader(
    private val path: Path,
    private val environment: String,
    private val mapper: ObjectMapper,
    private val refreshInterval: Duration = Duration.ofSeconds(2),
) {
    @Volatile private var cached = WatchlistSnapshot(environment = environment, revision = 0,
        updatedAt = Instant.EPOCH, entries = emptyMap())
    private var nextReadAt = 0L

    @Synchronized
    fun current(): WatchlistSnapshot {
        val now = System.nanoTime()
        if (now < nextReadAt) return cached
        nextReadAt = now + refreshInterval.toNanos()
        if (!Files.exists(path)) {
            cached = cached.copy(revision = 0, entries = emptyMap())
            return cached
        }
        runCatching { mapper.readValue<WatchlistSnapshot>(Files.readAllBytes(path)) }
            .getOrNull()?.takeIf { it.environment == environment }?.let { cached = it }
        return cached
    }
}
