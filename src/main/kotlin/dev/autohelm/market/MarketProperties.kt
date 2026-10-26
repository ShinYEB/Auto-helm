package dev.autohelm.market

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

@ConfigurationProperties("market")
data class MarketProperties(
    val enabled: Boolean = false,
    val environment: String = "dev",
    val dataDir: Path = Path.of("./data"),
    val symbols: List<String> = emptyList(),
    val queueCapacity: Int = 10_000,
    val lateGrace: Duration = Duration.ofSeconds(3),
    val restUrl: String = "https://openapivts.koreainvestment.com:29443",
    val wsUrl: String = "ws://ops.koreainvestment.com:31000/tryitout",
    val appKey: String = "",
    val appSecret: String = "",
    val sessions: List<SessionWindow> = emptyList(),
) {
    init {
        require(environment == "dev" || environment == "prd")
        require(queueCapacity > 0 && !lateGrace.isNegative)
        require(symbols.all { it.matches(Regex("[0-9A-Z]{6}")) })
        if (enabled) {
            require(symbols.isNotEmpty() && appKey.isNotBlank() && appSecret.isNotBlank())
            require((environment == "dev" && "31000" in wsUrl) || (environment == "prd" && "21000" in wsUrl)) {
                "WebSocket endpoint must match the selected environment"
            }
            require((environment == "dev" && "openapivts.koreainvestment.com" in restUrl) ||
                (environment == "prd" && "openapi.koreainvestment.com" in restUrl)) {
                "REST endpoint must match the selected environment"
            }
        }
    }
}

data class SessionWindow(
    val venue: Venue,
    val name: String,
    val dates: List<LocalDate>,
    val start: LocalTime,
    val end: LocalTime,
) {
    init { require(name.isNotBlank() && start.isBefore(end)) }
}
