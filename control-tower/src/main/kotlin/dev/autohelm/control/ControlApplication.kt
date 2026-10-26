package dev.autohelm.control

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import java.nio.file.Path
import java.net.URI

@ConfigurationProperties("control")
data class ControlProperties(
    val environment: String = "dev",
    val dataDir: Path = Path.of("./data"),
    val trainingJar: Path = Path.of("./model-training/build/libs/model-training-0.1.0-SNAPSHOT.jar"),
    val prometheusUrl: URI = URI.create("http://127.0.0.1:9090"),
    val token: String = "",
) {
    init {
        require(environment in setOf("dev", "prd"))
        require(token.length >= 32) { "AUTOHELM_CONTROL_TOKEN must contain at least 32 characters" }
        require(prometheusUrl.scheme == "http" && prometheusUrl.host in setOf("127.0.0.1", "localhost") &&
            prometheusUrl.port in 1..65535 && prometheusUrl.rawUserInfo == null &&
            prometheusUrl.rawQuery == null && prometheusUrl.rawFragment == null) {
            "Prometheus must be a local HTTP endpoint"
        }
    }
}

@SpringBootApplication
@EnableConfigurationProperties(ControlProperties::class)
class ControlApplication

fun main(args: Array<String>) { runApplication<ControlApplication>(*args) }
