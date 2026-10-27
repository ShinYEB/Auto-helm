package dev.autohelm.control

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WatchlistStoreTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Test
    fun `updates preserve active venue time and disabled symbols remain auditable`() {
        val store = WatchlistStore(ControlProperties(dataDir = directory), mapper)
        val first = store.put("005930", SymbolInput(0, "Samsung", setOf("KRX"), "Samsung"))
        assertThat(first.revision).isEqualTo(1)
        assertThat(first.entries.getValue("005930").activeFrom).containsKey("KRX")

        val second = store.put("005930", SymbolInput(1, "Samsung Electronics", setOf("KRX", "NXT")))
        assertThat(second.entries.getValue("005930").activeFrom.getValue("KRX"))
            .isEqualTo(first.entries.getValue("005930").activeFrom.getValue("KRX"))
        assertThat(second.entries.getValue("005930").activeFrom).containsKey("NXT")
        assertThatThrownBy { store.disable("005930", 1) }.isInstanceOf(RevisionConflict::class.java)

        val disabled = store.disable("005930", 2)
        assertThat(disabled.entries.getValue("005930").enabled).isFalse()
        assertThat(store.current()).isEqualTo(disabled)
        assertThat(Files.readAllLines(directory.resolve("dev/control/watchlist-audit.jsonl"))).hasSize(3)
    }

    @Test
    fun `production watchlist cannot be edited`() {
        val store = WatchlistStore(ControlProperties(environment = "prd", dataDir = directory), mapper)
        assertThatThrownBy { store.put("005930", SymbolInput(0, "Samsung", setOf("KRX"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `GDELT query requires English search terms`() {
        val store = WatchlistStore(ControlProperties(dataDir = directory), mapper)
        assertThatThrownBy { store.put("005930", SymbolInput(0, "삼성전자", setOf("KRX"), "삼성전자")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
