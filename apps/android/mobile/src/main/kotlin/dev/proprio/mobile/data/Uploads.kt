package dev.proprio.mobile.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.proprio.backend.SessionFiles
import dev.proprio.backend.UploadCore
import dev.proprio.backend.UploadOutcome
import dev.proprio.backend.UploadBackoff
import dev.proprio.backend.UploadQueue
import java.io.File
import java.util.concurrent.TimeUnit

/** Gives the upload core the session files held by [SessionStore] (the zip is read, never written). */
class StoreSessionFiles(private val store: SessionStore) : SessionFiles {
    override fun zip(sessionId: String): File? = store.zipFile(sessionId).takeIf { it.isFile }

    override fun labelsText(sessionId: String): String? =
        File(store.dir(sessionId), dev.proprio.protocol.FILE_LABELS).takeIf { it.isFile }?.readText(Charsets.UTF_8)
}

/** What the worker calls; separated from [UploadCore] so the worker is testable without a network. */
interface UploadRunner {
    suspend fun upload(sessionId: String): UploadOutcome
}

class CoreUploadRunner(private val core: UploadCore) : UploadRunner {
    override suspend fun upload(sessionId: String) = core.upload(sessionId)
}

/** Queues upload work for a session; the real one is backed by WorkManager. */
typealias UploadScheduler = UploadQueue

/**
 * Persistent, network-constrained, exponentially backed-off upload job per session. Transient failures retry forever
 * (WorkManager caps the wait at 5 hours); app start, sign-in and "Upload now" call [enqueue] again, which restarts a
 * job that is merely waiting in backoff. While a worker is actually running, a new request is appended so labels
 * edited mid-upload are picked up by a follow-up run, and the running upload is never cancelled. A run with nothing
 * left to send makes no network call.
 */
class WorkUploadScheduler(private val workManager: WorkManager) : UploadScheduler {
    private fun name(sessionId: String) = "upload-$sessionId"

    override fun isRunning(sessionId: String): Boolean =
        workManager.getWorkInfosForUniqueWork(name(sessionId)).get().any { it.state == WorkInfo.State.RUNNING }

    override fun enqueue(sessionId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(UploadWorker.KEY_SESSION_ID, sessionId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, UploadBackoff.INITIAL_MS, TimeUnit.MILLISECONDS)
            .build()
        // REPLACE cancels a job waiting in backoff (or blocked behind one) so it runs now; never replace a running one.
        val policy = if (isRunning(sessionId)) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        workManager.enqueueUniqueWork(name(sessionId), policy, request)
    }
}

class UploadWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: UploadRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        return when (val outcome = runner.upload(id)) {
            UploadOutcome.Done -> Result.success()
            // Transient (offline, server down or restarting, 5xx): keep going with backoff, no attempt cap. The core
            // has already stored the error and the next attempt time.
            is UploadOutcome.Retry -> Result.retry()
            // Nothing a retry can fix; the state already carries the reason. Signing in re-queues pending sessions.
            is UploadOutcome.Failed, UploadOutcome.AuthRequired -> Result.failure()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
    }
}

class UploadWorkerFactory(private val runner: () -> UploadRunner) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == UploadWorker::class.java.name) UploadWorker(appContext, workerParameters, runner()) else null
}
