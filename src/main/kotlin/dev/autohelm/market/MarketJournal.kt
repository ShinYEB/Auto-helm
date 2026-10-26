package dev.autohelm.market

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Append-only local source of truth. Every append is forced before downstream processing. */
class MarketJournal(private val properties: MarketProperties, private val mapper: ObjectMapper) {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val root = properties.dataDir.resolve(properties.environment).resolve("market")

    fun appendRaw(frame: RawFrame) = append("raw", frame.receivedAt, frame)
    fun appendBar(bar: MinuteBar) = append("bars", bar.minuteStart, bar)
    fun appendGap(gap: MarketGap) = append("gaps", gap.startedAt, gap)

    fun readRaw(date: LocalDate): List<RawFrame> = buildList { forEachRaw(date, ::add) }
    fun readBars(date: LocalDate): List<MinuteBar> = read("bars", date)
    fun readGaps(date: LocalDate): List<MarketGap> = read("gaps", date)

    fun forEachRaw(date: LocalDate, consume: (RawFrame) -> Unit) {
        forEachCompleteLine("raw", date) { consume(mapper.readValue(it)) }
    }

    private fun path(kind: String, date: LocalDate): Path = root.resolve(kind).resolve("$date.jsonl")

    @Synchronized
    private fun append(kind: String, instant: Instant, item: Any) {
        val file = path(kind, instant.atZone(seoul).toLocalDate())
        Files.createDirectories(file.parent)
        val bytes = (mapper.writeValueAsString(item) + "\n").toByteArray(Charsets.UTF_8)
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private inline fun <reified T> read(kind: String, date: LocalDate): List<T> {
        val result = mutableListOf<T>()
        forEachCompleteLine(kind, date) { result += mapper.readValue<T>(it) }
        return result
    }

    /** A power cut can leave a partial final JSON line. Keep it separately and replay complete lines. */
    private fun forEachCompleteLine(kind: String, date: LocalDate, consume: (String) -> Unit) {
        val file = path(kind, date)
        if (!Files.exists(file)) return
        FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.allocate(64 * 1024)
            val line = ByteArrayOutputStream()
            var offset = 0L
            var safeOffset = 0L
            while (channel.read(buffer) > 0) {
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val byte = buffer.get()
                    offset++
                    if (byte == '\n'.code.toByte()) {
                        if (line.size() > 0) consume(line.toString(Charsets.UTF_8))
                        line.reset()
                        safeOffset = offset
                    } else {
                        line.write(byte.toInt())
                    }
                }
                buffer.clear()
            }
            if (line.size() > 0) {
                val partial = file.resolveSibling("${file.fileName}.partial-${UUID.randomUUID()}")
                Files.write(partial, line.toByteArray(), StandardOpenOption.CREATE_NEW)
                channel.truncate(safeOffset)
                channel.force(true)
            }
        }
    }
}
