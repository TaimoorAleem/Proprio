package dev.proprio.wear.live

import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.LiveBatch
import dev.proprio.protocol.LiveCodec
import dev.proprio.protocol.LiveHealth
import dev.proprio.protocol.LiveSample
import dev.proprio.protocol.LiveSegmentKind
import dev.proprio.wear.sensor.SampleTap
import dev.proprio.wear.sensor.TYPE_ACC
import dev.proprio.wear.sensor.TYPE_GYR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Session facts the preview cannot see from the raw stream; read once per batch. */
data class LiveContext(
    val sessionId: String?,
    val segmentId: Int,
    val segmentKind: LiveSegmentKind,
    val strategy: String,
    val accWritten: Long,
    val gyrWritten: Long,
    val accRateHz: Double,
    val gyrRateHz: Double,
    val recorderDropped: Long,
    val writeError: Boolean,
)

/** Sends one encoded batch to the phone; may throw or be slow (Bluetooth). */
fun interface LiveSender {
    suspend fun send(path: String, payload: ByteArray)
}

/**
 * Live sensor preview (display-only). A read-only [SampleTap] on the recorder's raw events:
 *
 * - Health counters (inter-sample gaps above [GAP_NS], accelerometer saturation) are kept for the whole session from
 *   the sensor thread, whether or not anybody is watching, so a late subscriber still sees session totals.
 * - Samples are copied into a bounded [SampleRing] only while a lease is valid. A full ring drops preview samples
 *   and never affects the recording.
 * - A batcher coroutine runs only while recording AND the lease is valid; every [batchMs] it drains the ring into one
 *   [LiveBatch] and sends it. Nothing is stored, nothing touches the session files.
 */
class LivePreview(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val nowNs: () -> Long,
    private val context: () -> LiveContext,
    private val sender: LiveSender,
    private val log: (String) -> Unit = {},
    private val batchMs: Long = BATCH_MS,
    ringCapacity: Int = RING_CAPACITY,
    private val sendTimeoutMs: Long = SEND_TIMEOUT_MS,
) : SampleTap {
    val lease = LiveLease(nowMs)
    private val ring = SampleRing(ringCapacity)

    @Volatile private var recording = false
    @Volatile private var streaming = false
    @Volatile private var startNs = 0L

    // Sensor-thread writers; read by the batcher.
    @Volatile private var gaps = 0
    @Volatile private var saturated = 0
    private var lastAccNs = Long.MIN_VALUE
    private var lastGyrNs = Long.MIN_VALUE

    private var seq = 0L
    private var job: Job? = null

    /** Batches whose send failed or timed out (preview loss on the watch side). */
    @Volatile var sendFailures = 0
        private set

    val isStreaming: Boolean get() = streaming

    // ---- lease control (phone commands) --------------------------------------------------------

    fun subscribe(leaseMs: Long) {
        lease.subscribe(leaseMs)
        if (recording) startBatcher()
    }

    fun unsubscribe() {
        lease.unsubscribe()
        streaming = false
    }

    // ---- SampleTap (sensor thread) -------------------------------------------------------------

    override fun onStart(startNs: Long) {
        this.startNs = startNs
        gaps = 0; saturated = 0
        lastAccNs = Long.MIN_VALUE; lastGyrNs = Long.MIN_VALUE
        seq = 0
        recording = true
        streaming = lease.isActive()
        if (streaming) startBatcher()
    }

    override fun onSample(type: Int, tNs: Long, x: Float, y: Float, z: Float) {
        when (type) {
            TYPE_ACC -> {
                if (lastAccNs != Long.MIN_VALUE && tNs - lastAccNs > GAP_NS) gaps++
                lastAccNs = tNs
                if (Math.abs(x) >= SATURATION_MS2 || Math.abs(y) >= SATURATION_MS2 || Math.abs(z) >= SATURATION_MS2) saturated++
            }
            TYPE_GYR -> {
                if (lastGyrNs != Long.MIN_VALUE && tNs - lastGyrNs > GAP_NS) gaps++
                lastGyrNs = tNs
            }
        }
        if (streaming) ring.offer(type, tNs, x, y, z)
    }

    override fun onStop() {
        recording = false
        streaming = false
        lease.unsubscribe() // the lease ends with the session; the phone resubscribes for the next one
    }

    // ---- batching ------------------------------------------------------------------------------

    @Synchronized
    private fun startBatcher() {
        streaming = true
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                delay(batchMs)
                if (!tickShouldContinue()) return@launch
                sendBatch()
            }
        }
    }

    @Synchronized
    private fun tickShouldContinue(): Boolean {
        val go = recording && lease.isActive()
        if (!go) {
            streaming = false
            job = null
            ring.drain { _, _, _, _, _ -> } // discard whatever is left
        }
        return go
    }

    /** Builds the next batch from the ring and the context, or null when no session is active. Exposed for tests. */
    fun buildBatch(): LiveBatch? {
        val ctx = context()
        val sid = ctx.sessionId ?: return null
        val accT = ArrayList<Long>(); val gyrT = ArrayList<Long>()
        val acc = ArrayList<LiveSample>(); val gyr = ArrayList<LiveSample>()
        // First pass into temporary absolute-time lists, then rebase on the earliest sample.
        val absAcc = ArrayList<FloatArray>(); val absGyr = ArrayList<FloatArray>()
        ring.drain { type, t, x, y, z ->
            if (type == TYPE_ACC) { accT += t; absAcc += floatArrayOf(x, y, z) } else if (type == TYPE_GYR) { gyrT += t; absGyr += floatArrayOf(x, y, z) }
        }
        val first = minOf(accT.firstOrNull() ?: Long.MAX_VALUE, gyrT.firstOrNull() ?: Long.MAX_VALUE)
        val baseNs = if (first == Long.MAX_VALUE) nowNs() else first
        for (i in accT.indices) acc += LiveSample(((accT[i] - baseNs) / 1000L).toInt(), absAcc[i][0], absAcc[i][1], absAcc[i][2])
        for (i in gyrT.indices) gyr += LiveSample(((gyrT[i] - baseNs) / 1000L).toInt(), absGyr[i][0], absGyr[i][1], absGyr[i][2])
        return LiveBatch(
            seq = seq++,
            sessionId = sid,
            baseOffsetUs = (baseNs - startNs) / 1000L,
            segmentId = ctx.segmentId,
            segmentKind = ctx.segmentKind,
            strategy = ctx.strategy,
            health = LiveHealth(
                ctx.accWritten, ctx.gyrWritten, ctx.accRateHz.toFloat(), ctx.gyrRateHz.toFloat(),
                gaps, saturated, ctx.recorderDropped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), ctx.writeError,
            ),
            acc = acc,
            gyr = gyr,
        )
    }

    private suspend fun sendBatch() {
        val batch = buildBatch() ?: return
        val bytes = LiveCodec.encode(batch)
        try {
            val done = withTimeoutOrNull(sendTimeoutMs) { sender.send(DataLayer.PATH_LIVE_SAMPLES, bytes) }
            if (done == null) sendFailures++
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            sendFailures++
            log("live batch send failed: $e")
        }
    }

    companion object {
        const val BATCH_MS = 200L
        const val RING_CAPACITY = 2048 // about 20 s of both sensors at 50 Hz
        const val SEND_TIMEOUT_MS = 2_000L
        const val GAP_NS = 100_000_000L
        const val SATURATION_MS2 = 7.8f * 9.80665f
    }
}
