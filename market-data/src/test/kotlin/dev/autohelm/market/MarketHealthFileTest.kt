package dev.autohelm.market

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class MarketHealthFileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `health snapshot exposes subscription and progress without credentials`() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val props = MarketProperties(dataDir = directory)
        val writer = MarketHealthFile(props, mapper)
        val at = Instant.parse("2026-10-27T00:01:00Z")
        writer.write(at, listOf(MarketHealth("dev", "005930", Venue.KRX, SubscriptionState.SUBSCRIBED,
            at, null, null, at)), at, at, at)

        val root = mapper.readTree(directory.resolve("dev/market/health.json").toFile())
        assertEquals("SUBSCRIBED", root.path("subscriptions")[0].path("subscriptionState").asText())
        assertEquals(at.toString(), root.path("lastBarAt").asText())
        assertEquals(at.toString(), root.path("firstBarAt").asText())
        assertEquals(false, root.toString().contains("app-secret"))
    }
}
