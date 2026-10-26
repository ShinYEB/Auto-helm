package dev.autohelm.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.exp

data class ModelHead(
    val intercept: Double,
    val slope: Double,
    val trainCount: Int,
    val validationCount: Int,
    val validationMae: Double,
    val baselineMae: Double,
) {
    init {
        require(trainCount > 0 && validationCount > 0)
        require(listOf(intercept, slope, validationMae, baselineMae).all(Double::isFinite))
        require(validationMae >= 0 && baselineMae >= 0)
    }
}

data class ModelWeights(val heads: Map<String, ModelHead>) {
    init { require(heads.keys == setOf("NEXT_1M", "AFTER_1H")) }
}

data class ModelManifest(
    val schema: String = "ModelBundle.v1",
    val version: String,
    val symbol: String,
    val venue: String,
    val session: String,
    val featureNames: List<String> = listOf("logReturn1m"),
    val targetNames: List<String> = listOf("NEXT_1M", "AFTER_1H"),
    val datasetSha256: String,
    val weightsSha256: String,
    val createdAt: Instant,
    val eligible: Boolean,
) {
    init {
        require(schema == "ModelBundle.v1" && version.matches(Regex("[A-Za-z0-9._-]{6,100}")))
        require(symbol.matches(Regex("[0-9A-Z]{6}")) && venue in setOf("KRX", "NXT"))
        require(session.matches(Regex("[A-Za-z0-9._-]{1,40}")))
        require(featureNames == listOf("logReturn1m"))
        require(targetNames == listOf("NEXT_1M", "AFTER_1H"))
        require(datasetSha256.matches(Regex("[0-9a-f]{64}")))
        require(weightsSha256.matches(Regex("[0-9a-f]{64}")))
    }
    @get:JsonIgnore val marketKey: String get() = "$symbol:$venue:$session"
}

data class ModelBundle(val manifest: ModelManifest, val weights: ModelWeights, val sha256: String) {
    fun predict(target: String, close: Long, previousClose: Long): Long? {
        if (close <= 0 || previousClose <= 0) return null
        val head = weights.heads[target] ?: return null
        val feature = kotlin.math.ln(close.toDouble() / previousClose)
        val predictedReturn = head.intercept + head.slope * feature
        if (!predictedReturn.isFinite() || predictedReturn !in -0.2..0.2) return null
        val price = close * exp(predictedReturn)
        return if (price.isFinite() && price >= 1 && price <= Long.MAX_VALUE) price.toLong() else null
    }
}

data class ActiveModelRef(val version: String, val sha256: String)
data class ActiveModels(val schema: String = "ActiveModels.v1", val models: Map<String, ActiveModelRef>)

class ModelBundleIO(private val mapper: ObjectMapper) {
    fun write(directory: Path, manifest: ModelManifest, weights: ModelWeights): ModelBundle {
        require(!Files.exists(directory)) { "Model bundle already exists" }
        val weightsBytes = mapper.writeValueAsBytes(weights)
        require(sha256(weightsBytes) == manifest.weightsSha256)
        Files.createDirectories(directory.parent)
        val temporary = Files.createTempDirectory(directory.parent, "bundle-")
        try {
            Files.write(temporary.resolve("weights.json"), weightsBytes)
            Files.write(temporary.resolve("manifest.json"), mapper.writeValueAsBytes(manifest))
            try { Files.move(temporary, directory, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, directory) }
        } finally {
            if (Files.exists(temporary)) {
                Files.deleteIfExists(temporary.resolve("weights.json"))
                Files.deleteIfExists(temporary.resolve("manifest.json"))
                Files.deleteIfExists(temporary)
            }
        }
        return read(directory)
    }

    fun read(directory: Path): ModelBundle {
        val manifestBytes = Files.readAllBytes(directory.resolve("manifest.json"))
        val weightsBytes = Files.readAllBytes(directory.resolve("weights.json"))
        val manifest = mapper.readValue<ModelManifest>(manifestBytes)
        require(sha256(weightsBytes) == manifest.weightsSha256) { "Model weights checksum mismatch" }
        val weights = mapper.readValue<ModelWeights>(weightsBytes)
        require(weights.heads.values.all { it.validationCount > 0 })
        return ModelBundle(manifest, weights, sha256(manifestBytes + weightsBytes))
    }

    fun loadActive(root: Path): Map<String, ModelBundle> {
        val pointer = root.resolve("active.json")
        if (!Files.exists(pointer)) return emptyMap()
        val active = mapper.readValue<ActiveModels>(Files.readAllBytes(pointer))
        require(active.schema == "ActiveModels.v1")
        return active.models.mapValues { (key, ref) ->
            require(ref.version.matches(Regex("[A-Za-z0-9._-]{6,100}")))
            val bundle = read(root.resolve("bundles").resolve(ref.version))
            require(bundle.sha256 == ref.sha256 && bundle.manifest.marketKey == key &&
                bundle.manifest.version == ref.version)
            require(bundle.manifest.eligible) { "Ineligible model cannot be active" }
            bundle
        }
    }

    fun activate(root: Path, bundle: ModelBundle) {
        require(bundle.manifest.eligible) { "Model did not beat baseline on both targets" }
        val existing = if (Files.exists(root.resolve("active.json")))
            mapper.readValue<ActiveModels>(Files.readAllBytes(root.resolve("active.json"))) else ActiveModels(models = emptyMap())
        require(existing.schema == "ActiveModels.v1")
        val updated = existing.copy(models = existing.models +
            (bundle.manifest.marketKey to ActiveModelRef(bundle.manifest.version, bundle.sha256)))
        Files.createDirectories(root)
        val temporary = Files.createTempFile(root, "active-", ".tmp")
        try {
            Files.write(temporary, mapper.writeValueAsBytes(updated))
            try {
                Files.move(temporary, root.resolve("active.json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, root.resolve("active.json"), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
