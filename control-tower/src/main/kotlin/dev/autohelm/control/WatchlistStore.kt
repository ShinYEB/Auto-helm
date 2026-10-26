package dev.autohelm.control

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.control.contract.WatchlistEntry
import dev.autohelm.control.contract.WatchlistSnapshot
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

data class SymbolInput(
    val revision: Long,
    val name: String,
    val venues: Set<String>,
    val gdeltQuery: String? = null,
    val dartCorpCode: String? = null,
)

class RevisionConflict : IllegalStateException("Watchlist changed; reload and try again")

@Component
class WatchlistStore(private val properties: ControlProperties, private val mapper: ObjectMapper) {
    private val root: Path get() = properties.dataDir.resolve(properties.environment).resolve("control")
    private val path: Path get() = root.resolve("watchlist.json")

    @Synchronized
    fun current(): WatchlistSnapshot = if (Files.exists(path))
        mapper.readValue<WatchlistSnapshot>(Files.readAllBytes(path)).also {
            require(it.environment == properties.environment)
        } else WatchlistSnapshot(environment = properties.environment, revision = 0,
            updatedAt = Instant.EPOCH, entries = emptyMap())

    @Synchronized
    fun put(symbol: String, input: SymbolInput): WatchlistSnapshot {
        require(properties.environment == "dev") { "Watchlist edits are available only in dev" }
        require(symbol.matches(Regex("[0-9A-Z]{6}")))
        val before = current()
        if (before.revision != input.revision) throw RevisionConflict()
        val now = Instant.now()
        val previous = before.entries[symbol]
        val activeFrom = input.venues.associateWith { venue ->
            previous?.takeIf { it.enabled && venue in it.venues }?.activeFrom?.get(venue) ?: now
        }
        val entry = WatchlistEntry(symbol, input.name.trim(), input.venues,
            input.gdeltQuery?.trim()?.ifEmpty { null }, input.dartCorpCode?.trim()?.ifEmpty { null },
            enabled = true, updatedAt = now, activeFrom = activeFrom)
        val updated = before.copy(revision = before.revision + 1, updatedAt = entry.updatedAt,
            entries = before.entries + (symbol to entry))
        write(updated, if (symbol in before.entries) "UPDATE" else "ADD", symbol)
        return updated
    }

    @Synchronized
    fun disable(symbol: String, revision: Long): WatchlistSnapshot {
        require(properties.environment == "dev") { "Watchlist edits are available only in dev" }
        require(symbol.matches(Regex("[0-9A-Z]{6}")))
        val before = current()
        if (before.revision != revision) throw RevisionConflict()
        val previous = requireNotNull(before.entries[symbol]) { "Symbol is not managed" }
        if (!previous.enabled) return before
        val now = Instant.now()
        val updated = before.copy(revision = before.revision + 1, updatedAt = now,
            entries = before.entries + (symbol to previous.copy(enabled = false, updatedAt = now)))
        write(updated, "DISABLE", symbol)
        return updated
    }

    private fun write(snapshot: WatchlistSnapshot, action: String, symbol: String) {
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "watchlist-", ".tmp")
        try {
            Files.write(temporary, mapper.writeValueAsBytes(snapshot))
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
        val bytes = (mapper.writeValueAsString(mapOf("at" to snapshot.updatedAt, "revision" to snapshot.revision,
            "action" to action, "symbol" to symbol)) + "\n").toByteArray(Charsets.UTF_8)
        FileChannel.open(root.resolve("watchlist-audit.jsonl"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
}
