package dev.autohelm.control

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

data class TrainingJobRequest(
    val command: String,
    val symbol: String = "",
    val venue: String = "",
    val session: String = "regular",
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endTime: LocalTime = LocalTime.of(15, 30),
    val version: String = "",
) {
    fun arguments(): List<String> {
        require(command in setOf("collect", "fit", "promote-dev"))
        if (command == "promote-dev") {
            require(version.matches(Regex("[A-Za-z0-9._-]{6,100}")))
            return listOf("--training.command=promote", "--training.version=$version",
                "--training.target-environment=dev")
        }
        require(symbol.matches(Regex("[0-9A-Z]{6}")) && venue in setOf("KRX", "NXT"))
        require(session.matches(Regex("[A-Za-z0-9._-]{1,40}")))
        val start = requireNotNull(from)
        val end = requireNotNull(to)
        require(!start.isAfter(end) && ChronoUnit.DAYS.between(start, end) <= 366)
        require(startTime.isBefore(endTime))
        return listOf("--training.command=$command", "--training.symbol=$symbol", "--training.venue=$venue",
            "--training.session=$session", "--training.from=$start", "--training.to=$end",
            "--training.start-time=$startTime", "--training.end-time=$endTime")
    }
}

data class TrainingJob(
    val id: String,
    val command: String,
    val symbol: String,
    val venue: String,
    val state: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val pid: Long? = null,
    val exitCode: Int? = null,
)

/** Starts one bounded offline batch at a time. Child arguments are validated and never passed to a shell. */
@Component
class TrainingJobs(private val properties: ControlProperties, private val mapper: ObjectMapper) {
    private val root: Path get() = properties.dataDir.resolve(properties.environment).resolve("control/jobs")
    private var active: Process? = null

    @Synchronized
    fun start(request: TrainingJobRequest): TrainingJob {
        require(properties.environment == "dev") { "Training actions are available only in dev" }
        val args = request.arguments()
        require(active?.isAlive != true && list().none { it.state == "RUNNING" }) {
            "A training job is already running"
        }
        val jar = properties.trainingJar.toAbsolutePath().normalize()
        require(Files.isRegularFile(jar) && jar.fileName.toString().endsWith(".jar")) {
            "Build the model-training bootJar first"
        }
        val id = UUID.randomUUID().toString()
        Files.createDirectories(root)
        val log = root.resolve("$id.log")
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", jar.toString(), "--training.environment=dev",
            "--training.data-dir=${properties.dataDir.toAbsolutePath().normalize()}") + args
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
        val env = builder.environment()
        val trainingKey = env["KIS_TRAINING_APP_KEY"]
        val trainingSecret = env["KIS_TRAINING_APP_SECRET"]
        env.clear()
        if (trainingKey != null) env["KIS_TRAINING_APP_KEY"] = trainingKey
        if (trainingSecret != null) env["KIS_TRAINING_APP_SECRET"] = trainingSecret
        val process = builder.start()
        active = process
        val job = TrainingJob(id, request.command, request.symbol, request.venue,
            "RUNNING", Instant.now(), pid = process.pid())
        write(job)
        Thread({
            val exitCode = process.waitFor()
            write(job.copy(state = if (exitCode == 0) "SUCCEEDED" else "FAILED",
                endedAt = Instant.now(), exitCode = exitCode))
            synchronized(this) { if (active === process) active = null }
        }, "training-job-$id").apply { isDaemon = true }.start()
        return job
    }

    fun list(): List<TrainingJob> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.map { path ->
                val job = mapper.readValue<TrainingJob>(Files.readAllBytes(path))
                if (job.state == "RUNNING" && job.pid != null &&
                    !ProcessHandle.of(job.pid).map(ProcessHandle::isAlive).orElse(false))
                    job.copy(state = "UNKNOWN_RESULT") else job
            }.toList().sortedByDescending(TrainingJob::startedAt).take(20)
        }
    }

    private fun write(job: TrainingJob) {
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "job-", ".tmp")
        try {
            Files.write(temporary, mapper.writeValueAsBytes(job))
            try { Files.move(temporary, root.resolve("${job.id}.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, root.resolve("${job.id}.json"), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}
