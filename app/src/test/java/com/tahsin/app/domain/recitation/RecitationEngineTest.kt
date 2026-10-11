package com.tahsin.app.domain.recitation

import com.tahsin.app.audio.MicrophoneRecorder
import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.gharib.SyntheticSpeech
import com.tahsin.app.domain.model.MistakeKind
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.domain.text.ArabicNormalizer
import com.tahsin.whisper.WhisperSegment
import com.tahsin.whisper.WhisperTranscript
import com.tahsin.whisper.WhisperWord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Engine lifecycle tests against a fake microphone and a fake transcriber.
 *
 * The microphone-leak regression lives here: a session on a real device kept
 * the recorder running after the session ended, which silently starved every
 * later session of audio. These tests pin the engine half of that contract —
 * run() must stop the recorder on every exit path, cancellation included.
 */
@RunWith(RobolectricTestRunner::class)
class RecitationEngineTest {

    /** Feeds the ring from the test instead of AudioRecord, and records calls. */
    private class FakeRecorder : MicrophoneRecorder(sampleRate = 16_000) {
        var started = 0
        var stopped = 0
        var voiced = true

        override fun start(recordTo: File?) {
            started++
        }

        override fun stop() {
            stopped++
        }

        override fun silentForMs(ms: Long): Boolean = !voiced

        fun feedSeconds(seconds: Float) {
            val samples = ShortArray((16_000 * seconds).toInt()) { 1000 }
            ring.append(samples, samples.size)
        }

        fun feed(audio: FloatArray) {
            val samples = ShortArray(audio.size) { (audio[it] * 32767).toInt().toShort() }
            ring.append(samples, samples.size)
        }
    }

    /** 11:41's opening with مَجْر۪ىٰهَا as the mushaf marks it (U+06EA, imala). */
    private fun hud41(): List<ExpectedWord> = listOf(
        "بسم" to "بِسْمِ", "الله" to "ٱللَّهِ", "مجراها" to "مَجْر۪ىٰهَا", "ومرساها" to "وَمُرْسَىٰهَآ",
    ).mapIndexed { index, (plain, uthmani) ->
        ExpectedWord(
            position = index, surahId = 11, ayahNumber = 41, wordIndex = index + 3,
            uthmani = uthmani, normalized = plain, variants = listOf(plain),
        )
    }

    /**
     * Runs a whole 11:41 opening where مجراها is voiced with [middleVowel], and
     * returns what the engine concluded plus how the timing model was used.
     */
    private suspend fun reciteHud41(middleVowel: DoubleArray): Triple<RecitationEngine, RecitationTracker, IntArray> {
        val recorder = FakeRecorder()
        val tracker = RecitationTracker(hud41())
        val lead = SyntheticSpeech.concat(SyntheticSpeech.vowel(500, SyntheticSpeech.AA), SyntheticSpeech.gap(500))
        val word = SyntheticSpeech.majra(middleVowel)
        recorder.feed(lead)
        recorder.feed(word)
        recorder.feed(SyntheticSpeech.vowel(800, SyntheticSpeech.AA))
        val wordStartMs = lead.size / 16L
        val nextStartMs = (lead.size + word.size) / 16L
        val opens = IntArray(2) // [opened, closed]
        val engine = RecitationEngine(
            whisper = { _, _ -> transcript("بسم الله مجراها ومرساها") },
            recorder = recorder,
            tracker = tracker,
            tuning = EngineTuning.Balanced,
            timedTranscribers = {
                opens[0]++
                TimedTranscriber(
                    transcriber = { samples, _ ->
                        // Word times are relative to the clip, as whisper reports them.
                        val clipStartMs = (recorder.ring.totalSamples - samples.size) / 16L
                        WhisperTranscript(
                            text = "مَجْرَاهَا وَمُرْسَاهَا",
                            segments = emptyList(),
                            words = listOf(
                                WhisperWord("مَجْرَاهَا", wordStartMs - clipStartMs, nextStartMs - 300 - clipStartMs),
                                WhisperWord("وَمُرْسَاهَا", nextStartMs - clipStartMs, nextStartMs + 500 - clipStartMs),
                            ),
                        )
                    },
                    close = { opens[1]++ },
                )
            },
        )
        // Real time: the timing model opens on Dispatchers.IO, which virtual time
        // would simply skip past into the timeout.
        withContext(Dispatchers.Default) { withTimeout(10_000) { engine.run(recordTo = null) { } } }
        return Triple(engine, tracker, opens)
    }

    @Test
    fun `majroha instead of imala is reported though the words all matched`() = runTest {
        val (engine, tracker, opens) = reciteHud41(SyntheticSpeech.OO)

        val pronunciation = engine.allMistakes.filter { it.kind == MistakeKind.Pronunciation }
        assertEquals(engine.allMistakes.toString(), 1, pronunciation.size)
        assertEquals(5, pronunciation.single().wordIndex)
        assertEquals(WordStatus.Wrong, tracker.state().statuses[2])
        assertEquals(3, tracker.correctCount())
        // The verdict survives later re-filtering against transcript evidence.
        assertEquals(pronunciation, tracker.standingVerdicts(pronunciation))
        assertEquals("the timing model must be opened once and closed", listOf(1, 1), opens.toList())
    }

    @Test
    fun `imala read correctly is not reported`() = runTest {
        val (engine, tracker, opens) = reciteHud41(SyntheticSpeech.EH)

        assertTrue(engine.allMistakes.toString(), engine.allMistakes.isEmpty())
        assertEquals(4, tracker.correctCount())
        assertEquals(listOf(1, 1), opens.toList())
    }

    @Test
    fun `a passage without gharib never opens the timing model`() = runTest {
        val recorder = FakeRecorder()
        var opened = 0
        val engine = RecitationEngine(
            whisper = { _, _ -> transcript("بسم الله") },
            recorder = recorder,
            tracker = RecitationTracker(expected("بسم الله")),
            tuning = EngineTuning.Balanced,
            timedTranscribers = { opened++; null },
        )
        recorder.feedSeconds(3f)

        withTimeout(5_000) { engine.run(recordTo = null) { } }

        assertEquals(0, opened)
    }

    private fun expected(text: String): List<ExpectedWord> =
        ArabicNormalizer.tokenize(text).mapIndexed { index, word ->
            ExpectedWord(
                position = index,
                surahId = 1,
                ayahNumber = 1,
                wordIndex = index,
                uthmani = word,
                normalized = word,
                variants = listOf(word),
            )
        }

    private fun transcript(text: String) = WhisperTranscript(
        text = text,
        segments = listOf(WhisperSegment(text, 0, 1_000, avgTokenProb = 0.9f, noSpeechProb = 0.05f)),
    )

    @Test
    fun `a transcript advances the tracker and completes the session`() = runTest {
        val recorder = FakeRecorder()
        val tracker = RecitationTracker(expected("بسم الله"))
        val engine = RecitationEngine(
            whisper = { _, _ -> transcript("بسم الله") },
            recorder = recorder,
            tracker = tracker,
            tuning = EngineTuning.Balanced,
        )
        recorder.feedSeconds(3f)

        withTimeout(5_000) { engine.run(recordTo = null) { } }

        assertTrue(tracker.state().isComplete)
        assertEquals(1, recorder.started)
        assertEquals(1, recorder.stopped)
    }

    @Test
    fun `cancellation stops the recorder even while inference is in flight`() = runTest {
        val recorder = FakeRecorder()
        val inferenceEntered = CompletableDeferred<Unit>()
        val engine = RecitationEngine(
            whisper = { _, _ ->
                inferenceEntered.complete(Unit)
                // A decode that outlives the session, as on a slow phone.
                delay(60_000)
                transcript("بسم")
            },
            recorder = recorder,
            tracker = RecitationTracker(expected("بسم الله")),
            tuning = EngineTuning.Balanced,
        )
        recorder.feedSeconds(3f)

        val job = launch { engine.run(recordTo = null) { } }
        withTimeout(5_000) { inferenceEntered.await() }
        job.cancelAndJoin()

        // This is the microphone-leak regression: the recorder must be stopped
        // by run()'s finally, not by whenever the decode eventually returns.
        assertEquals(1, recorder.stopped)
    }

    @Test
    fun `a failing transcriber still releases the recorder`() = runTest {
        val recorder = FakeRecorder()
        val engine = RecitationEngine(
            whisper = { _, _ -> error("native decode blew up") },
            recorder = recorder,
            tracker = RecitationTracker(expected("بسم الله")),
            tuning = EngineTuning.Balanced,
        )
        recorder.feedSeconds(3f)

        val result = runCatching { withTimeout(5_000) { engine.run(recordTo = null) { } } }

        assertTrue(result.isFailure)
        assertEquals(1, recorder.stopped)
    }

    @Test
    fun `a word longer than one window is still heard whole`() = runTest {
        // A madd held for six counts can outlast a single step-plus-context
        // window. The engine must keep the window anchored at the last
        // recognised word, so the unfinished word stays in view until it ends.
        // On a phone, the last word of Al-Fatihah was never matched because the
        // window slid past its start on every pass that heard only a fragment.
        val recorder = FakeRecorder()
        val wordNeedsSeconds = 3.5f
        val windowsSeen = mutableListOf<Float>()
        // 1:7's last word as the mushaf writes it, maddah (U+0653) included —
        // the mark that tells the engine this word can be held for six counts.
        val longWord = ExpectedWord(
            position = 0, surahId = 1, ayahNumber = 7, wordIndex = 8,
            uthmani = "ٱلضَّا\u0653لِّينَ", normalized = "الضالين", variants = listOf("الضالين"),
        )
        val next = ExpectedWord(
            position = 1, surahId = 2, ayahNumber = 1, wordIndex = 0,
            uthmani = "الم", normalized = "الم", variants = listOf("الم"),
        )
        val tracker = RecitationTracker(listOf(longWord, next))
        val engine = RecitationEngine(
            whisper = { samples, _ ->
                val seconds = samples.size / 16_000f
                windowsSeen += seconds
                // Only a window holding the whole word hears it; anything shorter
                // hears a fragment that does not match.
                transcript(if (seconds >= wordNeedsSeconds) "الضالين" else "الضا")
            },
            recorder = recorder,
            tracker = tracker,
            tuning = EngineTuning.Balanced,
        )

        val job = launch { engine.run(recordTo = null) { } }
        // Audio arrives as it would from a microphone: 100 ms at a time.
        repeat(60) {
            recorder.feedSeconds(0.1f)
            delay(100)
        }
        job.cancelAndJoin()

        assertTrue("windows never grew past ${windowsSeen.maxOrNull()}s", tracker.state().cursor >= 1)
    }

    @Test
    fun `after a word is recognised the next window is short again`() = runTest {
        // The other half of the trade-off: a long window that keeps the previous
        // word's held ending in view makes whisper garble the next words. Once a
        // word is recognised, windows must shrink back to ordinary size.
        val recorder = FakeRecorder()
        val windowsAfterMatch = mutableListOf<Float>()
        var matched = false
        val tracker = RecitationTracker(expected("بسم الله الرحمن الرحيم"))
        val engine = RecitationEngine(
            whisper = { samples, _ ->
                if (matched) windowsAfterMatch += samples.size / 16_000f
                matched = true
                transcript("بسم")
            },
            recorder = recorder,
            tracker = tracker,
            tuning = EngineTuning.Balanced,
        )

        val job = launch { engine.run(recordTo = null) { } }
        repeat(80) {
            recorder.feedSeconds(0.1f)
            delay(100)
        }
        job.cancelAndJoin()

        val longest = windowsAfterMatch.maxOrNull() ?: 0f
        // الله is an ordinary word: step + base context, nowhere near the 8 s cap.
        assertTrue("window grew to ${longest}s after a match", longest <= 3.2f)
    }

    @Test
    fun `a word the cursor jumps over gets a second listen before it is judged`() = runTest {
        // From a live session: no short window heard رب cleanly, so the cursor
        // jumped from لله to العالمين and رب was reported missed — although the
        // reciter said it, and a single longer listen hears all four words.
        val recorder = FakeRecorder()
        val tracker = RecitationTracker(expected("الحمد لله رب العالمين"))
        val engine = RecitationEngine(
            whisper = { samples, _ ->
                val now = recorder.ring.totalSamples / 16_000f
                val windowStart = now - samples.size / 16_000f
                transcript(
                    when {
                        now < 2.5f -> "الحمد"
                        now < 4.0f -> "الحمد لله"
                        now < 6.0f -> ""
                        // Short windows only catch the last word...
                        windowStart > 3.0f -> "العالمين"
                        // ...a window reaching back to لله hears everything.
                        else -> "الحمد لله رب العالمين"
                    },
                )
            },
            recorder = recorder,
            tracker = tracker,
            tuning = EngineTuning.Balanced,
        )

        val job = launch { engine.run(recordTo = null) { } }
        repeat(90) {
            recorder.feedSeconds(0.1f)
            delay(100)
        }
        job.cancelAndJoin()

        val verdicts = engine.allMistakes + tracker.finish()
        assertTrue("reported $verdicts", verdicts.none { it.kind == MistakeKind.MissedWord })
        assertEquals(4, tracker.correctCount())
    }

    @Test
    fun `silence skips inference but keeps the session alive`() = runTest {
        val recorder = FakeRecorder()
        recorder.voiced = false
        var decodes = 0
        val engine = RecitationEngine(
            whisper = { _, _ -> decodes++; transcript("") },
            recorder = recorder,
            tracker = RecitationTracker(expected("بسم الله")),
            tuning = EngineTuning.Balanced,
        )
        recorder.feedSeconds(6f)

        val job = launch { engine.run(recordTo = null) { } }
        // Give the loop time to take several idle passes, then end the session.
        delay(2_000)
        job.cancelAndJoin()

        assertEquals(0, decodes)
        assertEquals(1, recorder.stopped)
        assertFalse(engine.allMistakes.isNotEmpty())
    }
}
