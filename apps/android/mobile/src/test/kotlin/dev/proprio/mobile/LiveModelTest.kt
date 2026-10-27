package dev.proprio.mobile

import dev.proprio.mobile.data.FakeWatchLink
import dev.proprio.mobile.domain.AxisRange
import dev.proprio.mobile.domain.Level
import dev.proprio.mobile.domain.LinkStatus
import dev.proprio.mobile.domain.LiveModel
import dev.proprio.mobile.domain.LiveSeries
import dev.proprio.mobile.domain.STANDARD_GRAVITY
import dev.proprio.protocol.LiveBatch
import dev.proprio.protocol.LiveCodec
import dev.proprio.protocol.LiveHealth
import dev.proprio.protocol.LiveSample
import dev.proprio.protocol.LiveSegmentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun liveBatch(
    seq: Long,
    sid: String = "s1",
    baseUs: Long = seq * 200_000,
    n: Int = 10,
    accZ: Float = STANDARD_GRAVITY.toFloat(),
    gyrX: Float = Math.PI.toFloat(),
    health: LiveHealth = LiveHealth(100, 100, 50f, 50f, 0, 0, 0, false),
    kind: LiveSegmentKind = LiveSegmentKind.NONE,
    segId: Int = 0,
) = LiveBatch(
    seq, sid, baseUs, segId, kind, "all", health,
    List(n) { LiveSample(it * 20_000, 0f, 0f, accZ) },
    List(n) { LiveSample(it * 20_000, gyrX, 0f, 0f) },
)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveModelTest {
    @Test
    fun convertsUnitsForDisplayOnly() {
        val m = LiveModel()
        m.onBatch(liveBatch(0), 0)
        assertEquals(1.0f, m.acc.z(0), 1e-5f) // 9.80665 m/s^2 -> 1 g
        assertEquals(180.0f, m.gyr.x(0), 1e-3f) // pi rad/s -> 180 deg/s
    }

    @Test
    fun codecToModelUsesWatchTimeOffsets() {
        val m = LiveModel()
        m.onBatch(LiveCodec.decode(LiveCodec.encode(liveBatch(0, baseUs = 2_000_000))), 0)
        assertEquals(2.0, m.acc.t(0), 1e-9)
        assertEquals(2.18, m.acc.t(9), 1e-9)
    }

    @Test
    fun windowIsTrimmedToTenSecondsPlusSlack() {
        val m = LiveModel(capacity = 4096)
        for (i in 0L until 100) m.onBatch(liveBatch(i), i * 200) // 20 s of data
        val newest = m.acc.lastT()
        assertTrue(m.acc.t(0) >= newest - 10.0 - 1.0 - 1e-6)
        assertTrue(m.acc.size in 500..600)
    }

    @Test
    fun ringOverwritesOldestWhenFull() {
        val s = LiveSeries(4)
        for (i in 0 until 6) s.add(i.toDouble(), i.toFloat(), 0f, 0f)
        assertEquals(4, s.size)
        assertEquals(2.0, s.t(0), 0.0)
        assertEquals(5.0, s.lastT(), 0.0)
    }

    @Test
    fun sequenceGapsCountAsPreviewDropsNotRecordingGaps() {
        val m = LiveModel()
        m.onBatch(liveBatch(0), 0)
        m.onBatch(liveBatch(1), 200)
        m.onBatch(liveBatch(4), 800) // 2 and 3 never arrived
        assertEquals(2L, m.previewDropped)
        val ui = m.healthUi(800)
        assertEquals(2L, ui.previewDropped)
        assertEquals(0, ui.gaps) // the watch's own gap counter is separate
        assertEquals(Level.OK, ui.gapsLevel)
        assertTrue(ui.recordingOk)
    }

    @Test
    fun duplicatesAndReorderedBatchesAreIgnored() {
        val m = LiveModel()
        m.onBatch(liveBatch(5), 0)
        val size = m.acc.size
        m.onBatch(liveBatch(5), 10)
        m.onBatch(liveBatch(3), 20)
        assertEquals(size, m.acc.size)
        assertEquals(0L, m.previewDropped)
    }

    @Test
    fun newSessionResetsTheWindowAndCounters() {
        val m = LiveModel()
        m.onBatch(liveBatch(0), 0)
        m.onBatch(liveBatch(5), 1000)
        m.onBatch(liveBatch(0, sid = "s2"), 2000)
        assertEquals(0L, m.previewDropped)
        assertEquals(10, m.acc.size)
    }

    @Test
    fun linkGoesLiveStaleNoData() {
        val m = LiveModel()
        assertEquals(LinkStatus.NO_DATA, m.linkStatus(0))
        m.onBatch(liveBatch(0), 1_000)
        assertEquals(LinkStatus.LIVE, m.linkStatus(1_999))
        assertEquals(LinkStatus.STALE, m.linkStatus(2_001))
        assertEquals(LinkStatus.NO_DATA, m.linkStatus(6_001))
        assertEquals(2.001, m.healthUi(3_001).linkAgeS!!, 1e-9)
    }

    @Test
    fun rateLevelsUseFortyFiveToFiftyFive() {
        fun level(hz: Float): Level {
            val m = LiveModel()
            m.onBatch(liveBatch(0, health = LiveHealth(1, 1, hz, 50f, 0, 0, 0, false)), 0)
            return m.healthUi(0).accLevel
        }
        assertEquals(Level.OK, level(45f))
        assertEquals(Level.OK, level(55f))
        assertEquals(Level.BAD, level(44.9f))
        assertEquals(Level.BAD, level(55.1f))
        assertEquals(Level.BAD, level(0f))
    }

    @Test
    fun badWatchSideIndicatorsFlipTheRecordingVerdict() {
        val m = LiveModel()
        m.onBatch(liveBatch(0, health = LiveHealth(1, 1, 50f, 50f, 3, 2, 0, false)), 0)
        val ui = m.healthUi(0)
        assertEquals(Level.BAD, ui.gapsLevel)
        assertEquals(Level.WARN, ui.saturatedLevel)
        assertTrue(!ui.recordingOk)
        m.onBatch(liveBatch(1, health = LiveHealth(1, 1, 50f, 50f, 0, 0, 7, false)), 0)
        assertEquals(Level.BAD, m.healthUi(0).recorderLevel)
        m.onBatch(liveBatch(2, health = LiveHealth(1, 1, 50f, 50f, 0, 0, 0, true)), 0)
        assertTrue(m.healthUi(0).writeError)
    }

    @Test
    fun segmentText() {
        val m = LiveModel()
        m.onBatch(liveBatch(0, kind = LiveSegmentKind.SET, segId = 12), 0)
        assertEquals("SET #12 recording", m.healthUi(0).segment)
        m.onBatch(liveBatch(1), 0)
        assertEquals("Rest", m.healthUi(0).segment)
    }

    @Test
    fun axisRangeSnapsToRoundQuartersAndKeepsMinimumSpan() {
        val r = AxisRange(minSpan = 2.0)
        val s = LiveSeries(16)
        s.add(0.0, 0.1f, 0.2f, 1.0f)
        r.fit(s, -1.0)
        assertTrue(r.hi - r.lo >= 2.0)
        val q = (r.hi - r.lo) / 4
        assertEquals(q, Math.round(q * 100) / 100.0, 1e-9) // quarter lines on round numbers
        assertTrue(r.lo <= 0.1 && r.hi >= 1.0)
        s.add(1.0, 0f, 0f, -7.9f)
        r.fit(s, -1.0)
        assertTrue(r.lo <= -7.9)
    }

    @Test
    fun windowEndExtrapolatesButIsCapped() {
        val m = LiveModel()
        m.onBatch(liveBatch(0), 1_000) // newest sample at 0.18 s
        assertEquals(0.18, m.windowEnd(1_000), 1e-9)
        assertEquals(0.28, m.windowEnd(1_100), 1e-9)
        assertEquals(0.68, m.windowEnd(9_000), 1e-9)
    }

    @Test
    fun controllerHoldsTheLeaseOnlyWhileVisible() = runTest {
        val link = FakeWatchLink()
        val c = LivePreviewController(CoroutineScope(Job() + Dispatchers.Unconfined), link, { testScheduler.currentTime }, Dispatchers.Unconfined)
        val job = launch { c.runVisible() }
        runCurrent()
        advanceTimeBy(8_100) // renewals at 0, 4 s, 8 s
        assertEquals(listOf("live/subscribe:10000", "live/subscribe:10000", "live/subscribe:10000"), link.liveCommands)
        job.cancel()
        runCurrent()
        assertEquals("live/unsubscribe", link.liveCommands.last())
        val n = link.liveCommands.size
        advanceTimeBy(20_000)
        assertEquals(n, link.liveCommands.size)
    }

    @Test
    fun controllerFeedsBatchesIntoTheModel() = runTest {
        val link = FakeWatchLink()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val c = LivePreviewController(scope, link, { 5L }, Dispatchers.Unconfined)
        link.emitLive(liveBatch(0))
        assertEquals(10, c.model.acc.size)
        scope.cancel()
    }
}
