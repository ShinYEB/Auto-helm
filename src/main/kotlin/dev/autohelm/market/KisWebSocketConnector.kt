package dev.autohelm.market

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Component
class KisWebSocketConnector(
    private val properties: MarketProperties,
    private val mapper: ObjectMapper,
    private val worker: MarketWorker,
) {
    private class SubscriptionRejected(message: String) : IllegalStateException(message)

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null
    private var thread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!properties.enabled || running) return
        worker.start()
        running = true
        // A non-daemon thread keeps the non-web Spring Boot application alive.
        thread = Thread(::connectLoop, "kis-websocket").also { it.start() }
    }

    private fun approvalKey(): String {
        val request = HttpRequest.newBuilder(URI.create("${properties.restUrl.trimEnd('/')}/oauth2/Approval"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf(
                "grant_type" to "client_credentials", "appkey" to properties.appKey,
                "secretkey" to properties.appSecret,
            )))).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "KIS approval request failed: HTTP ${response.statusCode()}" }
        return mapper.readTree(response.body()).path("approval_key").asText().ifBlank { error("No KIS approval key") }
    }

    private fun connectLoop() {
        var waitSeconds = 1L
        var key: String? = null
        while (running) {
            try {
                if (key == null) key = approvalKey()
                val listener = KisListener(key)
                val ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(properties.wsUrl), listener).join()
                socket = ws
                listener.closed.join()
                waitSeconds = 1
            } catch (ex: Exception) {
                if (running) log.warn("KIS WebSocket connection failed: {}", ex.message)
                if (ex is SubscriptionRejected || ex.cause is SubscriptionRejected) key = null
            } finally {
                socket = null
                worker.connectionLost(Instant.now())
            }
            if (running) {
                try { Thread.sleep(waitSeconds * 1000) } catch (_: InterruptedException) { break }
                waitSeconds = (waitSeconds * 2).coerceAtMost(30)
            }
        }
    }

    private inner class KisListener(private val approvalKey: String) : WebSocket.Listener {
        val closed = CompletableFuture<Unit>()
        private val epoch = UUID.randomUUID().toString()
        private val sequence = AtomicLong()
        private val text = StringBuilder()
        private val expected = properties.symbols.flatMap { symbol ->
            listOf("H0STCNT0:$symbol", "H0NXCNT0:$symbol")
        }.toSet()
        private val confirmed = ConcurrentHashMap.newKeySet<String>()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
            worker.subscriptionStarted(Instant.now())
            var sent: CompletableFuture<WebSocket> = CompletableFuture.completedFuture(webSocket)
            for (item in expected) {
                val (trId, symbol) = item.split(':', limit = 2)
                val message = mapOf(
                    "header" to mapOf("approval_key" to approvalKey, "custtype" to "P", "tr_type" to "1", "content-type" to "utf-8"),
                    "body" to mapOf("input" to mapOf("tr_id" to trId, "tr_key" to symbol)),
                )
                sent = sent.thenCompose { webSocket.sendText(mapper.writeValueAsString(message), true) }
            }
            sent.exceptionally { error ->
                worker.connectionLost(Instant.now())
                closed.completeExceptionally(error)
                null
            }
            CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute {
                if (!closed.isDone && !confirmed.containsAll(expected)) {
                    closed.completeExceptionally(IllegalStateException("KIS subscription confirmation timed out"))
                    webSocket.abort()
                }
            }
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            text.append(data)
            if (last) {
                val message = text.toString()
                text.setLength(0)
                try {
                    if (message.startsWith("0|")) {
                        worker.offer(RawFrame("$epoch:${sequence.incrementAndGet()}", Instant.now(), message))
                    } else if (message.startsWith("1|")) {
                        worker.connectionLost(Instant.now()) // Unexpected encrypted market data: fail closed.
                        closed.completeExceptionally(IllegalStateException("Encrypted trade frame"))
                        webSocket.abort()
                    } else {
                        handleSystem(webSocket, message)
                    }
                } catch (ex: Exception) {
                    log.warn("KIS system frame rejected: {}", ex.message)
                    worker.connectionLost(Instant.now())
                    closed.completeExceptionally(ex)
                    webSocket.abort()
                }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        private fun handleSystem(webSocket: WebSocket, message: String) {
            val node = mapper.readTree(message)
            val trId = node.path("header").path("tr_id").asText()
            if (trId == "PINGPONG") {
                webSocket.sendPong(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)))
                return
            }
            val symbol = node.path("header").path("tr_key").asText()
            val item = "$trId:$symbol"
            if (item !in expected) return
            if (node.path("body").path("rt_cd").asText() == "0") {
                confirmed += item
                worker.subscriptionConfirmed(symbol, if (trId == "H0STCNT0") Venue.KRX else Venue.NXT, Instant.now())
                if (confirmed.containsAll(expected)) {
                    worker.connectionRestored(Instant.now())
                    log.info("KIS subscriptions confirmed: {}", confirmed.size)
                }
            } else {
                val reason = node.path("body").path("msg1").asText().replace(approvalKey, "[redacted]").take(160)
                log.warn("KIS subscription rejected for {}: code={}, reason={}",
                    item, node.path("body").path("msg_cd").asText(), reason)
                worker.connectionLost(Instant.now())
                closed.completeExceptionally(SubscriptionRejected("KIS subscription rejected"))
                webSocket.abort()
            }
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            if (running) log.info("KIS WebSocket closed: status={}", statusCode)
            worker.connectionLost(Instant.now())
            closed.complete(Unit)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            worker.connectionLost(Instant.now())
            closed.completeExceptionally(error)
        }
    }

    @PreDestroy
    fun stop() {
        running = false
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
        thread?.interrupt()
        thread?.join(3000)
    }
}
