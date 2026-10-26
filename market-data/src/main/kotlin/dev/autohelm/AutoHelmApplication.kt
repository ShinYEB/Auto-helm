package dev.autohelm

import dev.autohelm.market.MarketProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(MarketProperties::class)
class AutoHelmApplication

fun main(args: Array<String>) {
    runApplication<AutoHelmApplication>(*args)
}
