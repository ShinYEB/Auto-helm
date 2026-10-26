package dev.autohelm.prediction

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId

/** Prediction rows are durable before the Redis message is acknowledged. */
class PredictionJournal(private val properties: PredictionProperties, private val mapper: ObjectMapper) {
    private val root = properties.dataDir.resolve(properties.environment).resolve("prediction")
    private val zone = ZoneId.of("Asia/Seoul")
    private val seenIds = mutableSetOf<String>()

    init { recoverIds() }

    @Synchronized
    fun append(record: PredictionRecord) {
        if (record.id in seenIds) return
        appendLine(root.resolve("predictions/${record.createdAt.atZone(zone).toLocalDate()}.jsonl"), record)
        seenIds += record.id
    }

    @Synchronized
    fun reject(record: RejectedBar) {
        appendLine(root.resolve("rejected/${record.receivedAt.atZone(zone).toLocalDate()}.jsonl"), record)
    }

    private fun recoverIds() {
        val directory = root.resolve("predictions")
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".jsonl") }.sorted().forEach { file ->
                val complete = repairAndRead(file)
                complete.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    seenIds += mapper.readValue<PredictionRecord>(line).id
                }
            }
        }
    }

    private fun repairAndRead(file: Path): String {
        val bytes = Files.readAllBytes(file)
        val newline = bytes.indexOfLast { it == '\n'.code.toByte() }
        val safeSize = newline + 1
        if (safeSize < bytes.size) {
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                channel.truncate(safeSize.toLong())
                channel.force(true)
            }
        }
        return String(bytes, 0, safeSize, Charsets.UTF_8)
    }

    private fun appendLine(path: Path, item: Any) {
        Files.createDirectories(path.parent)
        val bytes = (mapper.writeValueAsString(item) + "\n").toByteArray(Charsets.UTF_8)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
}
