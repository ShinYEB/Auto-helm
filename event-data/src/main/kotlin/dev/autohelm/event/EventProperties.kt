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
    val rss: RssProperties = RssProperties(),
    val naver: NaverNewsProperties = NaverNewsProperties(),
    val jev: JevProperties = JevProperties(),
    val search: EventSearchProperties = EventSearchProperties(),
    val dart: DartProperties = DartProperties(),
    val redis: EventRedisProperties = EventRedisProperties(),
) {
    init {
        require(environment in setOf("dev", "prd"))
        if (enabled) require(gdelt.enabled || dart.enabled || rss.enabled || naver.enabled) { "Enable at least one event source" }
    }
}

data class EventSearchProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val endpoint: URI = URI.create("https://api.openai.com/v1/responses"),
    val model: String = "gpt-4.1-mini",
    val policyVersion: String = "web-confirm-v1",
    val threshold: Double = 0.6,
    val maxCallsPerDay: Int = 10,
    val maxAttempts: Int = 2,
    val pollInterval: Duration = Duration.ofSeconds(10),
    val maxEventAge: Duration = Duration.ofDays(1),
) {
    init {
        require(threshold in 0.0..1.0)
        require(maxCallsPerDay in 1..1000)
        require(maxAttempts in 1..5)
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(!maxEventAge.isNegative && !maxEventAge.isZero)
        if (enabled) require(apiKey.isNotBlank()) { "Search API key is required when search is enabled" }
    }
}

data class JevProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val endpoint: URI = URI.create("https://api.typesafe.ai/v1/systemone"),
    val model: String = "jev-1.13.0",
    val analysisVersion: String = "issue-v2-jev-1.13.0",
    val pollInterval: Duration = Duration.ofSeconds(5),
    val maxAttempts: Int = 2,
    val maxCallsPerDay: Int = 100,
    val maxEventAge: Duration = Duration.ofDays(1),
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(maxAttempts in 1..5)
        require(maxCallsPerDay in 1..10_000)
        require(!maxEventAge.isNegative && !maxEventAge.isZero)
        if (enabled) require(apiKey.isNotBlank()) { "Jev API key is required when analysis is enabled" }
    }
}

data class RssProperties(
    val enabled: Boolean = false,
    val feeds: Map<String, URI> = mapOf(
        "stocks" to URI.create("https://news.einfomax.co.kr/rss/S1N2.xml"),
        "business" to URI.create("https://news.einfomax.co.kr/rss/S1N7.xml"),
        "policy" to URI.create("https://news.einfomax.co.kr/rss/S1N15.xml"),
        "fx" to URI.create("https://news.einfomax.co.kr/rss/S1N16.xml"),
        "international" to URI.create("https://news.einfomax.co.kr/rss/S1N23.xml"),
    ),
    val pollInterval: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(feeds.isNotEmpty() && feeds.keys.all { it.isNotBlank() })
        require(feeds.values.all { it.scheme in setOf("http", "https") })
    }
}

data class NaverNewsProperties(
    val enabled: Boolean = false,
    val clientId: String = "",
    val clientSecret: String = "",
    val endpoint: URI = URI.create("https://naverapihub.apigw.ntruss.com/search/v1/news"),
    val pollInterval: Duration = Duration.ofMinutes(5),
    val maxCallsPerDay: Int = 500,
    val display: Int = 30,
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(maxCallsPerDay in 1..25_000 && display in 1..100)
        if (enabled) require(clientId.isNotBlank() && clientSecret.isNotBlank())
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
    val pollInterval: Duration = Duration.ofMinutes(5),
    val minRequestGap: Duration = Duration.ofSeconds(10),
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(minRequestGap >= Duration.ofSeconds(5))
        if (enabled) {
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
            require(apiKey.isNotBlank())
            require(corpCodes.keys.all { it.matches(Regex("[0-9A-Z]{6}")) })
            require(corpCodes.values.all { it.matches(Regex("[0-9]{8}")) })
        }
    }
}
