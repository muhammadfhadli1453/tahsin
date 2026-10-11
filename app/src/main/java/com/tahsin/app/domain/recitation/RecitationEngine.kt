package com.tahsin.app.domain.recitation

import android.util.Log
import com.tahsin.app.audio.MicrophoneRecorder
import com.tahsin.app.domain.gharib.GharibKind
import com.tahsin.app.domain.gharib.GharibVerdict
import com.tahsin.app.domain.gharib.GharibWatch
import com.tahsin.app.domain.gharib.ImalaCheck
import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.model.Mistake
import com.tahsin.app.domain.model.MistakeKind
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.domain.text.ArabicNormalizer
import com.tahsin.app.domain.text.WordSimilarity
import com.tahsin.whisper.WhisperDecodeOptions
import com.tahsin.whisper.WhisperTranscript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val LOG_TAG = "TahsinEngine"

/**
 * Timing and decoding knobs for a live session.
 *
 * Window sizing has to satisfy two pulls at once. A window must hold the word
 * the cursor is waiting for from its first letter, or a madd held for six
 * counts is only ever heard in fragments. But a window must not reach back
 * further than that, because audio that starts in the middle of an earlier,
 * already-recognised word (a held ayah ending, typically) makes whisper produce
 * nonsense for the new words after it.
 *
 * So each pass reaches back the shorter of: everything since the last
 * recognised word (plus [contextSeconds]), or the longest the awaited word could
 * plausibly last. Right after a word is recognised the window is short; it grows
 * only while a long word is still being recited. [maxWindowSeconds] bounds the
 * cost when nothing is being recognised at all.
 */
data class EngineTuning(
    val stepSeconds: Float = 1.2f,
    val contextSeconds: Float = 1.5f,
    val maxWindowSeconds: Float = 8f,
    val minWindowSeconds: Float = 1.0f,
    /** Skip inference entirely after this much continuous silence. */
    val silenceGateMs: Long = 900L,
    /** Emit a "stuck" hint after this long with no forward progress. */
    val hintAfterMs: Long = 4_000L,
    val decode: WhisperDecodeOptions = LiveDecode,
) {
    companion object {
        /**
         * Decoding for short live windows. Temperature fallback is off because a
         * clipped window almost always looks "uncertain" to whisper, which then
         * re-decodes it up to five more times — multiplying the cost of every
         * pass for no gain, since the next overlapping window re-hears the same
         * words anyway. Token timestamps are off because nothing reads them.
         */
        val LiveDecode = WhisperDecodeOptions(
            tokenTimestamps = false,
            temperatureInc = 0f,
            beamSize = 1,
            bestOf = 1,
        )

        /** Favours latency: shorter steps. */
        val Responsive = EngineTuning(stepSeconds = 0.9f, contextSeconds = 1.2f)

        val Balanced = EngineTuning()

        /** Favours accuracy: longer context and beam search. */
        val Accurate = EngineTuning(
            stepSeconds = 1.8f,
            contextSeconds = 2.0f,
            decode = LiveDecode.copy(beamSize = 3),
        )
    }
}

data class EngineSnapshot(
    val state: TrackerState,
    val newMistakes: List<Mistake>,
    val micLevel: Float,
    val isSpeaking: Boolean,
    val elapsedMs: Long,
    /** Set when the reciter has been stalled on [TrackerState.cursor] for a while. */
    val stuckOn: ExpectedWord?,
    val lastTranscript: String,
    val lastConfidence: Float,
)

/** The one capability the engine needs from speech recognition. */
fun interface Transcriber {
    suspend fun transcribe(samples: FloatArray, options: WhisperDecodeOptions): WhisperTranscript
}

/** A transcriber whose transcripts carry word timings, held only while needed. */
class TimedTranscriber(val transcriber: Transcriber, val close: suspend () -> Unit)

/** Opens a [TimedTranscriber], or returns null when this model cannot time words. */
fun interface TimedTranscriberSource {
    suspend fun open(): TimedTranscriber?
}

/**
 * Drives one live recitation: records continuously, transcribes rolling windows
 * on the inference thread, and folds each result into [tracker].
 *
 * With a [timedTranscribers] source, gharib words are also checked acoustically
 * (see [GharibWatch]).
 */
class RecitationEngine(
    private val whisper: Transcriber,
    private val recorder: MicrophoneRecorder,
    private val tracker: RecitationTracker,
    private val tuning: EngineTuning = EngineTuning.Balanced,
    private val timedTranscribers: TimedTranscriberSource? = null,
) {
    private val gharib = GharibWatch(tracker.expected).takeIf { timedTranscribers != null && !it.isEmpty }
    private var timedLoad: Deferred<TimedTranscriber?>? = null
    @Volatile
    private var timedOpened: TimedTranscriber? = null
    @Volatile
    var isPaused: Boolean = false

    private val sampleRate = recorder.sampleRate
    private var lastProcessedSample = 0L

    /**
     * Where the next window must reach back to: the end of the last pass that
     * recognised a word (minus context). Everything after it is unconfirmed.
     */
    private var anchorSample = 0L
    private var lastAdvanceMs = 0L
    private var lastCursor = 0

    val allMistakes = mutableListOf<Mistake>()

    /**
     * Runs until the session completes or the coroutine is cancelled. [onSnapshot]
     * is invoked on the calling coroutine for every tick, including idle ticks so
     * the level meter stays live.
     */
    suspend fun run(
        recordTo: File?,
        onSnapshot: suspend (EngineSnapshot) -> Unit,
    ) {
        recorder.start(recordTo)
        lastProcessedSample = 0L
        anchorSample = 0L
        lastAdvanceMs = 0L
        lastCursor = tracker.state().cursor
        try {
            coroutineScope {
                loop(this, onSnapshot)
                // The passage is over; words heard last still deserve their check.
                if (tracker.state().isComplete) {
                    val last = checkGharib(this, speechPaused = true, finished = true)
                    if (last.isNotEmpty()) {
                        allMistakes += last
                        emit(onSnapshot, last, "", 0f)
                    }
                }
            }
        } finally {
            recorder.stop()
            withContext(NonCancellable) { closeTimedTranscriber() }
        }
    }

    private suspend fun loop(scope: CoroutineScope, onSnapshot: suspend (EngineSnapshot) -> Unit) {
        val stepSamples = (tuning.stepSeconds * sampleRate).toInt()
        val maxWindowSamples = (tuning.maxWindowSeconds * sampleRate).toInt()
        val contextSamples = (tuning.contextSeconds * sampleRate).toInt()
        val minWindowSamples = (tuning.minWindowSeconds * sampleRate).toInt()

        var lastDiagnosticMs = -DIAGNOSTIC_INTERVAL_MS
        while (currentCoroutineContext().isActive) {
            // Throttled view of why a pass did or did not run. Without this, a
            // loop that silently never reaches inference is indistinguishable
            // from one that runs and finds nothing.
            val recordedMs = recorder.recordedMs
            if (recordedMs - lastDiagnosticMs >= DIAGNOSTIC_INTERVAL_MS) {
                lastDiagnosticMs = recordedMs
                Log.i(
                    LOG_TAG,
                    "tick recorded=${recordedMs}ms " +
                        "pending=${(recorder.ring.totalSamples - lastProcessedSample) * 1000 / sampleRate}ms " +
                        "step=${tuning.stepSeconds * 1000}ms " +
                        "silent=${recorder.silentForMs(tuning.silenceGateMs)} " +
                        "level=${"%.4f".format(recorder.currentLevel)} " +
                        "paused=$isPaused complete=${tracker.state().isComplete}",
                )
            }

            // The passage can also complete between passes — the reciter may have
            // skipped the last words — so check before doing any work.
            if (tracker.state().isComplete) return

            if (isPaused) {
                emit(onSnapshot, emptyList(), "", 0f)
                delay(IDLE_TICK_MS)
                continue
            }

            val total = recorder.ring.totalSamples
            val pending = total - lastProcessedSample
            if (pending < stepSamples) {
                emit(onSnapshot, emptyList(), "", 0f)
                delay(IDLE_TICK_MS)
                continue
            }

            // Skip inference only when *everything* recorded since the last pass
            // is silence. Gating on a fixed silence length instead drops the tail
            // of the final word: if the reciter stops just after a pass, the
            // silence threshold is crossed before the next one, so that word is
            // only ever heard clipped — which is how the last word of a passage
            // went unmatched.
            val pendingMs = pending * 1000 / sampleRate
            if (recorder.silentForMs(pendingMs)) {
                // Park the pointer at "now" so that when speech resumes, the first
                // pass waits a full step for real words instead of firing on a
                // window holding a single syllable.
                lastProcessedSample = total
                // The pass before this silence already heard the end of speech
                // in full (see above), so a pause carries nothing forward: start
                // the next window after it rather than dragging silence along,
                // which costs time and invites whisper to hallucinate.
                anchorSample = total
                // A pause right after a gharib word is the natural moment to check it.
                val checked = checkGharib(scope, speechPaused = true, finished = false)
                allMistakes += checked
                emit(onSnapshot, checked, "", 0f)
                delay(IDLE_TICK_MS)
                continue
            }

            val unconfirmed = total - anchorSample + contextSamples
            val awaitedWordSamples = (
                max(tuning.contextSeconds, longestPlausibleSeconds(tracker.state().cursor)) * sampleRate
                ).toLong()
            val windowSamples = min(
                maxWindowSamples.toLong(),
                max(minWindowSamples.toLong(), min(unconfirmed, pending + awaitedWordSamples)),
            ).toInt()
            if (unconfirmed > maxWindowSamples) {
                Log.i(
                    LOG_TAG,
                    "nothing recognised for ${(total - anchorSample) * 1000 / sampleRate}ms; " +
                        "window capped at ${tuning.maxWindowSeconds}s",
                )
            }
            val samples = recorder.ring.snapshotLast(windowSamples)
            if (samples.isEmpty()) {
                delay(IDLE_TICK_MS)
                continue
            }
            val windowStartMs = (total - samples.size) * 1000 / sampleRate
            lastProcessedSample = total

            // Whisper's encoder pays for a full 30 s window regardless of how
            // much audio it is given, which is hopeless for live feedback on a
            // phone. Shrinking audio_ctx to the frames the window actually fills
            // (one encoder frame per 320 samples) cuts the encode roughly in
            // proportion, and is how whisper.cpp's own streaming example runs.
            val windowSeconds = samples.size.toFloat() / sampleRate
            val decode = tuning.decode.copy(
                audioContext = tuning.decode.audioContext.takeIf { it > 0 }
                    ?: (samples.size / 320 + AUDIO_CTX_MARGIN).coerceIn(MIN_AUDIO_CTX, FULL_AUDIO_CTX),
                // Recitation with full tashkeel runs 6-9 tokens per word and a fast
                // reciter manages ~4 words/s, so ~36 tokens/s is the real ceiling.
                // Anything past that is a repetition loop, which also costs seconds.
                maxTokens = tuning.decode.maxTokens.takeIf { it > 0 }
                    ?: (windowSeconds * MAX_TOKENS_PER_SECOND).toInt() + MAX_TOKENS_MARGIN,
            )
            val startedAt = System.nanoTime()
            val transcript = whisper.transcribe(samples, decode)
            val inferenceMs = (System.nanoTime() - startedAt) / 1_000_000

            // Log before the cancellation check: a session that ends while its
            // first (slow) inference is still running must still leave evidence
            // of what that inference was doing, or the log shows nothing at all.
            Log.i(
                LOG_TAG,
                "window=${samples.size * 1000 / sampleRate}ms inference=${inferenceMs}ms " +
                    "threads=${decode.threads} ctx=${decode.audioContext} maxTok=${decode.maxTokens} " +
                    "conf=${"%.2f".format(transcript.confidence)} " +
                    "noSpeech=${"%.2f".format(transcript.noSpeechProb)} " +
                    "cursor=${tracker.state().cursor}/${tracker.size} " +
                    "heard=\"${transcript.text}\"",
            )
            if (!currentCoroutineContext().isActive) return

            if (inferenceMs > samples.size * 1000L / sampleRate) {
                Log.w(
                    LOG_TAG,
                    "inference (${inferenceMs}ms) is slower than the audio it covers; " +
                        "feedback will lag. Consider a smaller model or the Responsive profile.",
                )
            }

            val verdicts = if (transcript.isEmpty) {
                emptyList()
            } else {
                val cursorBefore = tracker.state().cursor
                val anchorBefore = anchorSample
                val update = tracker.onTranscript(
                    transcript = transcript.text,
                    confidence = transcript.confidence,
                    windowStartMs = windowStartMs,
                    deferVerdicts = true,
                )
                // Only a pass that recognised something moves the anchor. A pass
                // that heard a fragment leaves it put, so the next, longer window
                // still contains that word from its first letter.
                if (update.advancedBy > 0) anchorSample = total
                if (update.loopTrimmed) Log.i(LOG_TAG, "repetition loop trimmed; used for matches only")
                val jumpedOver = (cursorBefore until update.state.cursor)
                    .any { update.state.statuses[it] != WordStatus.Correct }
                if (jumpedOver) {
                    recheck(from = anchorBefore - contextSamples, to = total, maxWindowSamples)
                }
                // The word may have been heard by this pass or by its recheck,
                // which reaches back to the last confirmed word.
                gharib?.onPass(
                    cursor = tracker.state().cursor,
                    windowStartSample = min(total - samples.size, anchorBefore - contextSamples).coerceAtLeast(0),
                )
                update.newMistakes + tracker.takeVerdicts(transcript.confidence)
            }
            val newMistakes = verdicts + checkGharib(scope, speechPaused = false, finished = false)
            allMistakes += newMistakes

            emit(onSnapshot, newMistakes, transcript.text, transcript.confidence)
            if (tracker.state().isComplete) return
        }
    }

    /**
     * A second listen over `[from, to)` before any word is judged.
     *
     * Short windows cut words at their edges, and a short word squeezed between
     * two others can be clipped in every window that contains it — so the
     * cursor jumps over it, and it would be reported as missed although it was
     * recited. Transcribing the whole stretch since the last confirmed word in
     * one go gives whisper the surrounding context it lacked. The tracker keeps
     * the best score each word ever got, so whatever this pass hears can only
     * clear a verdict, never create a worse one. Costs one extra pass, and only
     * when the cursor jumps.
     */
    private suspend fun recheck(from: Long, to: Long, maxWindowSamples: Int) {
        val span = (to - from.coerceAtLeast(0)).coerceIn(0, maxWindowSamples.toLong()).toInt()
        val samples = recorder.ring.snapshotLast(span)
        if (samples.isEmpty()) return
        val windowSeconds = samples.size.toFloat() / sampleRate
        val decode = tuning.decode.copy(
            audioContext = (samples.size / 320 + AUDIO_CTX_MARGIN).coerceIn(MIN_AUDIO_CTX, FULL_AUDIO_CTX),
            maxTokens = (windowSeconds * MAX_TOKENS_PER_SECOND).toInt() + MAX_TOKENS_MARGIN,
        )
        val transcript = whisper.transcribe(samples, decode)
        Log.i(LOG_TAG, "recheck window=${samples.size * 1000 / sampleRate}ms heard=\"${transcript.text}\"")
        if (transcript.isEmpty) return
        tracker.onTranscript(
            transcript = transcript.text,
            confidence = transcript.confidence,
            windowStartMs = (to - samples.size) * 1000 / sampleRate,
            deferVerdicts = true,
        )
    }

    /**
     * Opens the word-timing transcriber in the background as the cursor enters
     * a gharib word's ayah, checks each gharib word once it has been heard, and
     * closes the transcriber again when no check is pending.
     */
    private suspend fun checkGharib(scope: CoroutineScope, speechPaused: Boolean, finished: Boolean): List<Mistake> {
        val watch = gharib ?: return emptyList()
        val source = timedTranscribers ?: return emptyList()
        val cursor = tracker.state().cursor
        if (watch.wantsAligner(cursor) && timedLoad == null) {
            Log.i(LOG_TAG, "gharib ahead: opening word timings")
            // Kept in a field as well: a cancelled Deferred drops its result, and
            // the native context it holds must still be freed.
            timedLoad = scope.async(Dispatchers.IO) {
                withContext(NonCancellable) { source.open().also { timedOpened = it } }
            }
        }
        val due = watch.due(cursor, speechPaused, finished)
        val mistakes = mutableListOf<Mistake>()
        for (target in due) {
            watch.markChecked(target)
            // A word not recited right is already reported as such; how it was
            // voiced only matters for a word that was.
            if (tracker.state().statuses[target.word.position] != WordStatus.Correct) continue
            val timed = timedLoad?.await() ?: continue
            checkOne(target, timed.transcriber)?.let { mistakes += it }
        }
        if (!watch.wantsAligner(tracker.state().cursor)) closeTimedTranscriber()
        return mistakes
    }

    private suspend fun checkOne(target: GharibWatch.Target, transcriber: Transcriber): Mistake? {
        val total = recorder.ring.totalSamples
        val clipStart = max(target.clipStartSample, total - MAX_GHARIB_CLIP_SECONDS * sampleRate)
        val clip = recorder.ring.snapshotLast((total - clipStart).toInt())
        if (clip.isEmpty()) return null
        val clipStartMs = (total - clip.size) * 1000 / sampleRate
        val transcript = transcriber.transcribe(
            clip,
            tuning.decode.copy(
                // Word timings come from cross-attention over these frames; they
                // must cover the whole clip (whisper asserts as much).
                audioContext = (clip.size / 320 + AUDIO_CTX_MARGIN).coerceIn(MIN_AUDIO_CTX, FULL_AUDIO_CTX),
                maxTokens = (clip.size.toFloat() / sampleRate * MAX_TOKENS_PER_SECOND).toInt() + MAX_TOKENS_MARGIN,
            ),
        )
        val words = transcript.words
        // The latest saying of the word: a reciter who repeats it to correct
        // themselves is judged on the repeat.
        val at = words.indices.lastOrNull { i ->
            WordSimilarity.best(ArabicNormalizer.normalize(words[i].text), target.word.variants) >= GHARIB_WORD_MATCH
        }
        if (at == null) {
            Log.i(LOG_TAG, "gharib ${target.word.uthmani}: not found in \"${transcript.text}\"")
            return null
        }
        val word = words[at]
        val startMs = (word.startMs - WORD_LEAD_MS).coerceAtLeast(0)
        val endMs = min(words.getOrNull(at + 1)?.startMs ?: Long.MAX_VALUE, word.lastTokenMs + WORD_TAIL_MS)
        val from = (startMs * sampleRate / 1000).toInt().coerceIn(0, clip.size)
        val to = (endMs.coerceAtMost(clip.size * 1000L / sampleRate) * sampleRate / 1000).toInt().coerceIn(from, clip.size)
        val measurement = when (target.kind) {
            GharibKind.Imala -> ImalaCheck.measure(clip.copyOfRange(from, to), sampleRate)
        }
        Log.i(
            LOG_TAG,
            "gharib ${target.kind} ${target.word.surahId}:${target.word.ayahNumber} ${target.word.uthmani} " +
                "at ${clipStartMs + startMs}-${clipStartMs + endMs}ms deltaF2=${measurement.deltasHz.map { it?.toInt() }} " +
                "-> ${measurement.verdict}",
        )
        if (measurement.verdict != GharibVerdict.Wrong) return null
        tracker.markPronunciationFault(target.word.position)
        return Mistake(
            kind = MistakeKind.Pronunciation,
            surahId = target.word.surahId,
            ayahNumber = target.word.ayahNumber,
            wordIndex = target.word.wordIndex,
            expected = target.word.uthmani,
            heard = null,
            atMs = clipStartMs + startMs,
            confidence = transcript.confidence,
            note = target.kind.guidance,
        )
    }

    private suspend fun closeTimedTranscriber() {
        val load = timedLoad ?: return
        timedLoad = null
        // An open still in flight finishes first; whatever it opened is closed.
        load.join()
        timedOpened?.close?.invoke()
        timedOpened = null
    }

    /**
     * How long the word at [position] could take to recite, generously. Ordinary
     * words take about a second. A maddah mark (U+0653) means a madd of four to
     * six counts, and the last word of an ayah is usually held when the reciter
     * stops there — both can push a single word past three seconds.
     */
    private fun longestPlausibleSeconds(position: Int): Float {
        val word = tracker.expected.getOrNull(position) ?: return 0f
        val endsAyah = tracker.expected.getOrNull(position + 1)?.ayahNumber != word.ayahNumber
        var seconds = WORD_BASE_SECONDS + WORD_SECONDS_PER_LETTER * word.normalized.length
        if (word.uthmani.indexOf(MADDAH) >= 0) seconds += LONG_MADD_SECONDS
        if (endsAyah) seconds += STOPPING_MADD_SECONDS
        return seconds
    }

    private suspend fun emit(
        onSnapshot: suspend (EngineSnapshot) -> Unit,
        newMistakes: List<Mistake>,
        transcript: String,
        confidence: Float,
    ) {
        val state = tracker.state()
        val elapsed = recorder.recordedMs
        if (state.cursor != lastCursor) {
            lastCursor = state.cursor
            lastAdvanceMs = elapsed
        }
        val stalledMs = elapsed - lastAdvanceMs
        val stuckOn = if (!isPaused && stalledMs >= tuning.hintAfterMs) tracker.peek() else null

        onSnapshot(
            EngineSnapshot(
                state = state,
                newMistakes = newMistakes,
                micLevel = recorder.currentLevel,
                isSpeaking = !recorder.silentForMs(tuning.silenceGateMs),
                elapsedMs = elapsed,
                stuckOn = stuckOn,
                lastTranscript = transcript,
                lastConfidence = confidence,
            ),
        )
    }

    private companion object {
        const val IDLE_TICK_MS = 120L
        const val DIAGNOSTIC_INTERVAL_MS = 2_000L

        /** Encoder frames: whisper uses 1500 for its fixed 30 s window. */
        const val FULL_AUDIO_CTX = 1500
        const val MIN_AUDIO_CTX = 128
        /** Headroom so trailing phonemes are not cut off at the window edge. */
        const val AUDIO_CTX_MARGIN = 32
        const val WORD_BASE_SECONDS = 0.35f
        const val WORD_SECONDS_PER_LETTER = 0.11f
        const val LONG_MADD_SECONDS = 2.5f
        const val STOPPING_MADD_SECONDS = 2.0f
        const val MADDAH = '\u0653'
        const val MAX_TOKENS_PER_SECOND = 30
        const val MAX_TOKENS_MARGIN = 16

        /** Longest stretch a gharib check transcribes; well inside the 40 s ring. */
        const val MAX_GHARIB_CLIP_SECONDS = 20L
        const val GHARIB_WORD_MATCH = 0.7
        /** Word onsets from DTW run slightly late, and a word outlasts its last token's onset. */
        const val WORD_LEAD_MS = 100L
        const val WORD_TAIL_MS = 500L
    }
}
