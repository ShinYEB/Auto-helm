package dev.autohelm.control

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class ControlSnapshot(
    val environment: String,
    val at: Instant,
    val services: List<ServiceObservation>,
    val jobs: List<TrainingJob>,
)

@RestController
@RequestMapping("/api")
class ControlApi(
    private val properties: ControlProperties,
    private val status: ServiceStatus,
    private val jobs: TrainingJobs,
    private val dashboard: DashboardMetrics,
) {
    @GetMapping("/snapshot")
    fun snapshot() = ControlSnapshot(properties.environment, Instant.now(), status.snapshot(), jobs.list())

    @GetMapping("/dashboard")
    fun dashboard(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "6h") range: String) =
        dashboard.snapshot(range)

    @PostMapping("/training/jobs")
    fun start(@RequestBody request: TrainingJobRequest): ResponseEntity<TrainingJob> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.start(request))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "Invalid request")))
}
