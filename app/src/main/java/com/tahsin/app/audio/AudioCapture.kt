package com.tahsin.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

class AudioCapture(
    private val sampleRate: Int = 16_000,
    private val chunkSeconds: Float = 2.5f,
    private val overlapSeconds: Float = 0.5f,
    private val silenceThreshold: Float = 0.008f,
) {
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    fun captureChunks(): Flow<FloatArray> = flow {
        val chunkSamples = (sampleRate * chunkSeconds).toInt()
        val overlapSamples = (sampleRate * overlapSeconds).toInt()
        val bufferSize = maxOf(minBufferSize, chunkSamples * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Microphone unavailable")
        }

        val pcmBuffer = ShortArray(chunkSamples)
        val overlapBuffer = ShortArray(overlapSamples)
        val ring = FloatArray(chunkSamples)
        var ringPos = 0

        recorder.startRecording()
        try {
            while (currentCoroutineContext().isActive) {
                var readTotal = 0
                if (overlapSamples > 0) {
                    System.arraycopy(overlapBuffer, 0, pcmBuffer, 0, overlapSamples)
                    readTotal = overlapSamples
                }
                while (readTotal < chunkSamples && currentCoroutineContext().isActive) {
                    val read = recorder.read(pcmBuffer, readTotal, chunkSamples - readTotal)
                    if (read <= 0) continue
                    readTotal += read
                }

                for (i in 0 until chunkSamples) {
                    ring[ringPos] = pcmBuffer[i] / 32768f
                    ringPos = (ringPos + 1) % chunkSamples
                }

                val chunk = FloatArray(chunkSamples)
                for (i in 0 until chunkSamples) {
                    chunk[i] = ring[(ringPos + i) % chunkSamples]
                }

                if (rms(chunk) >= silenceThreshold) {
                    emit(chunk)
                }

                if (overlapSamples > 0) {
                    System.arraycopy(
                        pcmBuffer,
                        chunkSamples - overlapSamples,
                        overlapBuffer,
                        0,
                        overlapSamples,
                    )
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size).toFloat()
    }

    companion object {
        fun pcm16ToFloat(pcm: ShortArray, length: Int = pcm.size): FloatArray {
            return FloatArray(length) { index -> pcm[index] / 32768f }
        }
    }
}

object AudioCaptureFactory {
    fun create(): AudioCapture = AudioCapture()
}
