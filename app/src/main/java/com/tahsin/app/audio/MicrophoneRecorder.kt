package com.tahsin.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

private const val LOG_TAG = "TahsinAudio"

class MicrophoneUnavailableException(message: String) : IllegalStateException(message)

/**
 * Continuously records the microphone on a dedicated thread into a
 * [PcmRingBuffer], optionally mirroring the audio to a WAV file.
 *
 * Nothing here ever waits on speech recognition. Inference reads snapshots at
 * whatever rate it can manage, so a slow model degrades window freshness rather
 * than losing audio.
 */
open class MicrophoneRecorder(
    val sampleRate: Int = 16_000,
    bufferSeconds: Int = 40,
) {
    val ring = PcmRingBuffer(sampleRate * bufferSeconds)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var wavWriter: WavFileWriter? = null
    private val effects = mutableListOf<AutoCloseable>()

    /** Short-term loudness in `0f..1f`, for the level meter and silence gating. */
    @Volatile
    var currentLevel: Float = 0f
        private set

    @Volatile
    var lastVoiceAtSample: Long = 0L
        private set

    val recordedMs: Long get() = ring.totalSamples * 1000 / sampleRate

    val isRecording: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    open fun start(recordTo: File? = null) {
        if (!running.compareAndSet(false, true)) return
        ring.clear()
        currentLevel = 0f
        lastVoiceAtSample = 0L

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            running.set(false)
            throw MicrophoneUnavailableException("This device reports no usable microphone buffer")
        }
        // A generous hardware buffer means a scheduling hiccup does not cost audio.
        val bufferBytes = maxOf(minBuffer * 4, sampleRate) // >= 0.5 s of 16-bit audio

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            running.set(false)
            throw MicrophoneUnavailableException("Microphone permission was denied")
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            throw MicrophoneUnavailableException("Another app is using the microphone")
        }

        enableEffects(record.audioSessionId)
        recorder = record
        wavWriter = recordTo?.let { runCatching { WavFileWriter(it, sampleRate) }.getOrNull() }

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stop()
            throw MicrophoneUnavailableException("The microphone could not be started")
        }

        thread = Thread({ readLoop(record) }, "tahsin-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    open fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(1_000)
        thread = null
        recorder?.runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        recorder = null
        effects.forEach { it.runCatching { close() } }
        effects.clear()
        wavWriter?.close()
        wavWriter = null
        currentLevel = 0f
    }

    private fun readLoop(record: AudioRecord) {
        val frame = ShortArray(sampleRate / 10) // 100 ms
        while (running.get()) {
            val read = record.read(frame, 0, frame.size)
            if (read <= 0) {
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                    Log.w(LOG_TAG, "AudioRecord.read returned $read, stopping capture")
                    break
                }
                continue
            }
            ring.append(frame, read)
            wavWriter?.write(frame, read)

            val level = rms(frame, read)
            // Smooth the meter but let it fall quickly, so the UI tracks speech.
            currentLevel = if (level > currentLevel) level else currentLevel * 0.8f + level * 0.2f
            if (level >= VOICE_RMS_THRESHOLD) {
                lastVoiceAtSample = ring.totalSamples
            }
        }
    }

    private fun enableEffects(sessionId: Int) {
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    it.enabled = true
                    effects += AutoCloseable { it.release() }
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let {
                    it.enabled = true
                    effects += AutoCloseable { it.release() }
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let {
                    it.enabled = true
                    effects += AutoCloseable { it.release() }
                }
            }
        }.onFailure { Log.w(LOG_TAG, "Audio effects unavailable: ${it.message}") }
    }

    /** True when nothing above the voice threshold has been heard for [ms]. */
    open fun silentForMs(ms: Long): Boolean {
        val silentSamples = ring.totalSamples - lastVoiceAtSample
        return silentSamples * 1000 / sampleRate >= ms
    }

    private fun rms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val value = samples[i] / 32768.0
            sum += value * value
        }
        return sqrt(sum / count).toFloat()
    }

    companion object {
        /** Below this short-term RMS the window is treated as silence. */
        const val VOICE_RMS_THRESHOLD = 0.012f
    }
}
