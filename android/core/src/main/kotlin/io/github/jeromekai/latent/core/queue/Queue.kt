package io.github.jeromekai.latent.core.queue

import io.github.jeromekai.latent.core.model.ImageApi
import io.github.jeromekai.latent.core.model.Normalize
import io.github.jeromekai.latent.core.store.JsonFiles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.min

/**
 * One photo waiting to be turned into an image. Everything that decides what
 * the result looks like is captured when the shutter fires -- including the
 * model -- so turning the model dial afterwards does not change a photo
 * already in the queue. (The camera's docs promised that; its code actually
 * read the model at generation time.)
 */
data class GenerationJob(
    val id: String,
    val createdAt: String,
    val promptId: String,
    val promptTitle: String,
    val promptBody: String,
    /** Relative to the app's storage root. */
    val capturePath: String,
    /** Where the result goes, relative to the storage root. */
    val generatedPath: String,
    val referencePaths: List<String> = emptyList(),
    val magicHistoryId: String? = null,
    val model: String,
    val api: ImageApi,
    val size: String,
    val quality: String,
    val outputFormat: String,
    val outputCompression: Int,
    /** "story" for a 9:16 recreation of an existing image. */
    val variant: String? = null,
    val sourceGeneratedPath: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Set once the queue stops retrying; the job then waits to be dismissed. */
    val failed: Boolean = false,
)

/** The camera's retry timing, unchanged: 15s doubling, capped at 15 minutes. */
object RetryPolicy {
    const val BASE_SECONDS = 15L
    const val MAX_SECONDS = 15L * 60L

    fun delaySeconds(attempts: Int): Long {
        val step = (attempts - 1).coerceAtLeast(0).coerceAtMost(30)
        return min(MAX_SECONDS, BASE_SECONDS shl step)
    }

    sealed interface Outcome {
        data class Retry(val attempts: Int, val delaySeconds: Long) : Outcome
        data class GiveUp(val attempts: Int, val reason: String) : Outcome
    }

    /**
     * What to do after a failed attempt. A permanent error (bad key, refused
     * prompt) gives up at once; anything else retries until [maxAttempts],
     * where 0 means forever, as QUEUE_MAX_ATTEMPTS=0 did.
     */
    fun afterFailure(previousAttempts: Int, retryable: Boolean, maxAttempts: Int): Outcome {
        val attempts = previousAttempts + 1
        return when {
            !retryable -> Outcome.GiveUp(attempts, "permanent error")
            maxAttempts > 0 && attempts >= maxAttempts -> Outcome.GiveUp(attempts, "gave up after $attempts attempts")
            else -> Outcome.Retry(attempts, delaySeconds(attempts))
        }
    }
}

/**
 * Port of PersistentJobStore: one JSON file per job, written atomically, so
 * the queue survives the app being killed. WorkManager schedules the work;
 * these files say what the work is.
 */
class JobStore(private val directory: File) {
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    private fun fileFor(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]+")) && id != "." && id != "..") { "bad job id: $id" }
        return File(directory, "$id.json")
    }

    fun save(job: GenerationJob) = synchronized(lock) { JsonFiles.writeAtomic(fileFor(job.id), encode(job)) }

    fun load(id: String): GenerationJob? = synchronized(lock) {
        val file = runCatching { fileFor(id) }.getOrNull() ?: return null
        decode(JsonFiles.readOrNull(file))
    }

    fun delete(id: String) = synchronized(lock) { runCatching { fileFor(id).delete() } }

    /** Every job on disk, oldest first. */
    fun all(): List<GenerationJob> = synchronized(lock) {
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { decode(JsonFiles.readOrNull(it)) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    fun pendingCount(): Int = all().count { !it.failed }

    companion object {
        fun encode(job: GenerationJob): JsonElement = buildJsonObject {
            put("id", job.id)
            put("created_at", job.createdAt)
            put("prompt_button", job.promptId)
            put("prompt_title", job.promptTitle)
            put("prompt_body", job.promptBody)
            put("capture_path", job.capturePath)
            put("generated_path", job.generatedPath)
            put("reference_paths", buildJsonArray { job.referencePaths.forEach { add(JsonPrimitive(it)) } })
            job.magicHistoryId?.let { put("magic_history_id", it) }
            put("model", job.model)
            put("api", job.api.wire)
            put("size", job.size)
            put("quality", job.quality)
            put("output_format", job.outputFormat)
            put("output_compression", job.outputCompression)
            job.variant?.let { put("variant", it) }
            job.sourceGeneratedPath?.let { put("source_generated_path", it) }
            put("attempts", job.attempts)
            job.lastError?.let { put("last_error", it) }
            put("failed", job.failed)
        }

        /** Tolerant: a job missing its essentials is dropped, never thrown. */
        fun decode(raw: JsonElement?): GenerationJob? {
            val obj = raw as? JsonObject ?: return null
            fun s(key: String) = Normalize.text(obj[key]).trim()
            val id = s("id")
            val capture = s("capture_path")
            val generated = s("generated_path")
            val model = s("model")
            if (id.isEmpty() || capture.isEmpty() || generated.isEmpty() || model.isEmpty()) return null
            return GenerationJob(
                id = id,
                createdAt = s("created_at"),
                promptId = s("prompt_button").ifEmpty { "prompt" },
                promptTitle = s("prompt_title").ifEmpty { "Prompt" },
                promptBody = s("prompt_body"),
                capturePath = capture,
                generatedPath = generated,
                referencePaths = (obj["reference_paths"] as? JsonArray).orEmpty()
                    .map { Normalize.text(it).trim() }.filter { it.isNotEmpty() },
                magicHistoryId = s("magic_history_id").ifEmpty { null },
                model = model,
                api = ImageApi.fromWire(s("api")),
                size = s("size").ifEmpty { "1536x1024" },
                quality = s("quality").ifEmpty { "low" },
                outputFormat = s("output_format").ifEmpty { "jpeg" },
                outputCompression = ((obj["output_compression"] as? JsonPrimitive)?.intOrNull ?: 85).coerceIn(0, 100),
                variant = s("variant").ifEmpty { null },
                sourceGeneratedPath = s("source_generated_path").ifEmpty { null },
                attempts = ((obj["attempts"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0),
                lastError = s("last_error").ifEmpty { null },
                failed = (obj["failed"] as? JsonPrimitive)?.content == "true",
            )
        }
    }
}
