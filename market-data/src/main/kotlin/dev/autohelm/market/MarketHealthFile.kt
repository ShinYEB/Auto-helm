package dev.autohelm.market

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Replaceable current view. Durable outages stay in the gap and operations journals. */
class MarketHealthFile(private val properties: MarketProperties, private val mapper: ObjectMapper) {
    private val path = properties.dataDir.resolve(properties.environment).resolve("market/health.json")

    fun write(at: Instant, subscriptions: List<MarketHealth>, firstBarAt: Instant?, lastBarAt: Instant?, lastReadyBarAt: Instant?) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "health-", ".tmp")
        try {
            mapper.writeValue(temporary.toFile(), mapOf(
                "pid" to ProcessHandle.current().pid(),
                "heartbeatAt" to at.toString(),
                "firstBarAt" to firstBarAt?.toString(),
                "lastBarAt" to lastBarAt?.toString(),
                "lastReadyBarAt" to lastReadyBarAt?.toString(),
                "subscriptions" to subscriptions.map { row -> mapOf(
                    "symbol" to row.symbol,
                    "venue" to row.venue.name,
                    "subscriptionState" to row.subscriptionState.name,
                    "lastReceivedAt" to row.lastReceivedAt?.toString(),
                    "gapStartedAt" to row.gapStartedAt?.toString(),
                    "reason" to row.reason,
                    "updatedAt" to row.updatedAt.toString(),
                    "subscriptionConfirmedAt" to row.subscriptionConfirmedAt?.toString(),
                ) },
            ))
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
