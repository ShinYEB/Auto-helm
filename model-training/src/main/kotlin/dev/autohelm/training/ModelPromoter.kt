package dev.autohelm.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.autohelm.model.ModelBundle
import dev.autohelm.model.ModelBundleIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

data class PromotionEvidence(
    val bundleSha256: String,
    val devReplayPassed: Boolean,
    val mockTradingPassed: Boolean,
    val approvedAt: Instant,
    val note: String,
)

/** Explicit promotion command; training itself never changes the online active pointer. */
class ModelPromoter(
    private val properties: TrainingProperties,
    private val mapper: ObjectMapper,
    private val io: ModelBundleIO,
) {
    fun promote(): ModelBundle {
        require(properties.environment == "dev")
        val target = properties.targetEnvironment
        require(target in setOf("dev", "prd"))
        val version = properties.version
        require(version.matches(Regex("[A-Za-z0-9._-]{6,100}")))
        val base = properties.dataDir
        val source = if (target == "dev") base.resolve("dev/training/bundles/$version")
                     else base.resolve("dev/models/bundles/$version")
        val bundle = io.read(source)
        require(bundle.manifest.version == version && bundle.manifest.eligible)
        if (target == "prd") {
            val devActive = io.loadActive(base.resolve("dev/models"))[bundle.manifest.marketKey]
            require(devActive?.sha256 == bundle.sha256) { "Model is not active in dev" }
            val evidencePath = requireNotNull(properties.evidenceFile) { "prd promotion requires evidence file" }
            val evidence = mapper.readValue<PromotionEvidence>(Files.readAllBytes(evidencePath))
            require(evidence.bundleSha256 == bundle.sha256 && evidence.devReplayPassed &&
                evidence.mockTradingPassed && evidence.note.isNotBlank()) { "Promotion evidence incomplete" }
        }
        val targetRoot = base.resolve("$target/models")
        val destination = targetRoot.resolve("bundles/$version")
        if (!Files.exists(destination)) copyBundle(source, destination)
        val copied = io.read(destination)
        require(copied.sha256 == bundle.sha256) { "Copied model differs from source" }
        io.activate(targetRoot, copied)
        return copied
    }

    private fun copyBundle(source: Path, destination: Path) {
        Files.createDirectories(destination.parent)
        val tmp = Files.createTempDirectory(destination.parent, "bundle-")
        try {
            Files.copy(source.resolve("manifest.json"), tmp.resolve("manifest.json"))
            Files.copy(source.resolve("weights.json"), tmp.resolve("weights.json"))
            try { Files.move(tmp, destination, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, destination) }
        } finally {
            if (Files.exists(tmp)) {
                Files.deleteIfExists(tmp.resolve("manifest.json"))
                Files.deleteIfExists(tmp.resolve("weights.json"))
                Files.deleteIfExists(tmp)
            }
        }
    }
}
