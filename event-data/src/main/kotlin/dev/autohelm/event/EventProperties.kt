package dev.autohelm.event

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("events")
data class EventProperties(
    val enabled: Boolean = false,
    val environment: String = "dev",
    val dataDir: Path = Path.of("./data"),
    val gdelt: GdeltProperties = GdeltProperties(),
    val dart: DartProperties = DartProperties(),
    val redis: EventRedisProperties = EventRedisProperties(),
) {
    init {
        require(environment in setOf("dev", "prd"))
        if (enabled) require(gdelt.enabled || dart.enabled) { "Enable at least one event source" }
    }
}

data class EventRedisProperties(
    val enabled: Boolean = false,
    val pollInterval: Duration = Duration.ofSeconds(2),
) {
    init { require(!pollInterval.isNegative && !pollInterval.isZero) }
}

data class GdeltProperties(
    val enabled: Boolean = false,
    val searches: Map<String, String> = emptyMap(),
    val endpoint: URI = URI.create("https://api.gdeltproject.org/api/v2/doc/doc"),
    val pollInterval: Duration = Duration.ofMinutes(3),
    val minRequestGap: Duration = Duration.ofSeconds(10),
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(minRequestGap >= Duration.ofSeconds(5))
        if (enabled) {
            require(searches.isNotEmpty())
            require(searches.keys.all { it.matches(Regex("[0-9A-Z]{6}")) })
            require(searches.values.all { it.isNotBlank() })
        }
    }
}

data class DartProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val corpCodes: Map<String, String> = emptyMap(),
    val endpoint: URI = URI.create("https://opendart.fss.or.kr/api/"),
    val pollInterval: Duration = Duration.ofMinutes(15),
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero)
        if (enabled) {
            require(apiKey.isNotBlank() && corpCodes.isNotEmpty())
            require(corpCodes.keys.all { it.matches(Regex("[0-9A-Z]{6}")) })
            require(corpCodes.values.all { it.matches(Regex("[0-9]{8}")) })
        }
    }
}
