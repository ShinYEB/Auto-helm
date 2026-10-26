package dev.autohelm.control

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ControlTowerTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).addModule(JavaTimeModule()).build()
    private val token = "test-control-token-more-than-32-characters"
    private val props get() = ControlProperties(dataDir = directory, token = token)

    @Test
    fun `api rejects requests without operator token`() {
        val filter = ApiTokenFilter(props)
        val request = MockHttpServletRequest("POST", "/api/training/jobs")
        val response = MockHttpServletResponse()
        var called = false
        val chain = FilterChain { _, _ -> called = true }
        filter.doFilter(request, response, chain)
        assertThat(response.status).isEqualTo(401)
        assertThat(called).isFalse()
        request.addHeader("X-Control-Token", token)
        val accepted = MockHttpServletResponse()
        filter.doFilter(request, accepted, chain)
        assertThat(accepted.status).isEqualTo(200)
        assertThat(called).isTrue()
        assertThat(accepted.getHeader("Cache-Control")).isEqualTo("no-store")
    }

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
}
