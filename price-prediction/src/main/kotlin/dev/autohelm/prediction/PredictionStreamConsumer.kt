package dev.autohelm.prediction

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class PredictionStreamConsumer(
    private val properties: PredictionProperties,
    private val mapper: ObjectMapper,
    private val redis: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val stream = "autohelm:${properties.environment}:market:minute-bars:v1"
    private val group = "price-prediction-v1"
    private val consumerName = "price-prediction-single"
    private val journal by lazy { PredictionJournal(properties, mapper) }
    private val engine by lazy { BaselinePredictionEngine(properties, journal) }
    @Volatile private var running = false
    private var groupReady = false
    private var worker: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.enabled || running) return
        running = true
        worker = Thread(::loop, "price-prediction-consumer").also { it.start() }
    }

    private fun loop() {
        while (running) {
            try {
                if (!redis.hasKey(stream)) {
                    groupReady = false
                    Thread.sleep(2000)
                    continue
                }
                ensureGroup()
                val pending = read(ReadOffset.from("0"), false)
                val records = pending.ifEmpty { read(ReadOffset.lastConsumed(), true) }
                for (record in records) processAndAck(record)
            } catch (_: InterruptedException) {
                break
            } catch (ex: Exception) {
                groupReady = false
                log.warn("Prediction Stream consumer delayed: {}", ex.javaClass.simpleName)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun ensureGroup() {
        if (groupReady) return
        try {
            redis.opsForStream<String, String>().createGroup(stream, ReadOffset.from("0-0"), group)
        } catch (ex: Exception) {
            if ("BUSYGROUP" !in (ex.message ?: "")) throw ex
        }
        groupReady = true
    }

    private fun read(offset: ReadOffset, blocking: Boolean): List<MapRecord<String, String, String>> {
        val options = StreamReadOptions.empty().count(100).let {
            if (blocking) it.block(Duration.ofSeconds(2)) else it
        }
        return redis.opsForStream<String, String>().read(
            Consumer.from(group, consumerName), options, StreamOffset.create(stream, offset)
        ) ?: emptyList()
    }

    private fun processAndAck(record: MapRecord<String, String, String>) {
        val fields = record.value
        try {
            require(fields["schema"] == "MinuteBar.v1")
            val bar = mapper.readValue<InputBar>(requireNotNull(fields["body"]))
            require(fields["barId"] == bar.id && fields["environment"] == bar.environment)
            require(fields["symbol"] == bar.symbol && fields["venue"] == bar.venue)
            engine.accept(bar)
        } catch (ex: IllegalArgumentException) {
            journal.reject(RejectedBar(record.id.value, ex.javaClass.simpleName, Instant.now()))
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            journal.reject(RejectedBar(record.id.value, "INVALID_JSON", Instant.now()))
        }
        redis.opsForStream<String, String>().acknowledge(stream, group, record.id)
    }

    @PreDestroy
    fun stop() {
        running = false
        worker?.interrupt()
        worker?.join(3000)
    }
}
