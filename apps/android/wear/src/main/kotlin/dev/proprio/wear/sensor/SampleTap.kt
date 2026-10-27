package dev.proprio.wear.sensor

/**
 * Read-only observer of the raw sensor events a [SensorRecorder] captures (the live preview).
 *
 * The recorder offers every event to its own queue first and to the tap afterwards, inside a try/catch, so a tap can
 * never block, reorder, alter or fail the recording. Implementations are called on the sensor thread and must be
 * non-blocking and allocation-light; anything they cannot keep up with has to be dropped on their side.
 */
interface SampleTap {
    /** Recording is about to start; [startNs] is the session's clock anchor (same clock as event timestamps). */
    fun onStart(startNs: Long)

    fun onSample(type: Int, tNs: Long, x: Float, y: Float, z: Float)

    /** Recording stopped or was aborted. */
    fun onStop()
}
