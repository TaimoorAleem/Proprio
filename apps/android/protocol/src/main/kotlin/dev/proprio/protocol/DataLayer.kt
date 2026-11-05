package dev.proprio.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wearable Data Layer contract between :wear and :mobile (docs/specs/phase2-recorder.md section 5).
 *
 * Commands flow phone -> watch over MessageClient; the watch owns all timestamps and segment ids
 * and reports back with [SegmentEvent] and [WatchStatus]. Finished sessions travel watch -> phone
 * over ChannelClient on [channelPath] and are acknowledged with [SessionAck].
 */
object DataLayer {
    const val VERSION = "v1"
    private const val ROOT = "/proprio/$VERSION"

    // phone -> watch
    const val PATH_SESSION_START = "$ROOT/session/start"
    const val PATH_SESSION_END = "$ROOT/session/end"
    const val PATH_SEGMENT_START = "$ROOT/segment/start"
    const val PATH_SEGMENT_STOP = "$ROOT/segment/stop"

    /** Cancels a running start countdown (empty payload); a no-op when none is running. */
    const val PATH_SEGMENT_CANCEL = "$ROOT/segment/cancel"
    const val PATH_SESSION_ACK = "$ROOT/session/ack"

    /** Live preview lease (display-only, never recorded; see [LiveSubscribe]). */
    const val PATH_LIVE_SUBSCRIBE = "$ROOT/live/subscribe"
    const val PATH_LIVE_UNSUBSCRIBE = "$ROOT/live/unsubscribe"

    // watch -> phone
    const val PATH_STATUS = "$ROOT/status"
    /** Binary [LiveBatch] stream, see [LiveCodec]. */
    const val PATH_LIVE_SAMPLES = "$ROOT/live/samples"
    const val PATH_SEGMENT_EVENT = "$ROOT/segment/event"
    private const val CHANNEL_PREFIX = "$ROOT/session/zip/"

    /** Capabilities declared in each app's res/values/wear.xml, used for node discovery. */
    const val CAPABILITY_WEAR = "proprio_wear"
    const val CAPABILITY_MOBILE = "proprio_mobile"

    fun channelPath(sessionId: String): String = CHANNEL_PREFIX + sessionId

    /** Session id from a channel path, or null if the path is not a session transfer. */
    fun sessionIdFromChannelPath(path: String): String? =
        path.takeIf { it.startsWith(CHANNEL_PREFIX) }?.removePrefix(CHANNEL_PREFIX)?.ifEmpty { null }

    fun encode(payload: SessionStartCommand): ByteArray = ProtocolJson.encodeToString(payload).toByteArray()
    fun encode(payload: SegmentStartCommand): ByteArray = ProtocolJson.encodeToString(payload).toByteArray()
    fun encode(payload: SessionAck): ByteArray = ProtocolJson.encodeToString(payload).toByteArray()
    fun encode(payload: WatchStatus): ByteArray = ProtocolJson.encodeToString(payload).toByteArray()
    fun encode(payload: SegmentEvent): ByteArray = ProtocolJson.encodeToString(payload).toByteArray()

    fun encode(payload: LiveSubscribe): ByteArray = ProtocolJson.encodeToString(payload).toByteArray()

    inline fun <reified T> decode(bytes: ByteArray): T = ProtocolJson.decodeFromString(bytes.decodeToString())
}

/** Segment kinds a user can open explicitly; rest is implicit (section 3.2). */
@Serializable
enum class MarkKind {
    @SerialName("set") SET,
    @SerialName("other") OTHER,
}

@Serializable
data class SessionStartCommand(
    val sessionKind: SessionKind,
    val participantId: String? = null,
)

/**
 * Opens a segment. [countdownMs] > 0 asks the watch to count down first and open the segment when the countdown ends
 * (the segment's start timestamp is that moment, on the watch clock, never the time of the command); 0 (the default,
 * and what older senders imply by omitting the field) opens it immediately.
 */
@Serializable
data class SegmentStartCommand(val kind: MarkKind, val countdownMs: Long = 0) {
    companion object {
        /** Upper bound the watch accepts; larger values are clamped. */
        const val MAX_COUNTDOWN_MS = 30_000L

        /** Default countdown when nothing was configured. */
        const val DEFAULT_COUNTDOWN_MS = 5_000L

        /** Countdown lengths offered in the phone settings (0 = immediate). */
        val COUNTDOWN_CHOICES_MS = listOf(0L, 3_000L, 5_000L, 10_000L)

        fun clampCountdown(ms: Long): Long = ms.coerceIn(0L, MAX_COUNTDOWN_MS)
    }
}

/** Sent by the watch whenever a segment opens or closes, so the phone can attach labels by id. */
@Serializable
data class SegmentEvent(
    val sessionId: String,
    val segmentId: Int,
    val event: Event,
    val kind: MarkKind? = null,
    val source: String,
) {
    @Serializable
    enum class Event {
        @SerialName("started") STARTED,
        @SerialName("stopped") STOPPED,
    }
}

/** Periodic and on-change watch state for the phone UI. */
@Serializable
data class WatchStatus(
    val state: State,
    val sessionId: String? = null,
    val sessionKind: SessionKind? = null,
    val openSegmentId: Int? = null,
    val elapsedS: Double? = null,
    val accRateHz: Double? = null,
    val gyrRateHz: Double? = null,
    val calibrationStep: String? = null,
    /**
     * Milliseconds left of a running start countdown, measured when the watch sent this status; null when no countdown
     * runs. The state stays [State.RECORDING] during a countdown (no segment is open yet). Absent in older watches.
     */
    val countdownEndsInMs: Long? = null,
) {
    @Serializable
    enum class State {
        @SerialName("idle") IDLE,
        @SerialName("recording") RECORDING,
        @SerialName("transferring") TRANSFERRING,
    }
}

/** Phone -> watch after receiving a session zip; the watch deletes its copy only when ok and hashes match. */
@Serializable
data class SessionAck(
    val sessionId: String,
    val sha256: String,
    val ok: Boolean,
)

/**
 * Phone -> watch lease for the live sensor preview. The watch streams `/live/samples` only while recording
 * AND a lease is valid; the phone renews before [leaseMs] elapses and sends `/live/unsubscribe` (empty payload)
 * when the live view is hidden. The preview is display-only: never stored, uploaded or part of Raw Format v1.
 */
@Serializable
data class LiveSubscribe(val leaseMs: Long = DEFAULT_LEASE_MS) {
    companion object {
        const val DEFAULT_LEASE_MS = 10_000L
        const val MIN_LEASE_MS = 1_000L
        const val MAX_LEASE_MS = 30_000L

        /** How often the phone renews while the live view is visible. */
        const val RENEW_INTERVAL_MS = 4_000L
    }
}
