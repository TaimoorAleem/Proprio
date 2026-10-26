package dev.proprio.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** App-start / sign-in / manual-retry behaviour around states a killed worker left behind. */
class UploadRecoveryTest {
    private class FakeQueue(val running: MutableSet<String> = mutableSetOf()) : UploadQueue {
        val enqueued = mutableListOf<String>()
        override fun enqueue(sessionId: String) {
            enqueued += sessionId
        }
        override fun isRunning(sessionId: String) = sessionId in running
    }

    private val states = MemoryUploadStateStore()

    @Test
    fun staleUploading_withNoRunningWorker_becomesWaitingToRetry_andIsRequeued() {
        states.update("a") { it.copy(phase = UploadPhase.UPLOADING, attempts = 4) } // the owner's upload.json
        val queue = FakeQueue()

        UploadRecovery.recover(listOf("a"), states, queue, nowMs = 5L)

        val st = states.get("a")!!
        assertEquals(UploadPhase.WAITING_TO_RETRY, st.phase)
        assertEquals(4, st.attempts)
        assertEquals("Interrupted before it finished.", st.error)
        assertEquals(listOf("a"), queue.enqueued)
    }

    @Test
    fun uploading_withARunningWorker_isLeftAlone() {
        states.update("a") { it.copy(phase = UploadPhase.UPLOADING) }

        UploadRecovery.markStale(listOf("a"), states, FakeQueue(mutableSetOf("a")))

        assertEquals(UploadPhase.UPLOADING, states.get("a")!!.phase)
    }

    @Test
    fun waitingState_keepsItsErrorAcrossRecovery() {
        states.update("a") { it.copy(phase = UploadPhase.WAITING_TO_RETRY, error = "Connection refused") }

        UploadRecovery.markStale(listOf("a"), states, FakeQueue())

        assertEquals("Connection refused", states.get("a")!!.error)
    }

    @Test
    fun finishedUploads_areNotTouched_butStillRequeuedForLabelSync() {
        states.update("a") { it.copy(phase = UploadPhase.ANALYZED, remoteUploaded = true) }
        val queue = FakeQueue()

        UploadRecovery.recover(listOf("a", "unknown"), states, queue)

        assertEquals(UploadPhase.ANALYZED, states.get("a")!!.phase)
        assertNull(states.get("unknown"))
        assertEquals(listOf("a", "unknown"), queue.enqueued)
    }

    @Test
    fun uploadNow_fromAStuckUploading_resetsAndStartsARun() {
        states.update("a") { it.copy(phase = UploadPhase.UPLOADING, attempts = 4, error = "x", nextAttemptAtMs = 9) }
        val queue = FakeQueue()

        UploadRecovery.uploadNow("a", states, queue)

        val st = states.get("a")!!
        assertEquals(UploadPhase.PENDING, st.phase)
        assertEquals(0, st.attempts)
        assertNull(st.error)
        assertNull(st.nextAttemptAtMs)
        assertEquals(listOf("a"), queue.enqueued)
    }

    @Test
    fun uploadNow_worksFromEveryNonDoneState() {
        for (phase in listOf(UploadPhase.PENDING, UploadPhase.UPLOADING, UploadPhase.WAITING_TO_RETRY, UploadPhase.FAILED)) {
            val s = MemoryUploadStateStore()
            s.update("a") { it.copy(phase = phase, error = "e") }
            val queue = FakeQueue()

            UploadRecovery.uploadNow("a", s, queue)

            assertEquals(listOf("a"), queue.enqueued, phase.name)
            assertEquals(UploadPhase.PENDING, s.get("a")!!.phase, phase.name)
        }
    }

    @Test
    fun uploadNow_whileAWorkerReallyRuns_doesNotDisturbIt() {
        states.update("a") { it.copy(phase = UploadPhase.UPLOADING) }
        val queue = FakeQueue(mutableSetOf("a"))

        UploadRecovery.uploadNow("a", states, queue)

        assertTrue(queue.enqueued.isEmpty())
        assertEquals(UploadPhase.UPLOADING, states.get("a")!!.phase)
    }
}
