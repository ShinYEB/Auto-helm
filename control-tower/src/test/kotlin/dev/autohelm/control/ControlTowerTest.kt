package dev.autohelm.control

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ControlTowerTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).addModule(JavaTimeModule()).build()
    private val props get() = ControlProperties(dataDir = directory)

    @Test
    fun `training request validates market and date before process launch`() {
        val valid = TrainingJobRequest("collect", "005930", "KRX",
            from = LocalDate.parse("2026-10-20"), to = LocalDate.parse("2026-10-24"))
        assertThat(valid.arguments()).contains("--training.symbol=005930", "--training.venue=KRX")
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(symbol = "005930;rm").arguments()
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(to = LocalDate.parse("2027-12-31")).arguments()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainingJobRequest("promote-dev", version = "../prd").arguments()
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainingJobs(props.copy(environment = "prd"), mapper).start(valid)
        }
    }

    @Test
    fun `old outputs do not claim a service is running`() {
        val clock = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC)
        val event = directory.resolve("dev/events/health.json")
        Files.createDirectories(event.parent)
        Files.writeString(event, """{"pid":${ProcessHandle.current().pid()},"running":true,"heartbeatAt":"2026-10-26T11:00:00Z"}""")
        val states = ServiceStatus(props, mapper, clock).snapshot().associateBy(ServiceObservation::service)
        assertThat(states.getValue("event-data").state).isEqualTo("STALE")
        assertThat(states.getValue("price-prediction").state).isEqualTo("NO_DATA")
    }

    @Test
    fun `dashboard reads fixed Prometheus queries and handles unavailability`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/api/v1/") { exchange ->
            requests++
            val range = exchange.requestURI.path.endsWith("query_range")
            val body = if (range)
                """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"venue":"KRX","source":"DART"},"values":[[1793016000,"2"],[1793016060,"3"]]}]}}"""
            else """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1793016000,"1"]}]}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val dashboard = DashboardMetrics(props.copy(prometheusUrl = URI.create("http://127.0.0.1:${server.address.port}")),
            mapper, Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC))
        try {
            val result = dashboard.snapshot("6h")
            assertThat(result.available).isTrue()
            assertThat(result.stats).hasSize(8)
            assertThat(result.charts).hasSize(6)
            assertThat(result.charts.first().series.first().points.map(DashboardPoint::value)).containsExactly(2.0, 3.0)
            assertThat(requests).isEqualTo(14)
            assertThrows(IllegalArgumentException::class.java) { dashboard.snapshot("5years") }
        } finally { server.stop(0) }
        assertThat(dashboard.snapshot("6h").available).isFalse()
    }
}
