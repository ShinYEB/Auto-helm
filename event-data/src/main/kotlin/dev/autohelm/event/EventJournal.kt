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
    fun appendEventUpdate(record: EventRecord) = append("events", Instant.now(), record)
    fun appendLink(link: EventLink) = append("links", link.firstSeenAt, link)
    fun appendObservation(observation: EventObservation) = append("observations", observation.receivedAt, observation)
    fun appendAnalysis(result: IssueAnalysis) = append("analysis", result.completedAt, result)
    fun appendSearchResult(result: IssueSearchResult) = append("search", result.completedAt, result)

    fun existingIds(): MutableSet<String> {
        val ids = mutableSetOf<String>()
        readCompleteLines("events") { ids += mapper.readValue<EventRecord>(it).id }
        return ids
    }

    fun existingRecords(): Map<String, EventRecord> {
        val records = mutableMapOf<String, EventRecord>()
        readCompleteLines("events") {
            val record = mapper.readValue<EventRecord>(it)
            records[record.id] = record
        }
        return records
    }

    fun existingLinkIds(): MutableSet<String> {
        val ids = mutableSetOf<String>()
        readCompleteLines("links") { ids += mapper.readValue<EventLink>(it).id }
        return ids
    }

    fun existingObservationIds(): MutableSet<String> {
        val ids = mutableSetOf<String>()
        readCompleteLines("observations") { ids += mapper.readValue<EventObservation>(it).id }
        return ids
    }

    fun existingSourceCounts(): Map<EventSource, Long> {
        return existingRecords().values.groupingBy { it.source }.eachCount().mapValues { it.value.toLong() }
    }

    fun legacyNewsIdsByUrl(): Map<String, String> {
        val ids = mutableMapOf<String, String>()
        readCompleteLines("events") {
            val event = mapper.readValue<EventRecord>(it)
            if (event.id.startsWith("GDELT:")) ids[event.url] = event.id
        }
        return ids
    }

    fun existingAnalyses(): List<IssueAnalysis> {
        val items = mutableListOf<IssueAnalysis>()
        readCompleteLines("analysis") { items += mapper.readValue<IssueAnalysis>(it) }
        return items
    }

    fun existingSearchResults(): List<IssueSearchResult> {
        val items = mutableListOf<IssueSearchResult>()
        readCompleteLines("search") { items += mapper.readValue<IssueSearchResult>(it) }
        return items
    }

    fun saveWorking(eventId: String, description: String) {
        val target = root.resolve("working").resolve("${NewsIdentity.hash(eventId)}.txt")
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "excerpt-", ".tmp")
        try {
            Files.writeString(temporary, description.take(2000))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun readWorking(eventId: String): String? {
        val path = root.resolve("working").resolve("${NewsIdentity.hash(eventId)}.txt")
        return if (Files.isRegularFile(path)) Files.readString(path) else null
    }

    fun deleteWorking(eventId: String) {
        Files.deleteIfExists(root.resolve("working").resolve("${NewsIdentity.hash(eventId)}.txt"))
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
