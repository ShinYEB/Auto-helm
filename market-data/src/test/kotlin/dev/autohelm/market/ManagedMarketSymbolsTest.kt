package dev.autohelm.market

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.autohelm.control.contract.WatchlistEntry
import dev.autohelm.control.contract.WatchlistSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ManagedMarketSymbolsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `managed entries override venue selection and disable configured symbol`() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val at = Instant.parse("2026-10-26T01:00:00Z")
        val entry = WatchlistEntry("005930", "Samsung", setOf("KRX"), updatedAt = at,
            activeFrom = mapOf("KRX" to at))
        val path = directory.resolve("dev/control/watchlist.json")
        Files.createDirectories(path.parent)
        Files.write(path, mapper.writeValueAsBytes(WatchlistSnapshot(environment = "dev", revision = 1,
            updatedAt = at, entries = mapOf(entry.symbol to entry))))
        val props = MarketProperties(dataDir = directory, symbols = listOf("005930", "000660"))
        val managed = ManagedMarketSymbols(props, mapper)
        assertThat(managed.current().getValue("005930")).containsExactly(Venue.KRX)
        assertThat(managed.current().getValue("000660")).containsExactlyInAnyOrder(Venue.KRX, Venue.NXT)
        assertThat(managed.activeFrom("005930", Venue.KRX)).isEqualTo(at)
        assertThat(managed.subscriptions()).doesNotContain("H0NXCNT0:005930")

        Files.write(path, mapper.writeValueAsBytes(WatchlistSnapshot(environment = "dev", revision = 2,
            updatedAt = at, entries = mapOf(entry.symbol to entry.copy(enabled = false)))))
        val restarted = ManagedMarketSymbols(props, mapper)
        assertThat(restarted.current()).doesNotContainKey("005930")
    }
}
