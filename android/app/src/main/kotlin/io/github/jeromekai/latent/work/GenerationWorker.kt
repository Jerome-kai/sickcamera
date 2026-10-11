package io.github.jeromekai.latent.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jeromekai.latent.container
import io.github.jeromekai.latent.core.api.EditRequest
import io.github.jeromekai.latent.core.api.ImageGenException
import io.github.jeromekai.latent.core.api.ImageInput
import io.github.jeromekai.latent.core.model.GenerationMetadata
import io.github.jeromekai.latent.core.model.OutputFormat
import io.github.jeromekai.latent.core.queue.GenerationJob
import io.github.jeromekai.latent.core.queue.RetryPolicy
import io.github.jeromekai.latent.core.store.JsonFiles
import io.github.jeromekai.latent.data.Images
import io.github.jeromekai.latent.data.PhoneGallery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/**
 * One attempt at one job: the camera's _generation_worker_loop body.
 *
 * Every outcome returns success() to WorkManager. Retries are scheduled
 * explicitly with the camera's own timing (15s doubling, 15-minute cap),
 * because WorkManager's built-in backoff caps at five hours and would stretch
 * twelve attempts from about three hours to over eight.
 */
class GenerationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private companion object {
        /**
         * The camera ran one generation at a time. A phone can afford a few
         * together, but not unbounded: a burst of twenty shots would trip the
         * provider's rate limit on every one of them.
         */
        val concurrency = Semaphore(3)
    }

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val jobId = inputData.getString(GenerationScheduler.KEY_JOB_ID) ?: return Result.success()
        val job = container.jobs.load(jobId) ?: return Result.success()
        if (job.failed) return Result.success()

        val output = container.gallery.resolve(job.generatedPath)
            ?: return giveUp(job, "The output path is invalid.")

        // A previous attempt may have finished the file and died before
        // tidying up; don't pay for the same image twice.
        if (Images.isUsableImage(output)) {
            finish(job, output, skippedReferences = 0)
            return Result.success()
        }

        val capture = container.gallery.resolve(job.capturePath)?.takeIf { it.isFile }
            ?: return giveUp(job, "The original photo is missing.")

        return try {
            concurrency.withPermit { attempt(job, capture, output) }
            Result.success()
        } catch (error: CancellationException) {
            // WorkManager stopped us (the network dropped). It reschedules on
            // its own; the job file is untouched, so nothing is lost.
            throw error
        } catch (error: ImageGenException) {
            onFailure(job, error.message ?: "Generation failed", error.retryable)
        } catch (error: Exception) {
            onFailure(job, error.message ?: error.javaClass.simpleName, retryable = true)
        }
    }

    private suspend fun attempt(job: GenerationJob, capture: File, output: File) {
        val container = applicationContext.container
        val settings = container.settings.value
        val references = job.referencePaths.mapNotNull { path ->
            container.gallery.resolve(path)?.takeIf { it.isFile }
        }
        val request = withContext(Dispatchers.IO) {
            EditRequest(
                source = ImageInput(capture.readBytes(), capture.name, PhoneGallery.mimeTypeOf(capture)),
                prompt = job.promptBody,
                references = references.map { ImageInput(it.readBytes(), it.name, PhoneGallery.mimeTypeOf(it)) },
                model = job.model,
                api = job.api,
                quality = job.quality,
                size = job.size,
                outputFormat = job.outputFormat,
                outputCompression = job.outputCompression,
                timeoutSeconds = settings.imageTimeoutSeconds,
            )
        }
        val result = container.editor.edit(container.endpoint(), request)

        withContext(Dispatchers.IO) {
            val format = OutputFormat.fromWire(job.outputFormat)
            // /images/edits already returns the requested format; the other
            // modes hand back whatever the model made (usually PNG).
            val bytes = if (result.mimeType == format.mimeType) result.bytes
            else Images.reencode(result.bytes, format, job.outputCompression)
            JsonFiles.writeBytesAtomic(output, bytes)
        }
        finish(job, output, result.skippedReferences)
    }

    private suspend fun finish(job: GenerationJob, output: File, skippedReferences: Int) {
        val container = applicationContext.container
        withContext(Dispatchers.IO) {
            container.gallery.writeMetadata(
                output,
                GenerationMetadata(
                    capturePath = job.capturePath,
                    promptId = job.promptId,
                    promptTitle = job.promptTitle,
                    promptBody = job.promptBody,
                    model = job.model,
                    api = job.api.wire,
                    createdAt = LocalDateTime.now().withNano(0).toString(),
                    magicReferencePath = job.referencePaths.firstOrNull().takeIf { job.magicHistoryId != null },
                    magicHistoryId = job.magicHistoryId,
                    variant = job.variant,
                    sourceGeneratedPath = job.sourceGeneratedPath,
                    referencePaths = job.referencePaths,
                ),
            )
            container.jobs.delete(job.id)
            if (container.settings.value.saveToPhoneGallery) {
                PhoneGallery.saveToPictures(applicationContext, output)
            }
        }
        container.onGenerationComplete()
        val note = if (skippedReferences > 0) " ($skippedReferences reference image(s) not supported by this model)" else ""
        Notifications.ready(applicationContext, job.id, job.promptTitle + note)
    }

    private fun onFailure(job: GenerationJob, message: String, retryable: Boolean): Result {
        val container = applicationContext.container
        return when (val outcome = RetryPolicy.afterFailure(job.attempts, retryable, container.settings.value.maxAttempts)) {
            is RetryPolicy.Outcome.Retry -> {
                container.jobs.save(job.copy(attempts = outcome.attempts, lastError = message))
                container.scheduler.schedule(job.id, outcome.delaySeconds)
                container.refreshQueue()
                Result.success()
            }
            is RetryPolicy.Outcome.GiveUp -> giveUp(job.copy(attempts = outcome.attempts), message)
        }
    }

    /**
     * Stop retrying but keep the record, so the photo can be retried or
     * dismissed from the queue screen. (The camera moved these to
     * data/queue/failed/ for the same reason: nothing vanishes silently.)
     */
    private fun giveUp(job: GenerationJob, message: String): Result {
        val container = applicationContext.container
        container.jobs.save(job.copy(failed = true, lastError = message))
        container.refreshQueue()
        Notifications.failed(applicationContext, job.id, job.promptTitle, message)
        return Result.success()
    }
}
