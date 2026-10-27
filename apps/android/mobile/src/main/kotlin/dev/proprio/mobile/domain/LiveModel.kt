package dev.proprio.mobile.domain

import dev.proprio.protocol.LiveBatch
import dev.proprio.protocol.LiveSegmentKind
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** Standard gravity, for display conversion only (m/s^2 -> g). */
const val STANDARD_GRAVITY = 9.80665

private const val RAD_TO_DEG = 180.0 / Math.PI

/**
 * Ring buffer of one sensor's live samples in display units (g or deg/s) with watch time in seconds.
 * Fixed arrays, so adding a batch allocates nothing; the oldest sample is overwritten when full.
 */
class LiveSeries(val capacity: Int = 1024) {
    private val ts = DoubleArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val zs = FloatArray(capacity)
    private var start = 0

    var size = 0
        private set

    fun add(t: Double, x: Float, y: Float, z: Float) {
        val i = (start + size) % capacity
        if (size == capacity) start = (start + 1) % capacity else size++
        ts[i] = t; xs[i] = x; ys[i] = y; zs[i] = z
    }

    fun clear() {
        start = 0; size = 0
    }

    /** Drops samples older than [minT] seconds. */
    fun trimBefore(minT: Double) {
        while (size > 0 && ts[start] < minT) {
            start = (start + 1) % capacity
            size--
        }
    }

    private fun idx(i: Int) = (start + i) % capacity

    fun t(i: Int) = ts[idx(i)]
    fun x(i: Int) = xs[idx(i)]
    fun y(i: Int) = ys[idx(i)]
    fun z(i: Int) = zs[idx(i)]
    fun value(axis: Int, i: Int) = when (axis) { 0 -> x(i); 1 -> y(i); else -> z(i) }
    fun lastT(): Double = if (size == 0) Double.NEGATIVE_INFINITY else t(size - 1)
}

/** Vertical range of a chart, snapped so that the four quarter grid lines fall on round numbers. */
class AxisRange(private val minSpan: Double) {
    var lo = -minSpan / 2
        private set
    var hi = minSpan / 2
        private set

    /** Re-fits to the data in [s] newer than [minT]; the range only changes when the data leaves it. */
    fun fit(s: LiveSeries, minT: Double) {
        var mn = Double.POSITIVE_INFINITY
        var mx = Double.NEGATIVE_INFINITY
        for (i in 0 until s.size) {
            if (s.t(i) < minT) continue
            for (a in 0..2) {
                val v = s.value(a, i).toDouble()
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
        }
        if (mn > mx) return // no data: keep the current range
        val mid = (mn + mx) / 2
        val span = maxOf(mx - mn, minSpan)
        val step = niceStep(span * 1.1 / 4)
        val low = floor((mid - span * 0.55) / step) * step
        val n = ceil((mid + span * 0.55 - low) / (4 * step)).coerceAtLeast(1.0)
        lo = low
        hi = low + n * 4 * step
    }

    fun mid() = (lo + hi) / 2

    companion object {
        /** 1, 2, 5 x 10^k at or above [raw]. */
        fun niceStep(raw: Double): Double {
            val p = 10.0.pow(floor(kotlin.math.log10(raw)))
            val f = raw / p
            val m = when {
                f <= 1.0 -> 1.0
                f <= 2.0 -> 2.0
                f <= 5.0 -> 5.0
                else -> 10.0
            }
            return m * p
        }
    }
}

enum class LinkStatus { LIVE, STALE, NO_DATA }

enum class Level { OK, WARN, BAD, NEUTRAL }

/** Everything the health row shows, derived from the latest batch and the phone clock. */
data class LiveHealthUi(
    val link: LinkStatus,
    val linkAgeS: Double?,
    val accHz: Float?,
    val gyrHz: Float?,
    val accLevel: Level,
    val gyrLevel: Level,
    val gaps: Int,
    val gapsLevel: Level,
    val saturated: Int,
    val saturatedLevel: Level,
    /** Preview batches lost between watch and phone (Bluetooth); NOT a recording problem. */
    val previewDropped: Long,
    /** Events the watch recorder itself discarded (a real recording problem). */
    val recorderDropped: Int,
    val writeError: Boolean,
    val recorderLevel: Level,
    val accWritten: Long,
    val gyrWritten: Long,
    val segment: String,
    val strategy: String?,
) {
    /** True when every watch-side recording indicator is fine (link and preview drops are separate). */
    val recordingOk: Boolean get() =
        accLevel == Level.OK && gyrLevel == Level.OK && gapsLevel == Level.OK && recorderLevel == Level.OK
}

/**
 * Phone-side model of the live preview: decoded batches -> rolling window of display-unit samples plus health.
 * Units: the watch sends m/s^2 and rad/s, converted here for display only. Not thread-safe; call from one thread
 * (the controller collects on the main dispatcher, as does the UI).
 */
class LiveModel(
    val windowS: Double = 10.0,
    capacity: Int = 1024,
) {
    val acc = LiveSeries(capacity)
    val gyr = LiveSeries(capacity)
    val accRange = AxisRange(minSpan = 2.0)
    val gyrRange = AxisRange(minSpan = 100.0)

    private var sessionId: String? = null
    private var lastSeq = -1L
    private var lastArrivalMs = -1L
    private var latestT = 0.0
    private var latest: LiveBatch? = null

    var batchesReceived = 0L
        private set
    var previewDropped = 0L
        private set

    fun reset() {
        acc.clear(); gyr.clear()
        sessionId = null; lastSeq = -1; lastArrivalMs = -1; latestT = 0.0; latest = null
        batchesReceived = 0; previewDropped = 0
    }

    fun onBatch(b: LiveBatch, nowMs: Long) {
        if (b.sessionId != sessionId) {
            reset()
            sessionId = b.sessionId
        } else if (b.seq <= lastSeq) {
            return // duplicate or reordered: never go backwards
        }
        if (lastSeq >= 0) previewDropped += b.seq - lastSeq - 1
        lastSeq = b.seq
        batchesReceived++
        lastArrivalMs = nowMs
        latest = b
        val base = b.baseOffsetUs
        for (s in b.acc) {
            val t = (base + s.dtUs) / 1e6
            acc.add(t, (s.x / STANDARD_GRAVITY).toFloat(), (s.y / STANDARD_GRAVITY).toFloat(), (s.z / STANDARD_GRAVITY).toFloat())
            if (t > latestT) latestT = t
        }
        for (s in b.gyr) {
            val t = (base + s.dtUs) / 1e6
            gyr.add(t, (s.x * RAD_TO_DEG).toFloat(), (s.y * RAD_TO_DEG).toFloat(), (s.z * RAD_TO_DEG).toFloat())
            if (t > latestT) latestT = t
        }
        if (b.acc.isEmpty() && b.gyr.isEmpty()) latestT = maxOf(latestT, base / 1e6)
        val minT = latestT - windowS - EXTRA_KEEP_S
        acc.trimBefore(minT)
        gyr.trimBefore(minT)
    }

    /** Right edge of the chart: the newest watch time, extrapolated smoothly with the phone clock (capped). */
    fun windowEnd(nowMs: Long): Double {
        if (lastArrivalMs < 0) return 0.0
        val extra = ((nowMs - lastArrivalMs) / 1000.0).coerceIn(0.0, MAX_EXTRAPOLATE_S)
        return latestT + extra
    }

    /** Re-fits the axis ranges to the visible window; call once per frame tick (no allocation). */
    fun updateRanges(nowMs: Long) {
        val minT = windowEnd(nowMs) - windowS
        accRange.fit(acc, minT)
        gyrRange.fit(gyr, minT)
    }

    fun linkStatus(nowMs: Long): LinkStatus = when {
        lastArrivalMs < 0 || nowMs - lastArrivalMs > NO_DATA_MS -> LinkStatus.NO_DATA
        nowMs - lastArrivalMs > STALE_MS -> LinkStatus.STALE
        else -> LinkStatus.LIVE
    }

    fun healthUi(nowMs: Long): LiveHealthUi {
        val b = latest
        val h = b?.health
        val link = linkStatus(nowMs)
        fun rate(hz: Float?) = when {
            hz == null || link == LinkStatus.NO_DATA -> Level.NEUTRAL
            hz in RATE_OK_MIN..RATE_OK_MAX -> Level.OK
            else -> Level.BAD
        }
        val gaps = h?.gaps ?: 0
        val sat = h?.saturated ?: 0
        val dropped = h?.recorderDropped ?: 0
        val writeErr = h?.writeError ?: false
        return LiveHealthUi(
            link = link,
            linkAgeS = if (lastArrivalMs < 0) null else (nowMs - lastArrivalMs) / 1000.0,
            accHz = h?.accRateHz,
            gyrHz = h?.gyrRateHz,
            accLevel = rate(h?.accRateHz),
            gyrLevel = rate(h?.gyrRateHz),
            gaps = gaps,
            gapsLevel = if (h == null) Level.NEUTRAL else if (gaps == 0) Level.OK else Level.BAD,
            saturated = sat,
            saturatedLevel = if (h == null) Level.NEUTRAL else if (sat == 0) Level.OK else Level.WARN,
            previewDropped = previewDropped,
            recorderDropped = dropped,
            writeError = writeErr,
            recorderLevel = if (h == null) Level.NEUTRAL else if (dropped == 0 && !writeErr) Level.OK else Level.BAD,
            accWritten = h?.accWritten ?: 0,
            gyrWritten = h?.gyrWritten ?: 0,
            segment = segmentText(b),
            strategy = b?.strategy,
        )
    }

    companion object {
        const val STALE_MS = 1_000L
        const val NO_DATA_MS = 5_000L
        const val RATE_OK_MIN = 45f
        const val RATE_OK_MAX = 55f
        private const val EXTRA_KEEP_S = 1.0
        private const val MAX_EXTRAPOLATE_S = 0.5

        fun segmentText(b: LiveBatch?): String = when {
            b == null -> "-"
            b.segmentKind == LiveSegmentKind.NONE -> "Rest"
            b.segmentKind == LiveSegmentKind.SET -> "SET #${b.segmentId} recording"
            b.segmentKind == LiveSegmentKind.OTHER -> "OTHER #${b.segmentId} recording"
            else -> "Calibration step #${b.segmentId}"
        }
    }
}

