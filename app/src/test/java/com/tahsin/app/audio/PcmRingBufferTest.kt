package com.tahsin.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingBufferTest {

    private fun ramp(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    @Test
    fun `snapshot returns the most recent samples in order`() {
        val buffer = PcmRingBuffer(capacitySamples = 8)
        buffer.append(ramp(1, 4), 4)

        val snapshot = buffer.snapshotLast(4)

        assertArrayEquals(
            floatArrayOf(1 / 32768f, 2 / 32768f, 3 / 32768f, 4 / 32768f),
            snapshot,
            1e-9f,
        )
    }

    @Test
    fun `snapshot is clipped to what has been recorded`() {
        val buffer = PcmRingBuffer(capacitySamples = 8)
        buffer.append(ramp(1, 2), 2)

        assertEquals(2, buffer.snapshotLast(6).size)
    }

    @Test
    fun `writes wrap around and still read back contiguously`() {
        val buffer = PcmRingBuffer(capacitySamples = 4)
        buffer.append(ramp(1, 3), 3)
        buffer.append(ramp(4, 3), 3) // wraps past the end

        val snapshot = buffer.snapshotLast(4)

        assertArrayEquals(
            floatArrayOf(3 / 32768f, 4 / 32768f, 5 / 32768f, 6 / 32768f),
            snapshot,
            1e-9f,
        )
    }

    @Test
    fun `a single append longer than the capacity keeps only the tail`() {
        val buffer = PcmRingBuffer(capacitySamples = 4)
        buffer.append(ramp(1, 10), 10)

        val snapshot = buffer.snapshotLast(4)

        assertArrayEquals(
            floatArrayOf(7 / 32768f, 8 / 32768f, 9 / 32768f, 10 / 32768f),
            snapshot,
            1e-9f,
        )
        assertEquals(10L, buffer.totalSamples)
    }

    @Test
    fun `total samples acts as an audio clock`() {
        val buffer = PcmRingBuffer(capacitySamples = 16)
        buffer.append(ramp(1, 5), 5)
        buffer.append(ramp(1, 7), 7)

        assertEquals(12L, buffer.totalSamples)
    }

    @Test
    fun `clear resets the clock and the contents`() {
        val buffer = PcmRingBuffer(capacitySamples = 8)
        buffer.append(ramp(1, 5), 5)
        buffer.clear()

        assertEquals(0L, buffer.totalSamples)
        assertEquals(0, buffer.snapshotLast(4).size)
    }

    @Test
    fun `an empty buffer yields an empty snapshot`() {
        assertEquals(0, PcmRingBuffer(capacitySamples = 8).snapshotLast(4).size)
    }
}
