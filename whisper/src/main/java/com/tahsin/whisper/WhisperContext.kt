package com.tahsin.whisper

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "TahsinWhisper"

/**
 * Decoding options for a single window of audio.
 *
 * [initialPrompt] deliberately defaults to `null`. Priming Whisper with the text
 * the reciter is *expected* to say makes the decoder reproduce that text almost
 * regardless of what was actually recited, which silently destroys mistake
 * detection. Only pass a prompt that is independent of the expected words.
 */
data class WhisperDecodeOptions(
    val language: String = "ar",
    val initialPrompt: String? = null,
    val singleSegment: Boolean = true,
    val noContext: Boolean = true,
    val tokenTimestamps: Boolean = true,
    val maxLen: Int = 0,
    val splitOnWord: Boolean = true,
    val beamSize: Int = 1,
    val bestOf: Int = 2,
    val temperature: Float = 0f,
    val temperatureInc: Float = 0.2f,
    val noSpeechThreshold: Float = 0.6f,
    val entropyThreshold: Float = 2.4f,
    val logProbThreshold: Float = -1.0f,
    val suppressNonSpeech: Boolean = true,
    val audioContext: Int = 0,
    /** Upper bound on tokens per segment; 0 leaves whisper's own limit. */
    val maxTokens: Int = 0,
    val threads: Int = WhisperCpuConfig.preferredThreadCount,
)

data class WhisperSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Mean probability of the segment's text tokens, in `0f..1f`. */
    val avgTokenProb: Float,
    val noSpeechProb: Float,
)

/**
 * A word with its timing inside the transcribed window. [startMs] is the onset
 * of its first token and [lastTokenMs] the onset of its last, so the word itself
 * runs somewhat past [lastTokenMs] — up to the next word's [startMs] at most.
 */
data class WhisperWord(
    val text: String,
    val startMs: Long,
    val lastTokenMs: Long,
)

data class WhisperTranscript(
    val text: String,
    val segments: List<WhisperSegment>,
    /** Timed words; only filled by a context opened with word timings. */
    val words: List<WhisperWord> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank()

    /** Weighted mean acoustic confidence across segments, in `0f..1f`. */
    val confidence: Float
        get() {
            if (segments.isEmpty()) return 0f
            var weightSum = 0f
            var acc = 0f
            for (segment in segments) {
                val weight = segment.text.length.coerceAtLeast(1).toFloat()
                acc += segment.avgTokenProb * weight
                weightSum += weight
            }
            return if (weightSum == 0f) 0f else acc / weightSum
        }

    /** Highest no-speech probability reported for any segment. */
    val noSpeechProb: Float
        get() = segments.maxOfOrNull { it.noSpeechProb } ?: 1f

    companion object {
        val EMPTY = WhisperTranscript("", emptyList())
    }
}

/**
 * A loaded Whisper model. Every native call is confined to one dedicated thread,
 * because a `whisper_context` is not safe to use concurrently.
 */
class WhisperContext private constructor(
    private var ptr: Long,
    val modelPath: String,
    /** Whether transcripts carry [WhisperTranscript.words]. */
    val hasWordTimings: Boolean = false,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "whisper-inference").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val released = AtomicBoolean(false)

    val isMultilingual: Boolean by lazy { WhisperLib.isMultilingual(ptr) }

    /**
     * Transcribes one window. Returns [WhisperTranscript.EMPTY] rather than
     * throwing when the context has been released — a session being torn down
     * can race a window that is already in flight, and that is not an error.
     */
    suspend fun transcribe(
        samples: FloatArray,
        options: WhisperDecodeOptions = WhisperDecodeOptions(),
    ): WhisperTranscript {
        if (released.get() || ptr == 0L) return WhisperTranscript.EMPTY
        // Whisper pads to 30s internally but needs at least one mel frame of audio.
        if (samples.size < MIN_SAMPLES) return WhisperTranscript.EMPTY
        return try {
            decode(samples, options)
        } catch (_: RejectedExecutionException) {
            // release() shut the inference executor down while this was queueing.
            WhisperTranscript.EMPTY
        } catch (e: CancellationException) {
            // The executor rejects by cancelling the continuation, so a release
            // race arrives here too. A genuine caller cancellation must propagate.
            if (released.get()) WhisperTranscript.EMPTY else throw e
        }
    }

    private suspend fun decode(
        samples: FloatArray,
        options: WhisperDecodeOptions,
    ): WhisperTranscript = withContext(dispatcher) {
        val handle = ptr
        if (handle == 0L || released.get()) return@withContext WhisperTranscript.EMPTY

        val status = WhisperLib.fullTranscribe(
            handle,
            samples,
            options.threads,
            options.language,
            options.initialPrompt,
            options.singleSegment,
            options.noContext,
            options.tokenTimestamps,
            options.maxLen,
            options.splitOnWord,
            options.beamSize,
            options.bestOf,
            options.temperature,
            options.temperatureInc,
            options.noSpeechThreshold,
            options.entropyThreshold,
            options.logProbThreshold,
            options.suppressNonSpeech,
            options.audioContext,
            options.maxTokens,
        )
        if (status != 0) return@withContext WhisperTranscript.EMPTY

        val count = WhisperLib.getSegmentCount(handle)
        val segments = ArrayList<WhisperSegment>(count)
        for (i in 0 until count) {
            segments += WhisperSegment(
                text = WhisperLib.getSegmentText(handle, i),
                startMs = WhisperLib.getSegmentStartMs(handle, i),
                endMs = WhisperLib.getSegmentEndMs(handle, i),
                avgTokenProb = WhisperLib.getSegmentAvgTokenProb(handle, i),
                noSpeechProb = WhisperLib.getSegmentNoSpeechProb(handle, i),
            )
        }
        WhisperTranscript(
            text = segments.joinToString(separator = " ") { it.text.trim() }.trim(),
            segments = segments,
            words = if (hasWordTimings) timedWords(handle, count) else emptyList(),
        )
    }

    /**
     * Rebuilds words from tokens. Whisper's tokens are byte pieces, and a token
     * that begins with a space begins a word; the bytes are only decoded once a
     * word is whole, because a token can split an Arabic letter's UTF-8.
     */
    private fun timedWords(handle: Long, segmentCount: Int): List<WhisperWord> {
        val words = mutableListOf<WhisperWord>()
        val bytes = java.io.ByteArrayOutputStream()
        var start = -1L
        var last = -1L
        fun flush() {
            val text = bytes.toString(Charsets.UTF_8.name()).trim()
            if (text.isNotEmpty() && start >= 0) words += WhisperWord(text, start, last)
            bytes.reset()
            start = -1L
        }
        for (segment in 0 until segmentCount) {
            for (token in 0 until WhisperLib.getSegmentTokenCount(handle, segment)) {
                val at = WhisperLib.getTokenDtwMs(handle, segment, token)
                if (at < 0) continue
                val piece = WhisperLib.getTokenBytes(handle, segment, token)
                if (piece.isNotEmpty() && piece[0] == SPACE) flush()
                if (start < 0) start = at
                last = at
                bytes.write(piece)
            }
        }
        flush()
        return words
    }

    /**
     * Opens a second context on the same model that also reports per-word times,
     * or returns null when this model's size has no known alignment heads.
     * Word timings need flash attention off, which slows every pass, so the live
     * context never carries them; open this only for the moments that need it.
     */
    fun openWithWordTimings(): WhisperContext? {
        val preset = alignmentPreset()
        if (preset == ALIGNMENT_NONE || released.get()) return null
        val handle = WhisperLib.initContext(modelPath, false, preset)
        if (handle == 0L) {
            Log.w(LOG_TAG, "Could not open $modelPath with word timings")
            return null
        }
        return WhisperContext(handle, modelPath, hasWordTimings = true)
    }

    /** whisper.cpp's alignment-head preset for this model's size (enum whisper_alignment_heads_preset). */
    private fun alignmentPreset(): Int {
        val multilingual = isMultilingual
        return when (WhisperLib.getTextLayerCount(ptr)) {
            4 -> if (multilingual) 4 else 3 // tiny / tiny.en
            6 -> if (multilingual) 6 else 5 // base / base.en
            12 -> if (multilingual) 8 else 7 // small / small.en
            24 -> if (multilingual) 10 else 9 // medium / medium.en
            else -> ALIGNMENT_NONE // large variants differ in heads; not needed here
        }
    }

    /** Idempotent. Safe to call from any thread. */
    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        withContext(dispatcher) {
            if (ptr != 0L) {
                WhisperLib.freeContext(ptr)
                ptr = 0L
            }
        }
        executor.shutdown()
    }

    companion object {
        private const val MIN_SAMPLES = 16_000 / 10 // 100 ms at 16 kHz
        private const val ALIGNMENT_NONE = 0
        private const val SPACE = ' '.code.toByte()

        fun createFromFile(path: String, useGpu: Boolean = false): WhisperContext {
            val file = File(path)
            if (!file.exists()) {
                throw WhisperModelException("Model file not found: $path")
            }
            if (file.length() < 1_000_000L) {
                throw WhisperModelException(
                    "Model file looks truncated (${file.length()} bytes): $path",
                )
            }
            val ptr = WhisperLib.initContext(path, useGpu, ALIGNMENT_NONE)
            if (ptr == 0L) {
                throw WhisperModelException(
                    "Whisper could not load the model at $path. " +
                        "It may be corrupt or not a ggml/gguf Whisper model.",
                )
            }
            Log.i(LOG_TAG, "Loaded Whisper model ${file.name} (${file.length() / 1_048_576} MB)")
            return WhisperContext(ptr, path)
        }

        fun systemInfo(): String = WhisperLib.getSystemInfo()
    }
}

class WhisperModelException(message: String) : IllegalStateException(message)

object WhisperCpuConfig {
    /**
     * whisper.cpp scales poorly past the number of performance cores, and pinning
     * every core starves the audio thread, so leave at least one core free.
     */
    val preferredThreadCount: Int
        get() = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
}

internal object WhisperLib {
    init {
        // A single library for every ABI. ggml-cpu detects fp16/dotprod support
        // at runtime, so there is no per-microarchitecture variant to choose —
        // and the variant that used to exist here hung on some ARM cores.
        Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS.firstOrNull()}, loading libwhisper.so")
        System.loadLibrary("whisper")
    }

    /** [alignmentPreset] > 0 turns on DTW token times (and turns off flash attention). */
    external fun initContext(modelPath: String, useGpu: Boolean, alignmentPreset: Int): Long
    external fun freeContext(contextPtr: Long)

    @Suppress("LongParameterList")
    external fun fullTranscribe(
        contextPtr: Long,
        audioData: FloatArray,
        numThreads: Int,
        language: String?,
        initialPrompt: String?,
        singleSegment: Boolean,
        noContext: Boolean,
        tokenTimestamps: Boolean,
        maxLen: Int,
        splitOnWord: Boolean,
        beamSize: Int,
        bestOf: Int,
        temperature: Float,
        temperatureInc: Float,
        noSpeechThold: Float,
        entropyThold: Float,
        logProbThold: Float,
        suppressNonSpeech: Boolean,
        audioCtx: Int,
        maxTokens: Int,
    ): Int

    external fun getSegmentCount(contextPtr: Long): Int
    external fun getSegmentText(contextPtr: Long, index: Int): String
    external fun getSegmentStartMs(contextPtr: Long, index: Int): Long
    external fun getSegmentEndMs(contextPtr: Long, index: Int): Long
    external fun getSegmentNoSpeechProb(contextPtr: Long, index: Int): Float
    external fun getSegmentAvgTokenProb(contextPtr: Long, index: Int): Float
    external fun getSegmentTokenCount(contextPtr: Long, index: Int): Int
    external fun getTokenText(contextPtr: Long, segment: Int, token: Int): String
    external fun getTokenProb(contextPtr: Long, segment: Int, token: Int): Float
    external fun getTokenStartMs(contextPtr: Long, segment: Int, token: Int): Long
    external fun getTokenDtwMs(contextPtr: Long, segment: Int, token: Int): Long
    external fun getTokenBytes(contextPtr: Long, segment: Int, token: Int): ByteArray
    external fun getTextLayerCount(contextPtr: Long): Int
    external fun isMultilingual(contextPtr: Long): Boolean
    external fun getSystemInfo(): String

}
