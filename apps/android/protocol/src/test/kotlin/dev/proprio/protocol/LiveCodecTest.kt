package dev.proprio.protocol

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveCodecTest {
    private fun batch(nAcc: Int = 10, nGyr: Int = 10, seq: Long = 7) = LiveBatch(
        seq = seq,
        sessionId = "00000000-0000-4000-8000-000000000001",
        baseOffsetUs = 123_456_789_012L,
        segmentId = 12,
        segmentKind = LiveSegmentKind.SET,
        strategy = "all",
        health = LiveHealth(1000, 2000, 49.8f, 50.1f, 2, 3, 4, true),
        acc = List(nAcc) { LiveSample(it * 20_000, 0.5f + it, -9.81f, 1e-3f) },
        gyr = List(nGyr) { LiveSample(it * 20_000 + 5, -1f, 2f, Float.MIN_VALUE) },
    )

    @Test
    fun roundTrip() {
        val b = batch()
        assertEquals(b, LiveCodec.decode(LiveCodec.encode(b)))
    }

    @Test
    fun roundTripEmptyAndSequenceAbove31Bits() {
        val b = batch(0, 0, seq = 0xFFFFFFF0L).copy(segmentId = 0, segmentKind = LiveSegmentKind.NONE)
        assertEquals(b, LiveCodec.decode(LiveCodec.encode(b)))
    }

    @Test
    fun sizeIsCompact() {
        // 10 + 10 samples at 16 bytes plus a header of about 130 bytes
        assertTrue(LiveCodec.encode(batch()).size < 500)
    }

    @Test
    fun layoutIsLittleEndianWithVersionedHeader() {
        val bytes = LiveCodec.encode(batch(seq = 1))
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('L'.code.toByte(), bytes[1])
        assertEquals(1.toByte(), bytes[2])
        assertEquals(1.toByte(), bytes[4]) // seq low byte first
        assertEquals(0.toByte(), bytes[5])
    }

    @Test
    fun unknownVersionIsRejected() {
        val bytes = LiveCodec.encode(batch())
        bytes[2] = 2
        val e = assertThrows(LiveCodecException::class.java) { LiveCodec.decode(bytes) }
        assertTrue(e.message!!.contains("version"))
    }

    @Test
    fun wrongMagicIsRejected() {
        val bytes = LiveCodec.encode(batch())
        bytes[0] = 'X'.code.toByte()
        assertThrows(LiveCodecException::class.java) { LiveCodec.decode(bytes) }
    }

    @Test
    fun everyTruncationIsRejected() {
        val bytes = LiveCodec.encode(batch(3, 3))
        for (n in bytes.indices) {
            assertThrows(LiveCodecException::class.java, { LiveCodec.decode(bytes.copyOf(n)) }, "length $n")
        }
    }

    @Test
    fun trailingBytesAreRejected() {
        assertThrows(LiveCodecException::class.java) { LiveCodec.decode(LiveCodec.encode(batch()) + byteArrayOf(0)) }
    }

    @Test
    fun garbageNeverThrowsAnythingElse() {
        val rnd = Random(42)
        repeat(2000) {
            val junk = rnd.nextBytes(rnd.nextInt(0, 200))
            if (junk.size > 2 && rnd.nextBoolean()) { junk[0] = 'P'.code.toByte(); junk[1] = 'L'.code.toByte(); junk[2] = 1 }
            try {
                LiveCodec.decode(junk)
            } catch (_: LiveCodecException) {
                // expected
            }
        }
    }

    @Test
    fun badSegmentKindIsRejected() {
        val bytes = LiveCodec.encode(batch())
        bytes[4 + 4 + 8 + 4] = 9
        assertThrows(LiveCodecException::class.java) { LiveCodec.decode(bytes) }
    }

    @Test
    fun subscribePayloadsAndPaths() {
        val sub = LiveSubscribe(4000)
        assertEquals(sub, DataLayer.decode<LiveSubscribe>(DataLayer.encode(sub)))
        assertEquals(10_000L, LiveSubscribe().leaseMs)
        listOf(DataLayer.PATH_LIVE_SUBSCRIBE, DataLayer.PATH_LIVE_UNSUBSCRIBE, DataLayer.PATH_LIVE_SAMPLES)
            .forEach { assertTrue(it.startsWith("/proprio/v1/live/")) }
        assertEquals("/proprio/v1/live/samples", DataLayer.PATH_LIVE_SAMPLES)
    }
}
