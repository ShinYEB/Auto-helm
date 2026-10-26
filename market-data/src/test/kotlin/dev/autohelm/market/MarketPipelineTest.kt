package dev.autohelm.market

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class MarketPipelineTest {
    @TempDir lateinit var directory: Path

    private val date = LocalDate.of(2026, 10, 26)
    private val start = Instant.parse("2026-10-26T01:00:00Z") // 10:00 KST

    private fun properties() = MarketProperties(
        dataDir = directory,
        symbols = listOf("005930"),
        lateGrace = Duration.ofSeconds(3),
        sessions = listOf(
            SessionWindow(Venue.KRX, "regular", listOf(date), LocalTime.of(10, 0), LocalTime.of(10, 3)),
            SessionWindow(Venue.NXT, "regular", listOf(date), LocalTime.of(10, 0), LocalTime.of(10, 3)),
        ),
    )

    private fun frame(id: String, venue: Venue, clock: String, price: Long, volume: Long): RawFrame {
        val fields = MutableList(47) { "0" }
        fields[0] = "005930"
        fields[1] = clock
        fields[2] = price.toString()
        fields[12] = volume.toString()
        fields[13] = volume.toString()
        fields[33] = "20261026"
        val trId = if (venue == Venue.KRX) "H0STCNT0" else "H0NXCNT0"
        return RawFrame(id, start.plusSeconds(clock.takeLast(2).toLong()), "0|$trId|1|${fields.joinToString("^")}")
    }

    @Test
    fun `new subscription does not fabricate earlier empty minutes`() {
        val props = properties().copy(symbols = emptyList())
        val activation = start.plusSeconds(75)
        val builder = MinuteBarBuilder(props, { mapOf("005930" to setOf(Venue.KRX)) },
            { _, _ -> activation })

        val bars = builder.readyToSeal(start.plusSeconds(184))
        assertThat(bars.map { it.minuteStart }).containsExactly(start.plusSeconds(120))
        assertThat(bars.single().quality).isEqualTo(BarQuality.EMPTY_UNVERIFIED)
    }

    @Test
    fun `market ticks produce separate venue bars and no-trade minute stays empty`() {
        val props = properties()
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val journal = MarketJournal(props, mapper)
        val published = mutableListOf<MinuteBar>()
        val engine = MarketEngine(props, journal, onBar = published::add)

        engine.accept(frame("a", Venue.KRX, "100010", 70_000, 2))
        engine.accept(frame("b", Venue.NXT, "100020", 70_100, 3))
        engine.accept(frame("c", Venue.KRX, "100050", 70_200, 4))
        engine.seal(start.plusSeconds(64))

        val krx = published.single { it.venue == Venue.KRX }
        val nxt = published.single { it.venue == Venue.NXT }
        assertThat(krx.quality).isEqualTo(BarQuality.READY)
        assertThat(krx.open).isEqualTo(70_000)
        assertThat(krx.close).isEqualTo(70_200)
        assertThat(krx.volume).isEqualTo(6)
        assertThat(listOf(krx.priceP25, krx.priceP50, krx.priceP75))
            .containsExactly(70_000, 70_200, 70_200)
        assertThat(nxt.volume).isEqualTo(3)
        assertThat(listOf(nxt.priceP25, nxt.priceP50, nxt.priceP75))
            .containsExactly(70_100, 70_100, 70_100)

        engine.seal(start.plusSeconds(124))
        assertThat(published.filter { it.minuteStart == start.plusSeconds(60) })
            .allMatch { it.quality == BarQuality.EMPTY_UNVERIFIED && it.close == null &&
                it.priceP25 == null && it.priceP50 == null && it.priceP75 == null }
    }

    @Test
    fun `price quantiles weight each trade by executed volume`() {
        val props = properties()
        val journal = MarketJournal(props, jacksonObjectMapper().registerModule(JavaTimeModule()))
        val engine = MarketEngine(props, journal)
        engine.accept(frame("a", Venue.KRX, "100010", 100, 1))
        engine.accept(frame("b", Venue.KRX, "100020", 110, 6))
        engine.accept(frame("c", Venue.KRX, "100030", 120, 1))
        engine.seal(start.plusSeconds(64))

        val bar = journal.readBars(date).single { it.venue == Venue.KRX }
        assertThat(listOf(bar.priceP25, bar.priceP50, bar.priceP75))
            .containsExactly(110, 110, 110)
    }

    @Test
    fun `older bars without price quantiles remain readable`() {
        val props = properties()
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val journal = MarketJournal(props, mapper)
        val engine = MarketEngine(props, journal)
        engine.accept(frame("a", Venue.KRX, "100010", 100, 1))
        engine.seal(start.plusSeconds(64))

        val oldJson = mapper.valueToTree<ObjectNode>(journal.readBars(date).first { it.venue == Venue.KRX })
        oldJson.remove(listOf("priceP25", "priceP50", "priceP75"))
        val oldBar = mapper.treeToValue(oldJson, MinuteBar::class.java)
        assertThat(listOf(oldBar.priceP25, oldBar.priceP50, oldBar.priceP75))
            .containsExactly(null, null, null)
    }

    @Test
    fun `gap makes partially observed bar incomplete and replay never rewrites sealed bar`() {
        val props = properties()
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val journal = MarketJournal(props, mapper)
        val engine = MarketEngine(props, journal)
        engine.accept(frame("a", Venue.KRX, "100010", 70_000, 2))
        engine.markGap(start.plusSeconds(20), start.plusSeconds(40), "DISCONNECTED")
        engine.seal(start.plusSeconds(64))

        val original = journal.readBars(date).single { it.venue == Venue.KRX }
        assertThat(original.quality).isEqualTo(BarQuality.INCOMPLETE)
        assertThat(original.volume).isEqualTo(2)

        val restarted = MarketEngine(props, journal)
        restarted.recover(start.plusSeconds(65))
        restarted.accept(frame("late", Venue.KRX, "100030", 99_000, 9).copy(receivedAt = start.plusSeconds(120)))
        restarted.seal(start.plusSeconds(125))
        assertThat(journal.readBars(date).filter { it.id == original.id }).containsExactly(original)
    }

    @Test
    fun `invalid frame is preserved but cannot enter a ready bar`() {
        val props = properties()
        val journal = MarketJournal(props, jacksonObjectMapper().registerModule(JavaTimeModule()))
        val engine = MarketEngine(props, journal)
        engine.accept(RawFrame("bad", start.plusSeconds(10), "0|H0STCNT0|1|broken"))
        engine.seal(start.plusSeconds(64))

        assertThat(journal.readRaw(date)).hasSize(1)
        assertThat(journal.readBars(date).single { it.venue == Venue.KRX }.quality)
            .isEqualTo(BarQuality.INCOMPLETE)
    }

    @Test
    fun `trade received after its sealing deadline does not become a valid price`() {
        val props = properties()
        val journal = MarketJournal(props, jacksonObjectMapper().registerModule(JavaTimeModule()))
        val engine = MarketEngine(props, journal)
        engine.accept(frame("late", Venue.KRX, "100010", 90_000, 2).copy(receivedAt = start.plusSeconds(90)))
        engine.seal(start.plusSeconds(94))

        val bar = journal.readBars(date).single { it.venue == Venue.KRX }
        assertThat(bar.quality).isEqualTo(BarQuality.INCOMPLETE)
        assertThat(bar.close).isNull()
        assertThat(journal.readRaw(date)).hasSize(1)
    }

    @Test
    fun `restart preserves a torn final JSONL line and replays complete records`() {
        val props = properties()
        val journal = MarketJournal(props, jacksonObjectMapper().registerModule(JavaTimeModule()))
        journal.appendRaw(frame("good", Venue.KRX, "100010", 70_000, 2))
        val rawFile = directory.resolve("dev/market/raw/$date.jsonl")
        Files.writeString(rawFile, "{\"id\":\"partial", StandardOpenOption.APPEND)

        assertThat(journal.readRaw(date).map { it.id }).containsExactly("good")
        assertThat(Files.readString(rawFile)).endsWith("\n")
        assertThat(Files.list(rawFile.parent).use { it.filter { path -> path.fileName.toString().contains(".partial-") }.count() })
            .isEqualTo(1)
    }
}
