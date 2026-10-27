package dev.proprio.protocol

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Segment state carried by a live batch. */
enum class LiveSegmentKind(val code: Int) {
    NONE(0),
    SET(1),
    OTHER(2),

    /** An automatic (calibration) segment, which has no user-chosen kind. */
    AUTO(3),
    ;

    companion object {
        fun fromCode(code: Int): LiveSegmentKind? = entries.firstOrNull { it.code == code }
    }
}

/** One live sample in the watch's native units (m/s^2 or rad/s); [dtUs] is relative to [LiveBatch.baseOffsetUs]. */
data class LiveSample(val dtUs: Int, val x: Float, val y: Float, val z: Float)

/** Watch-side recording health for the whole session so far. Computed from the raw event stream, display-only. */
data class LiveHealth(
    val accWritten: Long,
    val gyrWritten: Long,
    val accRateHz: Float,
    val gyrRateHz: Float,
    /** Inter-sample gaps above 100 ms, both sensors together. */
    val gaps: Int,
    /** Accelerometer samples with any |axis| >= 7.8 g (the sensor range is about 8 g). */
    val saturated: Int,
    /** Events the recorder discarded because its queue was full (real data loss). */
    val recorderDropped: Int,
    val writeError: Boolean,
)

/**
 * One ~200 ms batch of the live preview. [baseOffsetUs] is the watch elapsed time (since the session start) of the
 * batch's window start; sample times are `baseOffsetUs + dtUs`. [seq] counts batches built on the watch, so a gap in
 * the sequence seen by the phone means preview batches were lost in transit (not a recording problem).
 */
data class LiveBatch(
    val seq: Long,
    val sessionId: String,
    val baseOffsetUs: Long,
    val segmentId: Int,
    val segmentKind: LiveSegmentKind,
    val strategy: String,
    val health: LiveHealth,
    val acc: List<LiveSample>,
    val gyr: List<LiveSample>,
)

class LiveCodecException(message: String) : IllegalArgumentException(message)

/**
 * Compact binary encoding of [LiveBatch] for `/proprio/v1/live/samples` (little-endian):
 *
 * ```
 * u8 'P', u8 'L', u8 version (=1), u8 reserved (=0)
 * u32 seq, i64 baseOffsetUs, i32 segmentId, u8 segmentKind
 * u8 sessionIdLen, utf8 sessionId, u8 strategyLen, utf8 strategy
 * i64 accWritten, i64 gyrWritten, f32 accRateHz, f32 gyrRateHz,
 * i32 gaps, i32 saturated, i32 recorderDropped, u8 writeError
 * u16 nAcc, u16 nGyr
 * nAcc x (i32 dtUs, f32 x, f32 y, f32 z), then nGyr x (same)
 * ```
 * The version is checked on decode; an unknown version is rejected, never guessed at.
 */
object LiveCodec {
    const val VERSION = 1
    private const val MAGIC_0 = 'P'.code
    private const val MAGIC_1 = 'L'.code
    private const val SAMPLE_BYTES = 16
    const val MAX_SAMPLES_PER_SENSOR = 0xFFFF

    fun encode(b: LiveBatch): ByteArray {
        require(b.acc.size <= MAX_SAMPLES_PER_SENSOR && b.gyr.size <= MAX_SAMPLES_PER_SENSOR) { "too many samples" }
        val sid = b.sessionId.toByteArray(Charsets.UTF_8)
        val strat = b.strategy.toByteArray(Charsets.UTF_8)
        require(sid.size <= 255 && strat.size <= 255) { "string too long" }
        val size = 4 + 4 + 8 + 4 + 1 + 1 + sid.size + 1 + strat.size + 8 + 8 + 4 + 4 + 4 + 4 + 4 + 1 + 2 + 2 +
            (b.acc.size + b.gyr.size) * SAMPLE_BYTES
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_0.toByte()).put(MAGIC_1.toByte()).put(VERSION.toByte()).put(0)
        buf.putInt(b.seq.toInt()).putLong(b.baseOffsetUs).putInt(b.segmentId).put(b.segmentKind.code.toByte())
        buf.put(sid.size.toByte()).put(sid).put(strat.size.toByte()).put(strat)
        val h = b.health
        buf.putLong(h.accWritten).putLong(h.gyrWritten).putFloat(h.accRateHz).putFloat(h.gyrRateHz)
        buf.putInt(h.gaps).putInt(h.saturated).putInt(h.recorderDropped).put(if (h.writeError) 1 else 0)
        buf.putShort(b.acc.size.toShort()).putShort(b.gyr.size.toShort())
        for (s in b.acc) putSample(buf, s)
        for (s in b.gyr) putSample(buf, s)
        return buf.array()
    }

    /** @throws LiveCodecException on a wrong magic or version, truncated or trailing bytes, or bad field values. */
    fun decode(bytes: ByteArray): LiveBatch {
        try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 4 || (buf.get().toInt() and 0xFF) != MAGIC_0 || (buf.get().toInt() and 0xFF) != MAGIC_1) {
                throw LiveCodecException("not a live batch")
            }
            val version = buf.get().toInt() and 0xFF
            if (version != VERSION) throw LiveCodecException("unsupported live batch version $version")
            buf.get() // reserved
            val seq = buf.getInt().toLong() and 0xFFFFFFFFL
            val base = buf.getLong()
            val segId = buf.getInt()
            val kind = LiveSegmentKind.fromCode(buf.get().toInt() and 0xFF) ?: throw LiveCodecException("bad segment kind")
            val sessionId = readString(buf)
            val strategy = readString(buf)
            val health = LiveHealth(
                accWritten = buf.getLong(), gyrWritten = buf.getLong(),
                accRateHz = buf.getFloat(), gyrRateHz = buf.getFloat(),
                gaps = buf.getInt(), saturated = buf.getInt(), recorderDropped = buf.getInt(),
                writeError = buf.get().toInt() != 0,
            )
            val nAcc = buf.getShort().toInt() and 0xFFFF
            val nGyr = buf.getShort().toInt() and 0xFFFF
            if (buf.remaining() != (nAcc + nGyr) * SAMPLE_BYTES) throw LiveCodecException("length mismatch")
            val acc = List(nAcc) { readSample(buf) }
            val gyr = List(nGyr) { readSample(buf) }
            return LiveBatch(seq, sessionId, base, segId, kind, strategy, health, acc, gyr)
        } catch (e: BufferUnderflowException) {
            throw LiveCodecException("truncated live batch")
        }
    }

    private fun putSample(buf: ByteBuffer, s: LiveSample) {
        buf.putInt(s.dtUs).putFloat(s.x).putFloat(s.y).putFloat(s.z)
    }

    private fun readSample(buf: ByteBuffer) = LiveSample(buf.getInt(), buf.getFloat(), buf.getFloat(), buf.getFloat())

    private fun readString(buf: ByteBuffer): String {
        val n = buf.get().toInt() and 0xFF
        if (n > buf.remaining()) throw LiveCodecException("truncated string")
        val a = ByteArray(n)
        buf.get(a)
        return String(a, Charsets.UTF_8)
    }
}
