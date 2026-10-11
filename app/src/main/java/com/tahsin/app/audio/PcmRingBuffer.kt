package com.tahsin.app.audio

import kotlin.math.min

/**
 * Fixed-capacity ring of 16-bit PCM samples shared between the recorder thread
 * and the inference coroutine.
 *
 * The recorder must never block on inference — that is what made earlier
 * versions drop audio (and therefore words) for the whole duration of each
 * Whisper call. The recorder only ever appends here; the consumer takes
 * snapshots of the most recent audio whenever it is free.
 */
class PcmRingBuffer(capacitySamples: Int) {
    private val buffer = ShortArray(capacitySamples)
    private val capacity = capacitySamples
    private var writePosition = 0

    /** Total samples ever written, which doubles as a monotonic audio clock. */
    @Volatile
    var totalSamples: Long = 0
        private set

    @Synchronized
    fun append(samples: ShortArray, count: Int) {
        var offset = 0
        var remaining = min(count, samples.size)
        while (remaining > 0) {
            val chunk = min(remaining, capacity - writePosition)
            System.arraycopy(samples, offset, buffer, writePosition, chunk)
            writePosition = (writePosition + chunk) % capacity
            offset += chunk
            remaining -= chunk
        }
        totalSamples += min(count, samples.size)
    }

    /**
     * Copies the most recent [count] samples as floats in `-1f..1f`.
     * Returns fewer samples if that much audio has not been recorded yet.
     */
    @Synchronized
    fun snapshotLast(count: Int): FloatArray {
        val available = min(min(count, capacity), totalSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (available <= 0) return FloatArray(0)
        val out = FloatArray(available)
        var readPosition = ((writePosition - available) % capacity + capacity) % capacity
        for (i in 0 until available) {
            out[i] = buffer[readPosition] / PCM_SCALE
            readPosition++
            if (readPosition == capacity) readPosition = 0
        }
        return out
    }

    @Synchronized
    fun clear() {
        writePosition = 0
        totalSamples = 0
        buffer.fill(0)
    }

    private companion object {
        const val PCM_SCALE = 32768f
    }
}
