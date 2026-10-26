package dev.autohelm.market

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.bridge.JsonlTailer
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/** Journal outbox: Redis never runs on the WebSocket or bar sealing thread. */
@Component
class MinuteBarStreamPublisher(
    private val properties: MarketProperties,
    private val mapper: ObjectMapper,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = properties.dataDir.resolve(properties.environment).resolve("market")
    private val tailer = JsonlTailer(root.resolve("bars"), root.resolve("bridge/minute-bars.cursor"))
    private val stream = "autohelm:${properties.environment}:market:minute-bars:v1"
    private val readyKey = "autohelm:${properties.environment}:market:minute-bars:v1:published"
    private var rebuilding = false
    @Volatile private var running = false
    private var worker: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.redis.enabled || running) return
        running = true
        worker = Thread(::loop, "minute-bar-stream-publisher").also { it.start() }
    }

    internal fun publishAvailable(): Int {
        if (redis.opsForValue().get(readyKey) != "ready" && !rebuilding) {
            tailer.reset()
            rebuilding = true
        }
        val count = tailer.drain { json ->
            val bar = mapper.readValue<MinuteBar>(json)
            require(bar.environment == properties.environment) { "Wrong environment in bar journal" }
            requireNotNull(redis.opsForStream<String, String>().add(stream, mapOf(
                "schema" to "MinuteBar.v1",
                "barId" to bar.id,
                "environment" to bar.environment,
                "symbol" to bar.symbol,
                "venue" to bar.venue.name,
                "session" to bar.session,
                "minuteStart" to bar.minuteStart.toString(),
                "sealedAt" to bar.sealedAt.toString(),
                "quality" to bar.quality.name,
                "body" to json,
            ))) { "XADD did not return a record ID" }
        }
        redis.opsForValue().set(readyKey, "ready")
        rebuilding = false
        return count
    }

    private fun loop() {
        while (running) {
            try {
                publishAvailable()
                Thread.sleep(properties.redis.pollInterval.toMillis())
            } catch (_: InterruptedException) {
                break
            } catch (ex: Exception) {
                rebuilding = false
                log.warn("Minute bar Redis publish delayed: {}", ex.javaClass.simpleName)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    @PreDestroy
    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(3000)
    }
}
