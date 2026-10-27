package dev.autohelm.control

import com.fasterxml.jackson.databind.ObjectMapper
import dev.autohelm.model.ModelBundleIO
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

data class ModelSummary(val version: String, val symbol: String, val venue: String,
                        val session: String, val eligible: Boolean, val active: Boolean,
                        val createdAt: Instant, val next1mMae: Double, val next1mBaselineMae: Double,
                        val after1hMae: Double, val after1hBaselineMae: Double)
data class HistoricalCoverage(val symbol: String, val venue: String, val session: String,
                              val completeDays: Int, val firstDate: LocalDate, val lastDate: LocalDate)
data class ModelOverview(val candidates: List<ModelSummary>, val active: List<ModelSummary>,
                         val coverage: List<HistoricalCoverage>, val error: String? = null)
data class SourceInfo(val source: String, val enabled: Boolean, val attempts: Long, val successes: Long,
                      val failures: Long, val rateLimits: Long, val discovered: Long,
                      val lastResult: String, val lastSuccessAt: Instant?)
data class RecentEvent(val id: String, val source: String, val title: String, val symbol: String?,
                       val url: String, val firstSeenAt: Instant)
data class InformationOverview(val sources: List<SourceInfo>, val recentEvents: List<RecentEvent>,
                               val at: Instant)

@Component
class ConsoleData(private val properties: ControlProperties, private val mapper: ObjectMapper) {
    private val root get() = properties.dataDir.resolve(properties.environment)
    private val io = ModelBundleIO(mapper)

    fun models(): ModelOverview {
        val active = runCatching { io.loadActive(root.resolve("models")) }.getOrElse {
            return ModelOverview(emptyList(), emptyList(), coverage(), "Active model could not be verified")
        }
        val candidatesRoot = root.resolve("training/bundles")
        val candidates = if (Files.isDirectory(candidatesRoot)) Files.list(candidatesRoot).use { paths ->
            paths.filter { Files.isDirectory(it) }.map { path ->
                runCatching { summary(io.read(path), false) }.getOrNull()
            }.filter { it != null }.map { it!! }.toList()
                .sortedByDescending(ModelSummary::createdAt).take(100)
        } else emptyList()
        return ModelOverview(candidates, active.values.map { summary(it, true) }
            .sortedBy(ModelSummary::symbol), coverage())
    }

    private fun summary(bundle: dev.autohelm.model.ModelBundle, active: Boolean): ModelSummary {
        val one = bundle.weights.heads.getValue("NEXT_1M")
        val hour = bundle.weights.heads.getValue("AFTER_1H")
        val manifest = bundle.manifest
        return ModelSummary(manifest.version, manifest.symbol, manifest.venue, manifest.session,
            manifest.eligible, active, manifest.createdAt, one.validationMae, one.baselineMae,
            hour.validationMae, hour.baselineMae)
    }

    private fun coverage(): List<HistoricalCoverage> {
        val directory = root.resolve("historical/manifest")
        if (!Files.isDirectory(directory)) return emptyList()
        val dates = mutableMapOf<Triple<String, String, String>, MutableList<LocalDate>>()
        Files.walk(directory, 4).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.forEach { path ->
                runCatching {
                    val node = mapper.readTree(Files.readAllBytes(path))
                    if (node.path("state").asText() == "COMPLETE") {
                        val key = Triple(node.path("symbol").asText(), node.path("venue").asText(),
                            node.path("session").asText())
                        dates.getOrPut(key) { mutableListOf() } += LocalDate.parse(node.path("date").asText())
                    }
                }
            }
        }
        return dates.map { (key, days) -> HistoricalCoverage(key.first, key.second, key.third,
            days.size, days.min(), days.max()) }.sortedWith(compareBy({ it.symbol }, { it.venue }))
    }

    fun information(): InformationOverview {
        val sources = sourceHealth()
        val events = recentEvents()
        return InformationOverview(sources, events, Instant.now())
    }

    private fun sourceHealth(): List<SourceInfo> {
        val file = root.resolve("events/health.json")
        if (!Files.exists(file)) return emptyList()
        return runCatching {
            val nodes = mapper.readTree(Files.readAllBytes(file)).path("sources")
            listOf("DART", "GDELT", "EINFOMAX", "NAVER").map { name ->
                val n = nodes.path(name)
                SourceInfo(name, n.path("enabled").asBoolean(false), n.path("attempts").asLong(),
                    n.path("successes").asLong(), n.path("failures").asLong(),
                    n.path("rateLimits").asLong(), n.path("discovered").asLong(),
                    n.path("lastResult").asText("NOT_STARTED"),
                    n.path("lastSuccessAt").asText().takeIf { it.isNotBlank() && it != "null" }
                        ?.let(Instant::parse))
            }
        }.getOrDefault(emptyList())
    }

    private fun recentEvents(): List<RecentEvent> {
        val directory = root.resolve("events/events")
        if (!Files.isDirectory(directory)) return emptyList()
        val files = Files.list(directory).use { paths -> paths.filter {
            Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl")
        }.sorted(Comparator.reverseOrder()).limit(2).toList() }
        val events = mutableListOf<RecentEvent>()
        for (file in files) Files.lines(file).use { lines ->
            lines.filter(String::isNotBlank).forEach { line ->
                runCatching {
                    val n = mapper.readTree(line)
                    RecentEvent(n.path("id").asText(), n.path("source").asText(),
                        n.path("title").asText(), n.path("symbol").asText().takeIf { it.isNotBlank() && it != "null" },
                        n.path("url").asText(), Instant.parse(n.path("firstSeenAt").asText()))
                }.getOrNull()?.let(events::add)
            }
        }
        return events.sortedByDescending(RecentEvent::firstSeenAt).take(20)
    }
}
