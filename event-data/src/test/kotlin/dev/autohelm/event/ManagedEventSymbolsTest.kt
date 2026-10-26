package dev.autohelm.event

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

class ManagedEventSymbolsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `managed source settings replace YAML search and corp code`() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val at = Instant.parse("2026-10-26T01:00:00Z")
        val entry = WatchlistEntry("005930", "Samsung", setOf("KRX"),
            gdeltQuery = "Samsung Electronics", dartCorpCode = null, updatedAt = at)
        val path = directory.resolve("dev/control/watchlist.json")
        Files.createDirectories(path.parent)
        Files.write(path, mapper.writeValueAsBytes(WatchlistSnapshot(environment = "dev", revision = 1,
            updatedAt = at, entries = mapOf(entry.symbol to entry))))
        val props = EventProperties(dataDir = directory,
            gdelt = GdeltProperties(searches = mapOf("005930" to "old", "000660" to "SK Hynix")),
            dart = DartProperties(corpCodes = mapOf("005930" to "00126380")))
        val managed = ManagedEventSymbols(props, mapper)
        assertThat(managed.gdeltSearches()).containsEntry("005930", "Samsung Electronics")
            .containsEntry("000660", "SK Hynix")
        assertThat(managed.dartCorpCodes()).doesNotContainKey("005930")
    }
}
