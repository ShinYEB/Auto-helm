package dev.autohelm.prediction

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(PredictionProperties::class)
class PredictionApplication

fun main(args: Array<String>) { runApplication<PredictionApplication>(*args) }
