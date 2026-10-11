package com.tahsin.app

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tahsin.app.audio.MicrophoneRecorder
import com.tahsin.app.data.quran.QuranDatabase
import com.tahsin.app.data.quran.QuranRepository
import com.tahsin.app.domain.gharib.GharibVerdict
import com.tahsin.app.domain.gharib.ImalaCheck
import com.tahsin.app.domain.model.AyahRange
import com.tahsin.app.domain.model.MistakeKind
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.domain.recitation.RecitationEngine
import com.tahsin.app.domain.recitation.RecitationTracker
import com.tahsin.app.domain.recitation.TimedTranscriber
import com.tahsin.app.domain.recitation.TimedTranscriberSource
import com.tahsin.app.domain.text.ArabicNormalizer
import com.tahsin.app.settings.TahsinSettings
import com.tahsin.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * End-to-end check that the recitation cursor follows real recitation, on the
 * device, through the exact pipeline a live session uses: the real Whisper
 * model, the real engine and tracker, and the app's default settings.
 *
 * The only stand-in is the microphone. [ReplayRecorder] feeds a WAV of real
 * recitation into the engine at real-time speed, 100 ms at a time — the same
 * way AudioRecord delivers it — so recognition has to keep up with live audio
 * exactly as it would with a person reciting.
 *
 * Fixtures live in the app's private files (push with `run-as`):
 *   files/test-fixtures/alafasy-fatihah.wav   Al-Fatihah 1-7, 16 kHz mono
 *   files/test-fixtures/user-bismillah.wav    1:1 recorded on a phone mic
 *   files/test-fixtures/husary-011041.wav     11:41 (imala in مَجْر۪ىٰهَا)
 *   files/test-fixtures/husary-079043.wav     79:43 (ذِكْرَاهَا, no imala)
 * and a model in files/models/. Tests skip when either is missing.
 *
 * Results are logged with tag TahsinFollow.
 */
@RunWith(AndroidJUnit4::class)
class RecitationFollowingTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cursorFollowsAPhoneMicRecitationOfTheBismillah() =
        replay("user-bismillah.wav", AyahRange(1, 1, 1), minCorrectFraction = 0.75)

    @Test
    fun cursorFollowsAFullFatihahAcrossAyahBoundaries() =
        replay("alafasy-fatihah.wav", AyahRange(1, 1, 7), minCorrectFraction = 0.85)

    /** 11:41 by Husary, imala and all: the gharib check must run, and must not accuse him. */
    @Test
    fun gharibImalaIsCheckedAndAMastersReadingPasses() =
        replay("husary-011041.wav", AyahRange(11, 41, 41), minCorrectFraction = 0.75, checkGharib = true)

    /**
     * The check's measurement on 79:43 ذِكْرَاهَا, which ends like مجراها but is
     * read without imala — so had it carried the imala mark, it must be caught.
     * Runs the real word-timing model to locate the word, as a session does.
     */
    @Test
    fun plainRaWhereImalaBelongsWouldBeCaught() {
        val wav = File(context.filesDir, "test-fixtures/husary-079043.wav")
        val model = File(context.filesDir, "models").listFiles { f -> f.extension == "bin" }
            ?.maxByOrNull { it.length() }
        assumeTrue("missing fixture ${wav.path}", wav.exists())
        assumeTrue("no model in files/models", model != null)
        ActivityScenario.launch(MainActivity::class.java).use {
            val pcm = readPcm16(wav)
            val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
            val whisper = WhisperContext.createFromFile(model!!.absolutePath)
            val timed = whisper.openWithWordTimings()
            try {
                assertTrue("this model has no word timings", timed != null)
                val decode = TahsinSettings().engineTuning().decode.copy(
                    audioContext = (samples.size / 320 + 32).coerceIn(128, 1500),
                )
                val transcript = runBlocking { timed!!.transcribe(samples, decode) }
                transcript.words.forEach { Log.i(TAG, "  word ${it.startMs}-${it.lastTokenMs}ms ${it.text}") }
                val at = transcript.words.indexOfLast { ArabicNormalizer.normalize(it.text) == "ذكراها" }
                assertTrue("ذكراها not located in \"${transcript.text}\"", at >= 0)
                val word = transcript.words[at]
                val end = minOf(
                    transcript.words.getOrNull(at + 1)?.startMs ?: Long.MAX_VALUE,
                    word.lastTokenMs + 500,
                    samples.size * 1000L / SAMPLE_RATE,
                )
                val from = ((word.startMs - 100).coerceAtLeast(0) * SAMPLE_RATE / 1000).toInt()
                val to = (end * SAMPLE_RATE / 1000).toInt().coerceIn(from, samples.size)
                val m = ImalaCheck.measure(samples.copyOfRange(from, to), SAMPLE_RATE)
                Log.i(TAG, "RESULT plain-ra ذكراها at ${word.startMs}-${end}ms deltaF2=${m.deltasHz} -> ${m.verdict}")
                assertEquals(GharibVerdict.Wrong, m.verdict)
            } finally {
                runBlocking {
                    timed?.release()
                    whisper.release()
                }
            }
        }
    }

    private fun replay(
        fixture: String,
        range: AyahRange,
        minCorrectFraction: Double,
        checkGharib: Boolean = false,
    ) {
        val wav = File(context.filesDir, "test-fixtures/$fixture")
        val model = File(context.filesDir, "models").listFiles { f -> f.extension == "bin" }
            ?.maxByOrNull { it.length() }
        assumeTrue("missing fixture ${wav.path}", wav.exists())
        assumeTrue("no model in files/models", model != null)

        // A resumed activity keeps the process in the foreground. Some vendor
        // power managers (Transsion's included) freeze background processes,
        // which an instrumentation run otherwise looks like.
        ActivityScenario.launch(MainActivity::class.java).use {
            val pcm = readPcm16(wav)
            val clipMs = pcm.size * 1000L / SAMPLE_RATE
            val settings = TahsinSettings()

            val database = QuranDatabase.create(context)
            val expected = runBlocking { QuranRepository(database.quranDao()).buildExpectedWords(range) }
            database.close()

            val tracker = RecitationTracker(expected, settings.trackerConfig())
            val recorder = ReplayRecorder(pcm)
            val whisper = WhisperContext.createFromFile(model!!.absolutePath)
            val advances = mutableListOf<Pair<Long, Int>>()
            var lastCursor = 0
            var timedOpened = false
            val engine = RecitationEngine(
                whisper::transcribe,
                recorder,
                tracker,
                settings.engineTuning(),
                timedTranscribers = if (!checkGharib) null else TimedTranscriberSource {
                    whisper.openWithWordTimings()?.let { timed ->
                        timedOpened = true
                        TimedTranscriber(timed::transcribe, timed::release)
                    }
                },
            )

            Log.i(TAG, "=== $fixture: ${clipMs}ms of audio, ${expected.size} words, model=${model.name}")
            try {
                runBlocking {
                    // Give recognition a fixed tail after the audio ends; on a
                    // working pipeline the last word lands well within it.
                    withTimeoutOrNull(clipMs + TAIL_MS) {
                        engine.run(recordTo = null) { snapshot ->
                                if (snapshot.state.cursor != lastCursor) {
                                    lastCursor = snapshot.state.cursor
                                    advances += snapshot.elapsedMs to lastCursor
                                    val word = expected.getOrNull(lastCursor - 1)
                                    Log.i(
                                        TAG,
                                        "t=${snapshot.elapsedMs}ms cursor=$lastCursor/${expected.size} " +
                                            "last=${word?.ayahNumber}:${word?.wordIndex} ${word?.uthmani} " +
                                            "heard=\"${snapshot.lastTranscript}\"",
                                    )
                                }
                            }
                    }
                }
            } finally {
                runBlocking { whisper.release() }
            }

            // Every verdict the app would show the reciter, end-of-session ones included.
            val mistakes = tracker.standingVerdicts(engine.allMistakes + tracker.finish())
            mistakes.forEach { m ->
                Log.i(TAG, "  MISTAKE ${m.kind} ${m.ayahNumber}:${m.wordIndex} expected=${m.expected} heard=${m.heard}")
            }
            val statuses = tracker.state().statuses
            val correct = statuses.count { it == WordStatus.Correct }
            expected.forEachIndexed { i, w ->
                Log.i(TAG, "  ${w.ayahNumber}:${w.wordIndex} ${statuses[i]}  ${w.uthmani}")
            }
            val lagAfterClip = (advances.lastOrNull()?.first ?: 0L) - clipMs
            Log.i(
                TAG,
                "RESULT $fixture correct=$correct/${expected.size} mistakes=${mistakes.size} " +
                    "cursor=${tracker.state().cursor} " +
                    "advances=${advances.size} lastAdvanceAfterClipEnd=${lagAfterClip}ms",
            )

            assertTrue("cursor never moved", advances.isNotEmpty())
            if (checkGharib) {
                assertTrue("the word-timing model never opened", timedOpened)
                assertTrue(
                    "a master's gharib reading was reported: $mistakes",
                    mistakes.none { it.kind == MistakeKind.Pronunciation },
                )
            }
            assertTrue(
                "only $correct/${expected.size} words followed",
                correct >= expected.size * minCorrectFraction,
            )
        }
    }

    /**
     * Stands in for the microphone: appends the clip to the ring in 100 ms frames
     * paced to the wall clock, then keeps appending silence like an open mic.
     */
    private class ReplayRecorder(private val pcm: ShortArray) : MicrophoneRecorder(sampleRate = SAMPLE_RATE) {
        @Volatile private var running = false
        @Volatile private var lastVoiceAt = 0L
        private var thread: Thread? = null

        override fun start(recordTo: File?) {
            ring.clear()
            running = true
            thread = Thread({
                val frame = SAMPLE_RATE / 10
                val startedAt = System.nanoTime()
                var offset = 0
                while (running) {
                    val chunk = if (offset < pcm.size) {
                        pcm.copyOfRange(offset, minOf(offset + frame, pcm.size))
                    } else {
                        ShortArray(frame)
                    }
                    ring.append(chunk, chunk.size)
                    if (rms(chunk) >= VOICE_RMS_THRESHOLD) lastVoiceAt = ring.totalSamples
                    offset += chunk.size
                    val due = startedAt + offset * 1_000_000_000L / SAMPLE_RATE
                    val waitMs = (due - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) Thread.sleep(waitMs)
                }
            }, "replay-mic").apply { start() }
        }

        override fun stop() {
            running = false
            thread?.join(1_000)
        }

        override fun silentForMs(ms: Long): Boolean =
            (ring.totalSamples - lastVoiceAt) * 1000 / SAMPLE_RATE >= ms

        private fun rms(samples: ShortArray): Float {
            var sum = 0.0
            for (s in samples) sum += (s / 32768.0) * (s / 32768.0)
            return sqrt(sum / samples.size.coerceAtLeast(1)).toFloat()
        }
    }

    private companion object {
        const val TAG = "TahsinFollow"
        const val SAMPLE_RATE = 16_000
        const val TAIL_MS = 20_000L

        /** 16-bit PCM from a WAV, walking chunks rather than assuming a 44-byte header. */
        fun readPcm16(file: File): ShortArray {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buffer.getInt(pos + 4)
                if (id == "data") {
                    val end = minOf(bytes.size, pos + 8 + size)
                    val count = (end - pos - 8) / 2
                    return ShortArray(count) { buffer.getShort(pos + 8 + it * 2) }
                }
                pos += 8 + size + (size and 1)
            }
            error("no data chunk in ${file.name}")
        }
    }
}
