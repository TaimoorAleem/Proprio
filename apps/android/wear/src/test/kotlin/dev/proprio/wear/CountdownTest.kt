package dev.proprio.wear

import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentEvent
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SegmentStartCommand
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionReader
import dev.proprio.protocol.WatchStatus
import dev.proprio.wear.sensor.SensorRecorder
import dev.proprio.wear.session.CommandHandler
import dev.proprio.wear.session.PhoneLink
import dev.proprio.wear.session.RecordingController
import dev.proprio.wear.ui.startSetLabel
import dev.proprio.wear.ui.countdownSeconds
import dev.proprio.wear.transfer.TransferManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hands-free "Start set in N s" state machine, driven by a fake clock tied to the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class CountdownTest {
    private class Harness(val t: TestScope, phoneLink: PhoneLink? = null) {
        val root: File = createTempDirectory("cd").toFile()
        val clock = FakeClock(scheduler = t.testScheduler)
        val source = FakeSensorSource()
        val phone = FakePhone()
        val haptics = FakeHaptics()
        val remembered = mutableListOf<Long>()
        val transfer = TransferManager(File(root, "sessions"), File(root, "outbox"), FakeSender(), FakeNodes())
        val controller = RecordingController(
            recorder = SensorRecorder(source, clock, StandardTestDispatcher(t.testScheduler)),
            clock = clock, transfer = transfer, phone = phoneLink ?: phone, haptics = haptics, host = FakeHost(),
            app = testApp, device = testDevice, scope = t.backgroundScope,
            newSessionId = { "sess-1" },
            onCountdownDefault = { remembered += it },
        )
        val commands = CommandHandler(controller)

        fun status() = phone.paths(DataLayer.PATH_STATUS).map { DataLayer.decode<WatchStatus>(it) }
        fun events() = phone.paths(DataLayer.PATH_SEGMENT_EVENT).map { DataLayer.decode<SegmentEvent>(it) }
    }

    /** Advances virtual time and also runs the tasks due exactly at the new time. */
    private suspend fun TestScope.adv(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private suspend fun Harness.startGym() {
        assertTrue(controller.startSession(SessionKind.GYM))
        t.adv(1_000)
        t.runCurrent()
    }

    @Test
    fun countdownTicksEverySecondThenOpensTheSegmentAtTheEndTime() = runTest {
        val h = Harness(this)
        h.startGym()
        val tapNs = h.clock.elapsedRealtimeNs()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH, 3_000))
        runCurrent()
        assertEquals(3_000L, h.controller.state.value.countdownRemainingMs)
        assertEquals(MarkKind.SET, h.controller.state.value.countdownKind)
        assertNull(h.controller.state.value.openSegmentId)
        assertEquals(listOf("tick"), h.haptics.events)

        adv(1_000)
        assertEquals(listOf("tick", "tick"), h.haptics.events)
        assertEquals(2_000L, h.controller.state.value.countdownRemainingMs)
        adv(1_000)
        assertEquals(listOf("tick", "tick", "tick"), h.haptics.events)
        adv(999)
        assertNull(h.controller.state.value.openSegmentId) // 1 ms to go

        adv(1)
        assertEquals(1, h.controller.state.value.openSegmentId)
        assertNull(h.controller.state.value.countdownRemainingMs)
        assertEquals(listOf("tick", "tick", "tick", "go"), h.haptics.events)

        // status messages carried the remaining time: 3 s, 2 s, 1 s, then none
        val remaining = h.status().map { it.countdownEndsInMs }.filterNotNull()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), remaining.distinct())
        assertNull(h.status().last().countdownEndsInMs)
        assertEquals(1, h.status().last().openSegmentId)
        val started = h.events().single()
        assertEquals(SegmentEvent.Event.STARTED, started.event)
        assertEquals("watch", started.source)

        adv(2_000)
        assertTrue(h.controller.endSession(SegmentSource.WATCH))
        runCurrent()
        val rec = SessionReader.readZip(h.transfer.zipFile("sess-1"))
        val seg = rec.segments.single()
        // the segment starts when the countdown ended (tap + 3 s), not at the tap
        assertEquals(tapNs + 3_000_000_000L, seg.tStartNs)
    }

    @Test
    fun zeroMillisecondsIsTodaysImmediateBehaviourWithoutCountdownHaptics() = runTest {
        val h = Harness(this)
        h.startGym()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH, 0))
        assertEquals(1, h.controller.state.value.openSegmentId)
        assertNull(h.controller.state.value.countdownRemainingMs)
        assertEquals(listOf("start"), h.haptics.events)
        adv(5_000)
        assertEquals(listOf("start"), h.haptics.events)
    }

    @Test
    fun cancelFromThePhoneStopsTheCountdownWithADoubleBuzz() = runTest {
        val h = Harness(this)
        h.startGym()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 5_000))
        adv(2_000)
        assertTrue(h.commands.handle(DataLayer.PATH_SEGMENT_CANCEL, ByteArray(0)))
        runCurrent()
        assertNull(h.controller.state.value.countdownRemainingMs)
        assertEquals(listOf("tick", "tick", "tick", "cancelled"), h.haptics.events)
        assertNull(h.status().last().countdownEndsInMs)

        adv(10_000)
        assertNull(h.controller.state.value.openSegmentId)
        assertTrue(h.events().isEmpty())
        assertEquals(4, h.haptics.events.size) // no further ticks and no "go"
        // nothing to cancel any more
        assertFalse(h.controller.cancelCountdown(SegmentSource.PHONE))
    }

    @Test
    fun aWatchTapRightAfterTheStartTapIsADoublePressButALaterTapCancels() = runTest {
        val h = Harness(this)
        h.startGym()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH, 5_000))
        adv(500)
        assertFalse(h.controller.cancelCountdown(SegmentSource.WATCH)) // 0.5 s: accidental double tap
        assertTrue(h.controller.state.value.countdownRemainingMs != null)
        adv(500)
        assertTrue(h.controller.cancelCountdown(SegmentSource.WATCH))
        assertNull(h.controller.state.value.countdownRemainingMs)
        assertEquals("cancelled", h.haptics.events.last())
        adv(10_000)
        assertNull(h.controller.state.value.openSegmentId)
    }

    @Test
    fun aSecondStartWhileCountingDownIsIgnored() = runTest {
        val h = Harness(this)
        h.startGym()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 3_000))
        adv(1_000)
        assertFalse(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 10_000))
        assertFalse(h.controller.startSegment(MarkKind.OTHER, SegmentSource.PHONE, 0))
        adv(2_000)
        // the original countdown finished on time; only one segment, a set
        assertEquals(1, h.controller.state.value.openSegmentId)
        assertEquals(MarkKind.SET, h.controller.state.value.openSegmentKind)
        assertEquals(1, h.events().size)
    }

    @Test
    fun endingTheSessionDuringTheCountdownCancelsItWithoutOpeningASegment() = runTest {
        val h = Harness(this)
        h.startGym()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 5_000))
        adv(2_000)
        assertTrue(h.controller.endSession(SegmentSource.PHONE))
        runCurrent()
        assertNull(h.controller.state.value.countdownRemainingMs)
        adv(10_000)
        assertTrue(h.events().isEmpty())
        assertFalse(h.haptics.events.contains("go"))
        assertEquals(3, h.haptics.events.size) // 3 ticks (0 s, 1 s, 2 s), nothing after the end
        val rec = SessionReader.readZip(h.transfer.zipFile("sess-1"))
        assertTrue(rec.segments.isEmpty())
        // a new session after that is not affected by the old countdown
        assertFalse(h.controller.state.value.recording)
    }

    @Test
    fun theCountdownFinishesEvenIfEveryMessageToThePhoneFails() = runTest {
        val h = Harness(this, phoneLink = PhoneLink { _, _ -> throw java.io.IOException("phone gone") })
        h.startGym()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 3_000))
        adv(3_000)
        assertEquals(1, h.controller.state.value.openSegmentId)
        assertEquals("go", h.haptics.events.last())
    }

    @Test
    fun aCountdownNeedsRestAndASession() = runTest {
        val h = Harness(this)
        assertFalse(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 3_000)) // no session
        h.startGym()
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 0))
        assertFalse(h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE, 3_000)) // a segment is open
        assertNull(h.controller.state.value.countdownRemainingMs)
        assertTrue(h.controller.stopSegment(SegmentSource.PHONE))
    }

    @Test
    fun phoneCommandCarriesTheCountdownAndTheWatchRemembersTheLastValue() = runTest {
        val h = Harness(this)
        h.startGym()
        assertEquals(SegmentStartCommand.DEFAULT_COUNTDOWN_MS, h.controller.defaultCountdownMs) // "else 5"
        val cmd = DataLayer.encode(SegmentStartCommand(MarkKind.SET, countdownMs = 3_000))
        assertTrue(h.commands.handle(DataLayer.PATH_SEGMENT_START, cmd))
        runCurrent()
        assertEquals(3_000L, h.controller.state.value.countdownRemainingMs)
        assertEquals(3_000L, h.controller.defaultCountdownMs)
        assertEquals(listOf(3_000L), h.remembered)
        assertEquals("Start set in 3 s", startSetLabel(h.controller.state.value.setCountdownMs))
        h.commands.handle(DataLayer.PATH_SEGMENT_CANCEL, ByteArray(0))
        adv(2_000)

        // an absurd value is clamped; "other" never changes the remembered default
        h.commands.handle(DataLayer.PATH_SEGMENT_START, DataLayer.encode(SegmentStartCommand(MarkKind.SET, countdownMs = 9_999_999)))
        assertEquals(SegmentStartCommand.MAX_COUNTDOWN_MS, h.controller.defaultCountdownMs)
        assertEquals(SegmentStartCommand.MAX_COUNTDOWN_MS, h.controller.state.value.countdownRemainingMs)
        h.commands.handle(DataLayer.PATH_SEGMENT_CANCEL, ByteArray(0))
        h.commands.handle(DataLayer.PATH_SEGMENT_START, DataLayer.encode(SegmentStartCommand(MarkKind.OTHER)))
        assertEquals(SegmentStartCommand.MAX_COUNTDOWN_MS, h.controller.defaultCountdownMs)
        assertEquals(MarkKind.OTHER, h.controller.state.value.openSegmentKind)
    }

    @Test
    fun anOldPayloadWithoutCountdownOpensImmediately() = runTest {
        val h = Harness(this)
        h.startGym()
        assertTrue(h.commands.handle(DataLayer.PATH_SEGMENT_START, """{"kind": "set"}""".toByteArray()))
        assertEquals(1, h.controller.state.value.openSegmentId)
        assertNull(h.controller.state.value.countdownRemainingMs)
    }

    @Test
    fun labelsAndSeconds() {
        assertEquals("Start set", startSetLabel(0))
        assertEquals("Start set in 5 s", startSetLabel(5_000))
        assertEquals("Start set in 10 s", startSetLabel(10_000))
        assertEquals(5, countdownSeconds(5_000))
        assertEquals(5, countdownSeconds(4_001))
        assertEquals(1, countdownSeconds(1))
    }
}
