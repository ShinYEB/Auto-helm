package dev.autohelm.bridge

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonlTailerTest {
    @TempDir lateinit var root: Path

    @Test
    fun `only committed lines advance and failed delivery retries`() {
        val directory = root.resolve("bars")
        Files.createDirectories(directory)
        val day = directory.resolve("2026-10-26.jsonl")
        Files.writeString(day, "first\nsecond")
        val checkpoint = root.resolve("cursor/position")
        val tailer = JsonlTailer(directory, checkpoint)
        val delivered = mutableListOf<String>()
        assertEquals(1, tailer.drain(delivered::add))
        assertEquals(listOf("first"), delivered)
        Files.writeString(day, "\nthird\n", java.nio.file.StandardOpenOption.APPEND)
        assertFailsWith<IllegalStateException> {
            tailer.drain {
                if (it == "third") error("sink down")
                delivered += it
            }
        }
        assertEquals(1, JsonlTailer(directory, checkpoint).drain(delivered::add))
        assertEquals(listOf("first", "second", "third"), delivered)
    }

    @Test
    fun `incomplete older file blocks later date until repaired`() {
        val directory = root.resolve("bars")
        Files.createDirectories(directory)
        val first = directory.resolve("2026-10-26.jsonl")
        Files.writeString(first, "partial")
        Files.writeString(directory.resolve("2026-10-27.jsonl"), "next\n")
        val tailer = JsonlTailer(directory, root.resolve("cursor/position"))
        val delivered = mutableListOf<String>()
        assertEquals(0, tailer.drain(delivered::add))
        Files.writeString(first, "\n", java.nio.file.StandardOpenOption.APPEND)
        assertEquals(2, tailer.drain(delivered::add))
        assertEquals(listOf("partial", "next"), delivered)
    }
}
