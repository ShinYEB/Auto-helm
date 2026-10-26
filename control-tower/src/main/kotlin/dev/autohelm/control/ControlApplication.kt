package dev.autohelm.control

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import java.nio.file.Path

@ConfigurationProperties("control")
data class ControlProperties(
    val environment: String = "dev",
    val dataDir: Path = Path.of("./data"),
    val trainingJar: Path = Path.of("./model-training/build/libs/model-training-0.1.0-SNAPSHOT.jar"),
    val token: String = "",
) {
    init {
        require(environment in setOf("dev", "prd"))
        require(token.length >= 32) { "AUTOHELM_CONTROL_TOKEN must contain at least 32 characters" }
    }
}

@SpringBootApplication
@EnableConfigurationProperties(ControlProperties::class)
class ControlApplication

fun main(args: Array<String>) { runApplication<ControlApplication>(*args) }
