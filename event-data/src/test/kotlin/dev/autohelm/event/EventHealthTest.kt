package dev.autohelm.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EventHealthTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `failure history survives later success and snapshot replacement`() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val health = EventHealth(EventProperties(dataDir = directory), mapper)
        health.started(emptyMap())
        health.failure(EventSource.GDELT, "RATE_LIMITED")
        health.success(EventSource.GDELT, 0)
        health.failure(EventSource.GDELT, "ERROR")

        val files = Files.list(directory.resolve("dev/events/health-events")).use { it.toList() }
        val rows = Files.readAllLines(files.single()).map(mapper::readTree)
        assertEquals(listOf("RATE_LIMITED", "ERROR"), rows.map { it.path("kind").asText() })
        val snapshot = mapper.readTree(directory.resolve("dev/events/health.json").toFile())
        assertEquals("ERROR", snapshot.path("sources").path("GDELT").path("lastResult").asText())
        assertEquals(2, snapshot.path("sources").path("GDELT").path("failures").asInt())
    }
}
