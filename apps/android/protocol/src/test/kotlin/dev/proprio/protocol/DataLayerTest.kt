package dev.proprio.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataLayerTest {
    @Test
    fun pathsAreVersionedAndDistinct() {
        val paths = listOf(
            DataLayer.PATH_SESSION_START,
            DataLayer.PATH_SESSION_END,
            DataLayer.PATH_SEGMENT_START,
            DataLayer.PATH_SEGMENT_STOP,
            DataLayer.PATH_SEGMENT_CANCEL,
            DataLayer.PATH_SESSION_ACK,
            DataLayer.PATH_STATUS,
            DataLayer.PATH_SEGMENT_EVENT,
        )
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.all { it.startsWith("/proprio/v1/") })
    }

    @Test
    fun countdownFieldsAreOptionalAndBackwardCompatible() {
        // payloads from before the countdown existed decode exactly as before
        val oldStart = DataLayer.decode<SegmentStartCommand>("""{"kind": "set"}""".toByteArray())
        assertEquals(SegmentStartCommand(MarkKind.SET, countdownMs = 0), oldStart)
        val oldStatus = DataLayer.decode<WatchStatus>(
            """{"state": "recording", "session_id": "s1", "open_segment_id": 2}""".toByteArray(),
        )
        assertEquals(WatchStatus(WatchStatus.State.RECORDING, sessionId = "s1", openSegmentId = 2), oldStatus)
        assertNull(oldStatus.countdownEndsInMs)

        // new fields use snake_case keys and round-trip
        val start = SegmentStartCommand(MarkKind.SET, countdownMs = 5_000)
        assertTrue(DataLayer.encode(start).decodeToString().contains("\"countdown_ms\": 5000"))
        assertEquals(start, DataLayer.decode<SegmentStartCommand>(DataLayer.encode(start)))
        val status = WatchStatus(WatchStatus.State.RECORDING, sessionId = "s1", countdownEndsInMs = 3_200)
        assertTrue(DataLayer.encode(status).decodeToString().contains("\"countdown_ends_in_ms\": 3200"))
        assertEquals(status, DataLayer.decode<WatchStatus>(DataLayer.encode(status)))
        // a status without a countdown carries no countdown key at all
        assertTrue(!DataLayer.encode(WatchStatus(WatchStatus.State.IDLE)).decodeToString().contains("countdown"))
    }

    @Test
    fun countdownIsClamped() {
        assertEquals(0L, SegmentStartCommand.clampCountdown(-5))
        assertEquals(10_000L, SegmentStartCommand.clampCountdown(10_000))
        assertEquals(SegmentStartCommand.MAX_COUNTDOWN_MS, SegmentStartCommand.clampCountdown(9_999_999))
        assertTrue(SegmentStartCommand.COUNTDOWN_CHOICES_MS.all { it in 0..SegmentStartCommand.MAX_COUNTDOWN_MS })
    }

    @Test
    fun channelPathRoundTripsSessionId() {
        val path = DataLayer.channelPath("abc-123")
        assertEquals("abc-123", DataLayer.sessionIdFromChannelPath(path))
        assertNull(DataLayer.sessionIdFromChannelPath(DataLayer.PATH_STATUS))
        assertNull(DataLayer.sessionIdFromChannelPath(DataLayer.channelPath("")))
    }

    @Test
    fun payloadsRoundTripWithSnakeCaseKeys() {
        val start = SessionStartCommand(SessionKind.CALIBRATION, participantId = "P01")
        val bytes = DataLayer.encode(start)
        assertTrue(bytes.decodeToString().contains("\"session_kind\": \"calibration\""))
        assertEquals(start, DataLayer.decode<SessionStartCommand>(bytes))

        val event = SegmentEvent("s1", 3, SegmentEvent.Event.STARTED, MarkKind.SET, source = "phone")
        assertEquals(event, DataLayer.decode<SegmentEvent>(DataLayer.encode(event)))

        val status = WatchStatus(WatchStatus.State.RECORDING, sessionId = "s1", openSegmentId = 3, accRateHz = 49.9)
        assertEquals(status, DataLayer.decode<WatchStatus>(DataLayer.encode(status)))

        val ack = SessionAck("s1", "deadbeef", ok = true)
        assertEquals(ack, DataLayer.decode<SessionAck>(DataLayer.encode(ack)))
        assertEquals(
            SegmentStartCommand(MarkKind.OTHER),
            DataLayer.decode<SegmentStartCommand>(DataLayer.encode(SegmentStartCommand(MarkKind.OTHER))),
        )
    }
}
