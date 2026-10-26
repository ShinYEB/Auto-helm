package dev.autohelm.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.model.ModelBundleIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class HistoricalBar(
    val environment: String,
    val symbol: String,
    val venue: String,
    val session: String,
    val minuteStart: Instant,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
    val provider: String = "KIS",
    val quality: String = "PROVIDER_REPORTED",
    val fetchedAt: Instant,
    val sourceSha256: String,
)

data class HistoricalManifest(
    val schema: String = "HistoricalDay.v1",
    val environment: String,
    val symbol: String,
    val venue: String,
    val session: String,
    val date: LocalDate,
    val pageCount: Int,
    val barCount: Int,
    val barsSha256: String?,
    val state: String,
    val completedAt: Instant,
)

class HistoricalDataCollector(
    private val properties: TrainingProperties,
    private val client: KisHistoricalClient,
    private val mapper: ObjectMapper,
) {
    private val root = properties.dataDir.resolve(properties.environment).resolve("historical")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val timeFormat = DateTimeFormatter.ofPattern("HHmmss")

    fun collect(): Int {
        properties.requireMarket()
        val from = requireNotNull(properties.from) { "training.from is required" }
        val to = requireNotNull(properties.to) { "training.to is required" }
        require(!from.isAfter(to) && ChronoUnit.DAYS.between(from, to) <= 366)
        require(properties.startTime.isBefore(properties.endTime))
        var count = 0
        for (date in from.datesUntil(to.plusDays(1))) {
            count += collectDate(date)
        }
        return count
    }

    internal fun collectDate(date: LocalDate): Int {
        properties.requireMarket()
        val directory = root.resolve("bars/${properties.symbol}/${properties.venue}/${properties.session}")
        val barsFile = directory.resolve("$date.jsonl")
        val manifestFile = root.resolve("manifest/${properties.symbol}/${properties.venue}/${properties.session}/$date.json")
        if (Files.exists(manifestFile)) {
            val manifest = mapper.readValue<HistoricalManifest>(Files.readAllBytes(manifestFile))
            if (manifest.state in setOf("COMPLETE", "EMPTY") &&
                (manifest.state == "EMPTY" || Files.exists(barsFile) &&
                    ModelBundleIO.sha256(Files.readAllBytes(barsFile)) == manifest.barsSha256)) return 0
        }
        var cursor = properties.endTime
        var pages = 0
        val bars = sortedMapOf<Instant, HistoricalBar>()
        var finished = false
        while (pages < properties.maxPagesPerDay) {
            val page = fetchWithRetry(date, cursor)
            val fetchedAt = Instant.now()
            val hash = ModelBundleIO.sha256(page.body.toByteArray(Charsets.UTF_8))
            val raw = root.resolve("raw/KIS/${properties.symbol}/${properties.venue}/$date")
                .resolve("${cursor.format(timeFormat)}-${hash.take(12)}.json")
            if (!Files.exists(raw)) atomicWrite(raw, page.body.toByteArray(Charsets.UTF_8))
            pages++
            val dated = page.rows.filter { it.path("stck_bsop_date").asText() == date.format(DateTimeFormatter.BASIC_ISO_DATE) }
            for (row in dated) {
                val bar = parse(row, date, fetchedAt, hash) ?: continue
                val old = bars.putIfAbsent(bar.minuteStart, bar)
                require(old == null || listOf(old.open, old.high, old.low, old.close, old.volume) ==
                    listOf(bar.open, bar.high, bar.low, bar.close, bar.volume)) { "Conflicting historical minute" }
            }
            if (page.rows.isEmpty() || dated.isEmpty()) { finished = true; break }
            val earliest = dated.minOf { LocalTime.parse(it.path("stck_cntg_hour").asText(), timeFormat) }
            if (!earliest.isAfter(properties.startTime)) { finished = true; break }
            require(!earliest.isAfter(cursor)) { "Historical pagination moved forward" }
            cursor = earliest.minusSeconds(1)
        }
        check(finished) { "Historical day reached page limit without completion" }
        // Each bar is one JSONL line; no partial day becomes visible to training.
        if (bars.isNotEmpty()) {
            val jsonl = bars.values.joinToString("\n", postfix = "\n") { mapper.writeValueAsString(it) }
            atomicWrite(barsFile, jsonl.toByteArray(Charsets.UTF_8))
        }
        val manifest = HistoricalManifest(environment = properties.environment, symbol = properties.symbol,
            venue = properties.venue, session = properties.session, date = date, pageCount = pages,
            barCount = bars.size, barsSha256 = if (bars.isEmpty()) null else ModelBundleIO.sha256(Files.readAllBytes(barsFile)),
            state = if (bars.isEmpty()) "EMPTY" else "COMPLETE", completedAt = Instant.now())
        atomicWrite(manifestFile, mapper.writeValueAsBytes(manifest))
        return bars.size
    }

    private fun fetchWithRetry(date: LocalDate, cursor: LocalTime): KisPage {
        repeat(3) { attempt ->
            try { return client.fetch(properties.symbol, properties.venue, date, cursor) }
            catch (_: KisRateLimit) { if (attempt == 2) throw KisRateLimit(); Thread.sleep(60_000L) }
        }
        error("unreachable")
    }

    private fun parse(row: JsonNode, date: LocalDate, fetchedAt: Instant, hash: String): HistoricalBar? {
        val time = LocalTime.parse(row.path("stck_cntg_hour").asText(), timeFormat)
        if (time.isBefore(properties.startTime) || !time.isBefore(properties.endTime)) return null
        require(time.second == 0) { "Non-minute historical timestamp" }
        fun number(field: String): Long = row.path(field).asText().toLong()
        val open = number("stck_oprc")
        val high = number("stck_hgpr")
        val low = number("stck_lwpr")
        val close = number("stck_prpr")
        val volume = number("cntg_vol")
        require(open > 0 && low > 0 && high >= maxOf(open, close) && low <= minOf(open, close) && volume >= 0)
        return HistoricalBar(properties.environment, properties.symbol, properties.venue, properties.session,
            date.atTime(time).atZone(seoul).toInstant(), open, high, low, close, volume,
            fetchedAt = fetchedAt, sourceSha256 = hash)
    }

    private fun atomicWrite(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, "write-", ".tmp")
        try {
            java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(tmp) }
    }
}
