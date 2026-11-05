package dev.proprio.backend

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the UI shows per session. [UPLOADING] means a request is in flight right now; after a retryable failure the
 * phase is [WAITING_TO_RETRY] (with the error and the next attempt time) until the next attempt starts.
 */
@Serializable
enum class UploadPhase { PENDING, UPLOADING, WAITING_TO_RETRY, UPLOADED, ANALYZED, FAILED }

/**
 * Persisted per-session upload progress (`upload.json` next to the session's zip), so it survives process death
 * and a finished upload is never repeated.
 */
@Serializable
data class UploadState(
    val phase: UploadPhase = UploadPhase.PENDING,
    /** The raw zip is on the server and `complete` was accepted: never upload it again. */
    val remoteUploaded: Boolean = false,
    /** SHA-256 of the labels.json last stored on the server; a different hash means labels need re-sending. */
    val labelsHash: String? = null,
    val attempts: Int = 0,
    /** Last upload error, or the server's failure reason when analysis failed. */
    val error: String? = null,
    val labelsError: String? = null,
    val updatedAtMs: Long = 0,
    /** Estimated wall-clock time of the next automatic attempt while [UploadPhase.WAITING_TO_RETRY]. */
    val nextAttemptAtMs: Long? = null,
) {
    /** The upload worked but the server's analysis did not. */
    val analysisFailed: Boolean get() = phase == UploadPhase.FAILED && remoteUploaded
}

interface UploadStateStore {
    val changes: StateFlow<Int>
    fun get(sessionId: String): UploadState?
    fun update(sessionId: String, transform: (UploadState) -> UploadState): UploadState
}

class FileUploadStateStore(private val dirFor: (String) -> File) : UploadStateStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun file(id: String) = File(dirFor(id), "upload.json")

    override fun get(sessionId: String): UploadState? = try {
        file(sessionId).takeIf { it.isFile }?.let { json.decodeFromString(UploadState.serializer(), it.readText()) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    override fun update(sessionId: String, transform: (UploadState) -> UploadState): UploadState {
        val next = transform(get(sessionId) ?: UploadState())
        val target = file(sessionId)
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile!!, target.name + ".tmp")
        tmp.writeText(json.encodeToString(UploadState.serializer(), next))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        _changes.value++
        return next
    }
}

class MemoryUploadStateStore : UploadStateStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, UploadState>()
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()
    override fun get(sessionId: String) = map[sessionId]

    @Synchronized
    override fun update(sessionId: String, transform: (UploadState) -> UploadState): UploadState {
        val next = transform(map[sessionId] ?: UploadState())
        map[sessionId] = next
        _changes.value++
        return next
    }
}

/** Backoff shared by the scheduler (WorkManager exponential policy) and the "next attempt" estimate shown to the user. */
object UploadBackoff {
    const val INITIAL_MS = 30_000L

    /** WorkManager never waits longer than 5 hours between attempts. */
    const val MAX_MS = 5 * 60 * 60 * 1000L

    /** Delay after the [failedAttempts]-th consecutive failure (1-based). */
    fun delayMs(failedAttempts: Int): Long {
        val shift = (failedAttempts - 1).coerceIn(0, 20)
        return (INITIAL_MS shl shift).coerceAtMost(MAX_MS)
    }
}

/** The background job queue as the upload logic sees it. */
interface UploadQueue {
    /** Starts an upload run for the session as soon as possible (replacing a job that is merely waiting). */
    fun enqueue(sessionId: String)

    /** True only while a worker for the session is executing right now. */
    fun isRunning(sessionId: String): Boolean
}

object UploadRecovery {
    /**
     * After a process restart nothing can be uploading unless the queue says a worker is running. A session left in
     * [UploadPhase.UPLOADING] by a killed or cancelled worker is shown as waiting to retry instead of uploading forever.
     */
    fun markStale(ids: Collection<String>, states: UploadStateStore, queue: UploadQueue, nowMs: Long = System.currentTimeMillis()) {
        for (id in ids) {
            val st = states.get(id) ?: continue
            if (st.remoteUploaded || st.phase != UploadPhase.UPLOADING || queue.isRunning(id)) continue
            states.update(id) {
                it.copy(
                    phase = UploadPhase.WAITING_TO_RETRY,
                    error = it.error ?: "Interrupted before it finished.",
                    nextAttemptAtMs = null,
                    updatedAtMs = nowMs,
                )
            }
        }
    }

    /** Manual "Upload now": clears the failure bookkeeping and starts a run, unless one is genuinely running. */
    fun uploadNow(id: String, states: UploadStateStore, queue: UploadQueue, nowMs: Long = System.currentTimeMillis()) {
        if (queue.isRunning(id)) return
        states.update(id) {
            it.copy(
                phase = if (it.remoteUploaded) it.phase else UploadPhase.PENDING,
                attempts = 0, error = null, nextAttemptAtMs = null, updatedAtMs = nowMs,
            )
        }
        queue.enqueue(id)
    }

    /** App start / sign-in: fix stale states, then (re)queue every session so waiting backoffs restart at once. */
    fun recover(ids: Collection<String>, states: UploadStateStore, queue: UploadQueue, nowMs: Long = System.currentTimeMillis()) {
        markStale(ids, states, queue, nowMs)
        ids.forEach(queue::enqueue)
    }
}
