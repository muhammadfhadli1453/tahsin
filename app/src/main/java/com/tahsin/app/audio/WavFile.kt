package com.tahsin.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit mono PCM to a WAV file.
 *
 * A RIFF header declares the size of the audio that follows, which is only known
 * once recording ends. Writing it solely on [close] means any abnormal ending —
 * the process being killed, the battery dying, a crash — leaves a file that
 * contains perfectly good audio but declares zero bytes of it, so every player
 * reports a duration of zero. The header is therefore refreshed as the recording
 * grows, which costs one seek and 44 bytes every few seconds and keeps the file
 * playable at all times.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var bytesSinceHeaderRefresh = 0L
    private var closed = false

    init {
        output.setLength(0)
        output.write(header(sampleRate, 0))
    }

    val bytesWritten: Long get() = dataBytes

    @Synchronized
    fun write(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        output.seek(HEADER_SIZE + dataBytes)
        output.write(bytes.array())
        dataBytes += count * 2L
        bytesSinceHeaderRefresh += count * 2L
        if (bytesSinceHeaderRefresh >= HEADER_REFRESH_INTERVAL_BYTES) {
            refreshHeader()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { refreshHeader() }
        runCatching { output.close() }
    }

    private fun refreshHeader() {
        output.seek(0)
        output.write(header(sampleRate, dataBytes))
        bytesSinceHeaderRefresh = 0
    }

    companion object {
        const val HEADER_SIZE = 44L

        /** Roughly every two seconds of 16 kHz mono audio. */
        private const val HEADER_REFRESH_INTERVAL_BYTES = 64_000L

        /**
         * Repairs a WAV whose header under-reports its audio, which is what an
         * older recording — or one interrupted before the fix landed — looks
         * like. Returns true if the file was changed.
         */
        fun repairIfTruncated(file: File, sampleRate: Int = 16_000): Boolean {
            if (!file.isFile || file.length() <= HEADER_SIZE) return false
            val actualDataBytes = file.length() - HEADER_SIZE
            return runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    val head = ByteArray(HEADER_SIZE.toInt())
                    raf.readFully(head)
                    if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return false
                    val declared = ByteBuffer.wrap(head, 40, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    if (declared >= actualDataBytes) return false
                    val rate = ByteBuffer.wrap(head, 24, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int.takeIf { it > 0 } ?: sampleRate
                    raf.seek(0)
                    raf.write(header(rate, actualDataBytes))
                    true
                }
            }.getOrDefault(false)
        }

        private fun header(sampleRate: Int, dataBytes: Long): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            return ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt((36 + dataBytes).toInt())
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray())
                putInt(dataBytes.toInt())
            }.array()
        }
    }
}
