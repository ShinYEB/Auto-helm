package dev.autohelm.training

import com.fasterxml.jackson.databind.ObjectMapper
import dev.autohelm.model.ModelBundleIO
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

@ConfigurationProperties("training")
data class TrainingProperties(
    val command: String = "none",
    val environment: String = "dev",
    val dataDir: Path = Path.of("./data"),
    val restUrl: URI = URI.create("https://openapivts.koreainvestment.com:29443"),
    val appKey: String = "",
    val appSecret: String = "",
    val symbol: String = "",
    val venue: String = "",
    val session: String = "regular",
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val startTime: LocalTime = LocalTime.of(0, 0),
    val endTime: LocalTime = LocalTime.of(23, 59),
    val minRequestGap: Duration = Duration.ofSeconds(2),
    val maxPagesPerDay: Int = 20,
    val version: String = "",
    val targetEnvironment: String = "",
    val evidenceFile: Path? = null,
) {
    init {
        require(environment in setOf("dev", "prd"))
        require(command in setOf("none", "collect", "fit", "promote"))
        require(minRequestGap >= Duration.ofMillis(500) && maxPagesPerDay in 1..100)
    }

    fun requireMarket() {
        require(symbol.matches(Regex("[0-9A-Z]{6}")))
        require(venue in setOf("KRX", "NXT"))
        require(session.matches(Regex("[A-Za-z0-9._-]{1,40}")))
    }
}

@SpringBootApplication
@EnableConfigurationProperties(TrainingProperties::class)
class TrainingApplication {
    @Bean
    fun runJob(properties: TrainingProperties, mapper: ObjectMapper) = CommandLineRunner {
        val io = ModelBundleIO(mapper)
        when (properties.command) {
            "collect" -> HistoricalDataCollector(properties, KisHistoricalClient(properties, mapper), mapper).collect()
            "fit" -> LinearModelTrainer(properties, mapper, io).fit()
            "promote" -> ModelPromoter(properties, mapper, io).promote()
            else -> error("Specify --training.command=collect|fit|promote")
        }
    }
}

fun main(args: Array<String>) { runApplication<TrainingApplication>(*args) }
