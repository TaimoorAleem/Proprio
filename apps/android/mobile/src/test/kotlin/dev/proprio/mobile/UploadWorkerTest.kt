package dev.proprio.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.proprio.backend.UploadOutcome
import dev.proprio.mobile.data.UploadRunner
import dev.proprio.mobile.data.UploadWorker
import dev.proprio.mobile.data.UploadWorkerFactory
import dev.proprio.mobile.data.WorkUploadScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The WorkManager job around the upload core: result mapping, retry cap, constraints and de-duplication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeRunner(var outcome: UploadOutcome) : UploadRunner {
        val uploaded = mutableListOf<String>()
        override suspend fun upload(sessionId: String): UploadOutcome {
            uploaded += sessionId
            return outcome
        }
    }

    private fun run(runner: FakeRunner, attempt: Int = 0): Result = runBlocking {
        TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(Data.Builder().putString(UploadWorker.KEY_SESSION_ID, "sess-1").build())
            .setWorkerFactory(UploadWorkerFactory { runner })
            .setRunAttemptCount(attempt)
            .build()
            .doWork()
    }

    /** The worker runs on WorkManager's executor: wait until it is no longer RUNNING. */
    private fun awaitSettled(wm: WorkManager, id: java.util.UUID): WorkInfo.State {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val state = wm.getWorkInfoById(id).get()!!.state
            if (state != WorkInfo.State.RUNNING) return state
            Thread.sleep(20)
        }
        error("worker still running")
    }

    @Test
    fun done_succeeds_forTheRequestedSession() {
        val runner = FakeRunner(UploadOutcome.Done)
        assertEquals(Result.success(), run(runner))
        assertEquals(listOf("sess-1"), runner.uploaded)
    }

    @Test
    fun transientFailure_isRetriedWithBackoff_withNoAttemptCap() {
        val runner = FakeRunner(UploadOutcome.Retry("offline"))

        for (attempt in listOf(0, 5, 6, 50, 500)) assertEquals(Result.retry(), run(runner, attempt = attempt))
    }

    @Test
    fun permanentFailureAndSignedOut_doNotRetry() {
        assertEquals(Result.failure(), run(FakeRunner(UploadOutcome.Failed("bad"))))
        assertEquals(Result.failure(), run(FakeRunner(UploadOutcome.AuthRequired)))
    }

    @Test
    fun scheduler_queuesNetworkConstrainedUniqueWork_andRunsItOnceOnline() {
        val runner = FakeRunner(UploadOutcome.Done)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(UploadWorkerFactory { runner }).build(),
        )
        val wm = WorkManager.getInstance(context)

        WorkUploadScheduler(wm).enqueue("sess-1")

        val queued = wm.getWorkInfosForUniqueWork("upload-sess-1").get().single()
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)
        assertTrue("nothing runs while offline", runner.uploaded.isEmpty())

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(queued.id)

        assertEquals(WorkInfo.State.SUCCEEDED, awaitSettled(wm, queued.id))
        assertEquals(listOf("sess-1"), runner.uploaded)
    }

    @Test
    fun scheduler_retryOutcome_staysQueuedForBackoff() {
        val runner = FakeRunner(UploadOutcome.Retry("503"))
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(UploadWorkerFactory { runner }).build(),
        )
        val wm = WorkManager.getInstance(context)
        WorkUploadScheduler(wm).enqueue("sess-2")
        val id = wm.getWorkInfosForUniqueWork("upload-sess-2").get().single().id

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)

        assertEquals(WorkInfo.State.ENQUEUED, awaitSettled(wm, id))
        assertEquals(1, wm.getWorkInfoById(id).get()!!.runAttemptCount)
    }

    @Test
    fun requeueingWhileWaitingInBackoff_replacesTheWaitingJob_insteadOfChainingBehindIt() {
        val runner = FakeRunner(UploadOutcome.Retry("503"))
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(UploadWorkerFactory { runner }).build(),
        )
        val wm = WorkManager.getInstance(context)
        val scheduler = WorkUploadScheduler(wm)
        scheduler.enqueue("sess-3")
        val first = wm.getWorkInfosForUniqueWork("upload-sess-3").get().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first)
        assertEquals(WorkInfo.State.ENQUEUED, awaitSettled(wm, first)) // now waiting in backoff
        assertTrue(!scheduler.isRunning("sess-3"))

        scheduler.enqueue("sess-3") // app start / "Upload now"

        val infos = wm.getWorkInfosForUniqueWork("upload-sess-3").get()
        assertTrue("old waiting job is replaced, not chained behind", infos.none { it.id == first })
        val fresh = infos.single()
        assertEquals(0, fresh.runAttemptCount)
        assertEquals(WorkInfo.State.ENQUEUED, fresh.state)
    }
}
