package dev.proprio.wear.live

/**
 * Fixed-size single-producer / single-consumer ring of raw samples: the sensor thread offers, the batcher drains.
 * [offer] never blocks and never allocates; when the ring is full the sample is dropped and counted, which is how the
 * preview is kept from ever back-pressuring the recorder.
 */
class SampleRing(private val capacity: Int) {
    private val type = ByteArray(capacity)
    private val t = LongArray(capacity)
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val z = FloatArray(capacity)

    @Volatile private var tail = 0L // next write index, written by the producer only
    @Volatile private var head = 0L // next read index, written by the consumer only

    /** Samples dropped because the ring was full (written by the producer only). */
    @Volatile var dropped = 0L
        private set

    /** Producer side. Returns false (and counts a drop) when full. */
    fun offer(sensorType: Int, tNs: Long, vx: Float, vy: Float, vz: Float): Boolean {
        val w = tail
        if (w - head >= capacity) {
            dropped++
            return false
        }
        val i = (w % capacity).toInt()
        type[i] = sensorType.toByte(); t[i] = tNs; x[i] = vx; y[i] = vy; z[i] = vz
        tail = w + 1 // volatile write publishes the slot
        return true
    }

    fun interface Consumer {
        fun accept(sensorType: Int, tNs: Long, x: Float, y: Float, z: Float)
    }

    /** Consumer side: hands every sample published so far to [c], oldest first. */
    fun drain(c: Consumer) {
        val end = tail
        var r = head
        while (r < end) {
            val i = (r % capacity).toInt()
            c.accept(type[i].toInt(), t[i], x[i], y[i], z[i])
            r++
        }
        head = r
    }
}
