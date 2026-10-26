package dev.autohelm.control

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.Instant

data class BarSummary(val venue: String, val minuteStart: String, val close: Double,
                      val quality: String)
data class SymbolDetailsSnapshot(val symbol: String, val barCount: Long, val latestBars: List<BarSummary>,
                                 val coverage: List<HistoricalCoverage>, val activeModels: List<ModelSummary>,
                                 val candidates: List<ModelSummary>, val jobs: List<TrainingJob>,
                                 val events: List<RecentEvent>, val at: Instant)

@Component
class SymbolDetails(private val properties: ControlProperties, private val mapper: ObjectMapper,
                    private val data: ConsoleData, private val jobs: TrainingJobs) {
    fun snapshot(symbol: String): SymbolDetailsSnapshot {
        require(symbol.matches(Regex("[0-9A-Z]{6}")))
        val root = properties.dataDir.resolve(properties.environment)
        val barsDir = root.resolve("market/bars")
        var count = 0L
        val latest = mutableListOf<BarSummary>()
        if (Files.isDirectory(barsDir)) Files.list(barsDir).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .sorted(Comparator.reverseOrder()).limit(7).forEach { file ->
                    Files.lines(file).use { lines -> lines.forEach { line ->
                        runCatching { mapper.readTree(line) }.getOrNull()?.takeIf {
                            it.path("symbol").asText() == symbol
                        }?.let {
                            count++
                            latest += BarSummary(it.path("venue").asText(), it.path("minuteStart").asText(),
                                it.path("close").asDouble(), it.path("quality").asText())
                        }
                    } }
                }
        }
        val models = data.models()
        return SymbolDetailsSnapshot(symbol, count, latest.sortedByDescending { it.minuteStart }.take(10),
            models.coverage.filter { it.symbol == symbol }, models.active.filter { it.symbol == symbol },
            models.candidates.filter { it.symbol == symbol }, jobs.list().filter { it.symbol == symbol },
            data.information().recentEvents.filter { it.symbol == symbol }, Instant.now())
    }
}
