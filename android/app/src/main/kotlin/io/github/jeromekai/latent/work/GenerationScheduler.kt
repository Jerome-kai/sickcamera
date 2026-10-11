package io.github.jeromekai.latent.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.jeromekai.latent.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Hands jobs to WorkManager. The job files are the source of truth, as the
 * camera's queue directory was; WorkManager only decides when each runs.
 *
 * Every attempt waits for a network connection. The camera retried on a
 * timer whether or not it was online, burning attempts while the Wi-Fi was
 * down; here an offline phone simply waits.
 */
class GenerationScheduler(private val context: Context, private val container: AppContainer) {
    companion object {
        const val TAG = "generation"
        const val KEY_JOB_ID = "job_id"
        fun uniqueName(jobId: String) = "generation-$jobId"
    }

    private val workManager get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Run [jobId] after [delaySeconds]. Called from inside a running attempt
     * to schedule the next one, so it APPENDS: replacing would cancel the
     * very worker that is asking.
     */
    fun schedule(jobId: String, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(workDataOf(KEY_JOB_ID to jobId))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(jobId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Make sure every pending job file has work behind it. */
    fun resumePending() {
        for (job in container.jobs.all()) {
            if (job.failed) continue
            val request = OneTimeWorkRequestBuilder<GenerationWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to job.id))
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(uniqueName(job.id), ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Put a given-up job back in the queue from scratch. */
    fun retry(jobId: String) {
        val job = container.jobs.load(jobId) ?: return
        container.jobs.save(job.copy(failed = false, attempts = 0, lastError = null))
        container.refreshQueue()
        workManager.enqueueUniqueWork(
            uniqueName(jobId),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<GenerationWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .setConstraints(constraints)
                .addTag(TAG)
                .build(),
        )
    }

    /** Drop a job for good. Its photo stays in captures. */
    fun dismiss(jobId: String) {
        workManager.cancelUniqueWork(uniqueName(jobId))
        container.jobs.delete(jobId)
        container.refreshQueue()
    }
}
