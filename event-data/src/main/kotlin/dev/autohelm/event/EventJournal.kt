package dev.autohelm.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Each service owns its files; no other process writes to this journal. */
class EventJournal(private val properties: EventProperties, private val mapper: ObjectMapper) {
    private val root = properties.dataDir.resolve(properties.environment).resolve("events")
    private val seoul = ZoneId.of("Asia/Seoul")

    fun appendRaw(fetch: RawFetch) = append("raw", fetch.receivedAt, fetch)
    fun appendEvent(record: EventRecord) = append("events", record.firstSeenAt, record)
    fun appendLink(link: EventLink) = append("links", link.firstSeenAt, link)

    fun existingIds(): MutableSet<String> {
        val ids = mutableSetOf<String>()
        readCompleteLines("events") { ids += mapper.readValue<EventRecord>(it).id }
        return ids
    }

    fun existingLinkIds(): MutableSet<String> {
        val ids = mutableSetOf<String>()
        readCompleteLines("links") { ids += mapper.readValue<EventLink>(it).id }
        return ids
    }

    fun saveDisclosure(receiptNo: String, bytes: ByteArray): Path {
        require(receiptNo.matches(Regex("[0-9]{14}")))
        val target = root.resolve("disclosures").resolve("$receiptNo.zip")
        Files.createDirectories(target.parent)
        if (Files.exists(target)) return target
        val temporary = Files.createTempFile(target.parent, "$receiptNo-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun append(kind: String, at: Instant, item: Any) {
        val path = root.resolve(kind).resolve("${at.atZone(seoul).toLocalDate()}.jsonl")
        Files.createDirectories(path.parent)
        val bytes = (mapper.writeValueAsString(item) + "\n").toByteArray()
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun readCompleteLines(kind: String, consume: (String) -> Unit) {
        val directory = root.resolve(kind)
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(".jsonl") }.sorted().forEach { file ->
                FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                    val bytes = Files.readAllBytes(file)
                    val lastNewline = bytes.indexOfLast { it == '\n'.code.toByte() }
                    if (lastNewline < bytes.lastIndex) {
                        val partial = file.resolveSibling("${file.fileName}.partial-${UUID.randomUUID()}")
                        Files.write(partial, bytes.copyOfRange(lastNewline + 1, bytes.size))
                        channel.truncate((lastNewline + 1).toLong())
                        channel.force(true)
                    }
                    if (lastNewline >= 0) {
                        String(bytes, 0, lastNewline + 1, Charsets.UTF_8).lineSequence()
                            .filter { it.isNotBlank() }.forEach(consume)
                    }
                }
            }
        }
    }
}
