package dev.autohelm.event

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(EventProperties::class)
class EventDataApplication

fun main(args: Array<String>) {
    runApplication<EventDataApplication>(*args)
}
