package dev.autohelm.bridge

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Reads only newline-terminated rows. The cursor advances after the sink accepts a row. */
class JsonlTailer(private val directory: Path, private val checkpoint: Path) {
    data class Cursor(val file: String, val offset: Long)

    fun reset() { Files.deleteIfExists(checkpoint) }

    fun drain(accept: (String) -> Unit): Int {
        if (!Files.isDirectory(directory)) return 0
        val files = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jsonl") }.sorted().toList()
        }
        var cursor = load()
        if (cursor != null && files.none { it.fileName.toString() == cursor.file }) {
            error("Journal cursor file missing: ${cursor.file}")
        }
        var count = 0
        for (file in files) {
            val name = file.fileName.toString()
            if (cursor != null && name < cursor.file) continue
            var offset = if (cursor?.file == name) cursor.offset else 0L
            Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
                require(channel.size() >= offset) { "Journal shrank before cursor: $file" }
                channel.position(offset)
                val byte = java.nio.ByteBuffer.allocate(1)
                val line = ByteArrayOutputStream()
                while (channel.read(byte) == 1) {
                    byte.flip()
                    val next = byte.get()
                    byte.clear()
                    if (next == '\n'.code.toByte()) {
                        val value = line.toString(Charsets.UTF_8)
                        if (value.isNotBlank()) accept(value)
                        offset = channel.position()
                        save(Cursor(name, offset))
                        cursor = Cursor(name, offset)
                        count++
                        line.reset()
                    } else {
                        require(line.size() < 4 * 1024 * 1024) { "Journal row exceeds 4 MiB: $file" }
                        line.write(next.toInt())
                    }
                }
                // A live append may be between bytes. Re-read the incomplete row next time.
                if (line.size() > 0) return count
            }
        }
        return count
    }

    private fun load(): Cursor? {
        if (!Files.exists(checkpoint)) return null
        val parts = Files.readString(checkpoint).trim().split('\t')
        require(parts.size == 2 && parts[0].matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.jsonl")))
        return Cursor(parts[0], parts[1].toLong().also { require(it >= 0) })
    }

    private fun save(cursor: Cursor) {
        Files.createDirectories(checkpoint.parent)
        val tmp = Files.createTempFile(checkpoint.parent, "cursor-", ".tmp")
        try {
            Files.writeString(tmp, "${cursor.file}\t${cursor.offset}\n")
            try {
                Files.move(tmp, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, checkpoint, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(tmp) }
    }
}
