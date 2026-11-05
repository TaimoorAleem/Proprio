package dev.proprio.wear

import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.LiveBatch
import dev.proprio.protocol.LiveCodec
import dev.proprio.protocol.LiveSegmentKind
import dev.proprio.protocol.LiveSubscribe
import dev.proprio.protocol.SessionKind
import dev.proprio.wear.live.LiveContext
import dev.proprio.wear.live.LiveLease
import dev.proprio.wear.live.LivePreview
import dev.proprio.wear.live.SampleRing
import dev.proprio.wear.sensor.SensorRecorder
import dev.proprio.wear.sensor.TYPE_ACC
import dev.proprio.wear.sensor.TYPE_GYR
import dev.proprio.wear.session.CommandHandler
import dev.proprio.wear.session.RecordingController
import dev.proprio.wear.transfer.TransferManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val G = 9.80665f

@OptIn(ExperimentalCoroutinesApi::class)
class LivePreviewTest {
    private class Sent {
        val batches = mutableListOf<LiveBatch>()
        var fail = false
        var hang = false
        suspend fun send(path: String, payload: ByteArray) {
            assertEquals(DataLayer.PATH_LIVE_SAMPLES, path)
            if (hang) awaitCancellation()
            if (fail) throw java.io.IOException("bluetooth gone")
            batches += LiveCodec.decode(payload)
        }
    }

    private fun TestScope.preview(
        sent: Sent,
        ring: Int = LivePreview.RING_CAPACITY,
        ctx: () -> LiveContext = { context() },
    ) = LivePreview(
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
        nowNs = { testScheduler.currentTime * 1_000_000L },
        context = ctx,
        sender = { p, b -> sent.send(p, b) },
        ringCapacity = ring,
    )

    private fun context(sid: String? = "sess") = LiveContext(
        sid, 0, LiveSegmentKind.NONE, "all", 11, 22, 49.5, 50.5, 0, false,
    )

    private fun LivePreview.feed(n: Int, startNs: Long = 0, stepNs: Long = 20_000_000L, x: Float = 1f) {
        for (i in 0 until n) {
            onSample(TYPE_ACC, startNs + i * stepNs, x, 0f, 9.81f)
            onSample(TYPE_GYR, startNs + i * stepNs, 0.1f, 0f, 0f)
        }
    }

    // ---- lease ------------------------------------------------------------------------------

    @Test
    fun leaseStartsRenewsExpiresAndUnsubscribes() {
        var now = 0L
        val lease = LiveLease { now }
        assertFalse(lease.isActive())
        lease.subscribe(10_000)
        now = 9_999; assertTrue(lease.isActive())
        now = 10_000; assertFalse(lease.isActive())
        now = 5_000; lease.subscribe(10_000) // renew replaces the expiry
        now = 14_999; assertTrue(lease.isActive())
        now = 15_000; assertFalse(lease.isActive())
        lease.subscribe(10_000)
        lease.unsubscribe()
        assertFalse(lease.isActive())
    }

    @Test
    fun leaseLengthIsClamped() {
        var now = 0L
        val lease = LiveLease { now }
        lease.subscribe(Long.MAX_VALUE / 4)
        now = LiveSubscribe.MAX_LEASE_MS; assertFalse(lease.isActive())
        lease.subscribe(1)
        now += LiveSubscribe.MIN_LEASE_MS - 1; assertTrue(lease.isActive())
    }

    @Test
    fun noLeaseNoStreamingAndNothingBuffered() = runTest {
        val sent = Sent()
        val p = preview(sent)
        p.onStart(0)
        p.feed(20)
        advanceTimeBy(2_000)
        assertTrue(sent.batches.isEmpty())
        assertFalse(p.isStreaming)
        // subscribing later does not replay old samples, but health totals are whole-session
        p.subscribe(10_000)
        advanceTimeBy(201)
        assertEquals(1, sent.batches.size)
        assertTrue(sent.batches[0].acc.isEmpty())
    }

    @Test
    fun batchesEvery200msWithAllSamplesAndWatchOffsets() = runTest {
        val sent = Sent()
        val p = preview(sent)
        p.onStart(1_000_000_000L) // session start on the sensor clock
        p.subscribe(10_000)
        p.feed(10, startNs = 1_500_000_000L)
        advanceTimeBy(201)
        val b = sent.batches.single()
        assertEquals(0L, b.seq)
        assertEquals("sess", b.sessionId)
        assertEquals(10, b.acc.size)
        assertEquals(10, b.gyr.size)
        assertEquals(500_000L, b.baseOffsetUs)
        assertEquals(20_000, b.acc[1].dtUs)
        assertEquals("all", b.strategy)
        assertEquals(11L, b.health.accWritten)
        assertEquals(49.5f, b.health.accRateHz, 0f)
        // native units are untouched
        assertEquals(9.81f, b.acc[0].z, 0f)
        advanceTimeBy(200)
        assertEquals(listOf(0L, 1L), sent.batches.map { it.seq })
        assertTrue(sent.batches[1].acc.isEmpty()) // empty batches still carry health
    }

    @Test
    fun streamingStopsWithinOneLeasePeriodAfterThePhoneGoesAway() = runTest {
        val sent = Sent()
        val p = preview(sent)
        p.onStart(0)
        p.subscribe(2_000)
        advanceTimeBy(1_000)
        p.subscribe(2_000) // renewal at t=1 s
        advanceTimeBy(10_000)
        val count = sent.batches.size
        assertTrue("batches=$count", count in 14..16) // ~3 s of 200 ms batches
        advanceTimeBy(10_000)
        assertEquals(count, sent.batches.size)
        assertFalse(p.isStreaming)
        p.subscribe(2_000) // a later renewal restarts it
        advanceTimeBy(201)
        assertEquals(count + 1, sent.batches.size)
    }

    @Test
    fun unsubscribeAndSessionEndStopStreaming() = runTest {
        val sent = Sent()
        val p = preview(sent)
        p.onStart(0)
        p.subscribe(10_000)
        advanceTimeBy(401)
        val n = sent.batches.size
        p.unsubscribe()
        advanceTimeBy(5_000)
        assertEquals(n, sent.batches.size)

        p.subscribe(10_000)
        advanceTimeBy(201)
        val m = sent.batches.size
        p.onStop()
        advanceTimeBy(5_000)
        assertEquals(m, sent.batches.size)
        assertFalse(p.lease.isActive()) // the lease ended with the session
    }

    @Test
    fun leaseBeforeSessionStartStreamsOnceRecordingBegins() = runTest {
        val sent = Sent()
        val p = preview(sent)
        p.subscribe(10_000)
        advanceTimeBy(1_000)
        assertTrue(sent.batches.isEmpty())
        p.onStart(0)
        advanceTimeBy(201)
        assertEquals(1, sent.batches.size)
    }

    @Test
    fun noBatchWithoutASession() = runTest {
        val sent = Sent()
        val p = preview(sent, ctx = { context(sid = null) })
        p.onStart(0)
        p.subscribe(10_000)
        advanceTimeBy(1_000)
        assertTrue(sent.batches.isEmpty())
    }

    // ---- health ---------------------------------------------------------------------------------

    @Test
    fun gapsAndSaturationAreCountedForTheWholeSessionEvenWithoutALease() = runTest {
        val sent = Sent()
        val p = preview(sent)
        p.onStart(0)
        p.onSample(TYPE_ACC, 0, 0f, 0f, 9.8f)
        p.onSample(TYPE_ACC, 20_000_000, 0f, 0f, 9.8f)
        p.onSample(TYPE_ACC, 120_000_001, 0f, 0f, 9.8f) // 100.000001 ms: a gap
        p.onSample(TYPE_ACC, 220_000_001, 0f, 0f, 9.8f) // exactly 100 ms: not a gap
        p.onSample(TYPE_GYR, 0, 0f, 0f, 0f)
        p.onSample(TYPE_GYR, 300_000_000, 0f, 0f, 0f) // gyro gap
        p.onSample(TYPE_ACC, 230_000_000, 7.8f * G, 0f, 0f) // saturated (and out of order: no gap)
        p.onSample(TYPE_ACC, 240_000_000, 0f, -7.9f * G, 0f)
        p.onSample(TYPE_ACC, 250_000_000, 7.7f * G, 7.7f * G, -7.7f * G) // not saturated
        p.subscribe(10_000)
        advanceTimeBy(201)
        val h = sent.batches.single().health
        assertEquals(2, h.gaps)
        assertEquals(2, h.saturated)
    }

    @Test
    fun countersResetOnTheNextSession() = runTest {
        val p = preview(Sent())
        p.onStart(0)
        p.onSample(TYPE_ACC, 0, 8f * G, 0f, 0f)
        p.onSample(TYPE_ACC, 500_000_000, 0f, 0f, 0f)
        p.onStop()
        p.onStart(0)
        p.subscribe(10_000)
        val b = p.buildBatch()!!
        assertEquals(0, b.health.gaps)
        assertEquals(0, b.health.saturated)
        assertEquals(0L, b.seq)
    }

    @Test
    fun segmentAndRecorderDropsComeFromTheContext() = runTest {
        val sent = Sent()
        val p = preview(sent, ctx = { context().copy(segmentId = 12, segmentKind = LiveSegmentKind.SET, recorderDropped = 5, writeError = true) })
        p.onStart(0)
        p.subscribe(10_000)
        advanceTimeBy(201)
        val b = sent.batches.single()
        assertEquals(12, b.segmentId)
        assertEquals(LiveSegmentKind.SET, b.segmentKind)
        assertEquals(5, b.health.recorderDropped)
        assertTrue(b.health.writeError)
    }

    // ---- preview never harms the recording ---------------------------------------------------

    private suspend fun TestScope.record(withPreview: Boolean, ring: Int = 4096, failSend: Boolean = false, n: Int = 400): File {
        val dir = createTempDirectory("rec").toFile()
        val src = FakeSensorSource()
        val sent = Sent().also { it.fail = failSend }
        val live = if (withPreview) preview(sent, ring) else null
        val rec = SensorRecorder(src, FakeClock(), StandardTestDispatcher(testScheduler), tap = live)
        rec.start(this, dir, "sid-identical", SessionKind.GYM, testApp, testDevice)
        live?.subscribe(30_000)
        // jitter, duplicates, out-of-order and a long gap: all must be written verbatim either way
        for (i in 0 until n) {
            val jitter = if (i % 7 == 0) 3_000_000L else 0L
            val t = 1_000_000_000L + i * 20_000_000L + jitter + (if (i > n / 2) 400_000_000L else 0L)
            src.acc(t, i * 0.001f, -0.5f, 9.81f)
            src.acc(t) // exact duplicate
            if (i % 50 == 0) src.acc(t - 50_000_000L) // out of order
            src.gyr(t, 0.01f * i, 0f, -0.2f)
            if (i % 40 == 0) advanceTimeBy(250) // let the batcher run mid-stream
        }
        runCurrent()
        rec.stop()
        advanceTimeBy(1_000)
        return dir
    }

    private fun File.snapshot(): Map<String, ByteArray> =
        listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }

    @Test
    fun recordedFilesAreByteIdenticalWithPreviewEnabledOrDisabled() = runTest {
        val off = record(withPreview = false).snapshot()
        val on = record(withPreview = true).snapshot()
        assertTrue(off.keys.containsAll(listOf("acc.csv", "gyr.csv", "meta.json")))
        assertEquals(off.keys, on.keys)
        for ((name, bytes) in off) assertArrayEquals("file $name differs", bytes, on.getValue(name))
    }

    @Test
    fun recordedFilesAreByteIdenticalWhenTheTinyPreviewBufferOverflowsAndSendsFail() = runTest {
        val off = record(withPreview = false).snapshot()
        val on = record(withPreview = true, ring = 8, failSend = true).snapshot()
        for ((name, bytes) in off) assertArrayEquals("file $name differs", bytes, on.getValue(name))
    }

    @Test
    fun ringOverflowDropsPreviewSamplesNeverRecordedOnes() = runTest {
        val dir = createTempDirectory("rec").toFile()
        val src = FakeSensorSource()
        val sent = Sent()
        val live = preview(sent, ring = 5)
        val rec = SensorRecorder(src, FakeClock(), StandardTestDispatcher(testScheduler), tap = live)
        rec.start(this, dir, "sid-overflow", SessionKind.GYM, testApp, testDevice)
        live.subscribe(10_000)
        for (i in 0 until 20) src.acc(1_000L + i * 20_000_000L)
        advanceTimeBy(201)
        rec.stop()
        assertEquals(5, sent.batches.sumOf { it.acc.size }) // preview kept only what fit
        assertEquals(21, dir.lines("acc.csv").size) // header + all 20 recorded
        assertEquals(0, rec.snapshot().droppedEvents)
    }

    @Test
    fun ringCountsDropsAndKeepsOrder() {
        val r = SampleRing(3)
        assertTrue(r.offer(1, 1, 0f, 0f, 0f))
        assertTrue(r.offer(1, 2, 0f, 0f, 0f))
        assertTrue(r.offer(1, 3, 0f, 0f, 0f))
        assertFalse(r.offer(1, 4, 0f, 0f, 0f))
        assertEquals(1L, r.dropped)
        val ts = mutableListOf<Long>()
        r.drain { _, t, _, _, _ -> ts += t }
        assertEquals(listOf(1L, 2L, 3L), ts)
        assertTrue(r.offer(1, 5, 0f, 0f, 0f)) // space again after draining
    }

    @Test
    fun aFailingOrHangingSenderOnlyCountsPreviewFailures() = runTest {
        val sent = Sent().also { it.fail = true }
        val p = preview(sent)
        p.onStart(0)
        p.subscribe(10_000)
        advanceTimeBy(601)
        assertEquals(3, p.sendFailures)
        sent.fail = false; sent.hang = true
        advanceTimeBy(5_000)
        assertTrue(p.sendFailures > 3) // timeouts count too
        sent.hang = false
        advanceTimeBy(5_000)
        assertTrue(sent.batches.isNotEmpty()) // and it recovers
    }

    @Test
    fun aThrowingTapCannotBreakTheRecording() = runTest {
        val dir = createTempDirectory("rec").toFile()
        val src = FakeSensorSource()
        val tap = object : dev.proprio.wear.sensor.SampleTap {
            override fun onStart(startNs: Long) {}
            override fun onSample(type: Int, tNs: Long, x: Float, y: Float, z: Float) = error("boom")
            override fun onStop() = error("boom")
        }
        val rec = SensorRecorder(src, FakeClock(), StandardTestDispatcher(testScheduler), tap = tap)
        rec.start(this, dir, "sid-boom", SessionKind.GYM, testApp, testDevice)
        src.acc(1L); src.acc(2L)
        rec.stop()
        assertEquals(3, dir.lines("acc.csv").size)
    }

    // ---- phone commands -------------------------------------------------------------------------

    @Test
    fun subscribeAndUnsubscribeMessagesDriveTheLease() = runTest {
        val root = createTempDirectory("ctl").toFile()
        val clock = FakeClock(scheduler = testScheduler)
        val source = FakeSensorSource()
        val sent = Sent()
        lateinit var controller: RecordingController
        val live = LivePreview(
            backgroundScope, { testScheduler.currentTime }, { clock.elapsedRealtimeNs() },
            { context(controller.state.value.sessionId) }, { p, b -> sent.send(p, b) },
        )
        val recorder = SensorRecorder(source, clock, StandardTestDispatcher(testScheduler), tap = live)
        controller = RecordingController(
            recorder = recorder, clock = clock,
            transfer = TransferManager(File(root, "sessions"), File(root, "outbox"), FakeSender(), FakeNodes()),
            phone = FakePhone(), haptics = FakeHaptics(), host = FakeHost(), app = testApp, device = testDevice,
            scope = backgroundScope, newSessionId = { "sess-1" },
        )
        val commands = CommandHandler(controller, live = live)

        assertTrue(commands.handle(DataLayer.PATH_LIVE_SUBSCRIBE, DataLayer.encode(LiveSubscribe(4_000))))
        assertTrue(live.lease.isActive())
        assertTrue(commands.handle(DataLayer.PATH_LIVE_UNSUBSCRIBE, ByteArray(0)))
        assertFalse(live.lease.isActive())
        assertTrue(commands.handle(DataLayer.PATH_LIVE_SUBSCRIBE, ByteArray(0))) // empty payload: default lease
        assertTrue(live.lease.isActive())
        assertFalse(commands.handle(DataLayer.PATH_LIVE_SUBSCRIBE, "garbage".toByteArray()))

        assertTrue(controller.startSession(SessionKind.GYM))
        runCurrent()
        advanceTimeBy(250)
        assertTrue(sent.batches.isNotEmpty())
        assertEquals("sess-1", sent.batches.last().sessionId)
        controller.endSession()
        val n = sent.batches.size
        advanceTimeBy(10_000)
        assertEquals(n, sent.batches.size) // session end ends streaming
        assertFalse(live.lease.isActive())
    }
}
