package com.tahsin.app.domain.recitation

import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.model.MistakeKind
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.domain.text.ArabicNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecitationTrackerTest {

    private val fatihah2 = "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ"
    private val ikhlas = "قُلْ هُوَ ٱللَّهُ أَحَدٌ"

    private fun expectedWords(vararg ayahs: Pair<Int, String>): List<ExpectedWord> {
        val words = mutableListOf<ExpectedWord>()
        for ((ayahNumber, text) in ayahs) {
            ArabicNormalizer.displayWords(text).forEach { uthmani ->
                val variants = ArabicNormalizer.normalizeVariants(uthmani)
                if (variants.first().isEmpty()) return@forEach
                words += ExpectedWord(
                    position = words.size,
                    surahId = 1,
                    ayahNumber = ayahNumber,
                    wordIndex = words.count { it.ayahNumber == ayahNumber },
                    uthmani = uthmani,
                    normalized = variants.first(),
                    variants = variants,
                )
            }
        }
        return words
    }

    private fun tracker(
        words: List<ExpectedWord>,
        config: TrackerConfig = TrackerConfig(),
    ) = RecitationTracker(words, config)

    // Al-Fatihah 1-3: ayah 3 repeats the last two words of ayah 1 verbatim.
    private val fatihah1 = "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ"
    private val fatihah3 = "ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ"

    @Test
    fun `a phrase repeated later in the passage is matched to the copy at the cursor`() {
        // On a real phone this skipped ayah 3 entirely: its words matched their
        // earlier copy in ayah 1 instead, so the cursor stalled and ayah 3 was
        // later reported as missed.
        val words = expectedWords(1 to fatihah1, 2 to fatihah2, 3 to fatihah3)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("بسم الله الرحمن الرحيم", confidence = 0.9f, windowStartMs = 0)
        subject.onTranscript("الحمد لله رب العالمين", confidence = 0.9f, windowStartMs = 5_000)
        val update = subject.onTranscript("الرحمن الرحيم", confidence = 0.9f, windowStartMs = 11_000)

        assertEquals(words.size, update.state.cursor)
        assertTrue(update.state.statuses.all { it == WordStatus.Correct })
        assertTrue(update.newMistakes.isEmpty())
    }

    @Test
    fun `a second listen confirms the unconfirmed copy of a repeated word`() {
        // From a live session on a Redmi: one window heard "عالمين الرحيم" and
        // jumped over 1:3's الرحمن; the second listen then heard "الرحمن" — but
        // matched it to 1:1's copy, already confirmed, instead of the one still
        // waiting. 1:3's الرحمن was then reported wrong although it was recited.
        val words = expectedWords(1 to fatihah1, 2 to fatihah2, 3 to fatihah3)
        val subject = tracker(words)

        subject.onTranscript("بسم الله الرحمن الرحيم", confidence = 0.95f, windowStartMs = 0)
        subject.onTranscript("الحمد لله رب العالمين", confidence = 0.95f, windowStartMs = 4_000)
        val jump = subject.onTranscript("عالمين الرحيم", confidence = 0.93f, windowStartMs = 9_000, deferVerdicts = true)
        assertEquals("the window should jump over 1:3's الرحمن", 10, jump.state.cursor)
        subject.onTranscript("الرحمن", confidence = 0.93f, windowStartMs = 8_500, deferVerdicts = true)

        assertEquals(WordStatus.Correct, subject.state().statuses[8])
        val verdicts = subject.standingVerdicts(subject.takeVerdicts(0.93f) + subject.finish())
        assertTrue("reported $verdicts", verdicts.none { it.ayahNumber == 3 })
    }

    @Test
    fun `a phrase that also appears further ahead does not make the cursor jump`() {
        // The mirror case: reciting ayah 1's own words must not leap to ayah 3
        // and write off everything in between as missed.
        val words = expectedWords(1 to fatihah1, 2 to fatihah2, 3 to fatihah3)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("بسم الله", confidence = 0.9f, windowStartMs = 0)
        val update = subject.onTranscript("الرحمن الرحيم", confidence = 0.9f, windowStartMs = 1_500)

        assertEquals(4, update.state.cursor)
        assertTrue(update.newMistakes.isEmpty())
        assertEquals(WordStatus.Pending, update.state.statuses[8])
    }

    @Test
    fun `a perfect recitation marks every word correct and reports nothing`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words)

        val update = subject.onTranscript("الحمد لله رب العالمين", confidence = 0.9f, windowStartMs = 0)

        assertTrue(update.newMistakes.isEmpty())
        assertEquals(words.size, subject.correctCount())
        assertTrue(subject.state().isComplete)
        assertTrue(update.state.statuses.all { it == WordStatus.Correct })
    }

    @Test
    fun `the cursor sits on the next expected word mid-passage`() {
        val subject = tracker(expectedWords(2 to fatihah2))

        val update = subject.onTranscript("الحمد لله", confidence = 0.9f, windowStartMs = 0)

        assertEquals(2, update.state.cursor)
        assertEquals(WordStatus.Correct, update.state.statuses[0])
        assertEquals(WordStatus.Current, update.state.statuses[2])
        assertEquals(WordStatus.Pending, update.state.statuses[3])
    }

    @Test
    fun `a wrong word is reported once the cursor settles past it`() {
        val words = expectedWords(2 to fatihah2)
        // settleWords = 0 makes the verdict immediate, which is what the test asserts.
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        val update = subject.onTranscript(
            "الحمد لله ملك العالمين",
            confidence = 0.9f,
            windowStartMs = 0,
        )

        val wrong = update.newMistakes.filter { it.kind == MistakeKind.WrongWord }
        assertEquals(1, wrong.size)
        assertEquals(2, wrong.first().wordIndex)
        assertEquals("ملك", wrong.first().heard)
    }

    @Test
    fun `a skipped word is reported as missed rather than wrong`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        val update = subject.onTranscript("الحمد لله العالمين", confidence = 0.9f, windowStartMs = 0)

        val missed = update.newMistakes.filter { it.kind == MistakeKind.MissedWord }
        assertEquals(1, missed.size)
        assertEquals(2, missed.first().wordIndex)
        assertEquals(null, missed.first().heard)
    }

    @Test
    fun `an interpolated word is reported as extra`() {
        val subject = tracker(expectedWords(112 to ikhlas), TrackerConfig(settleWords = 0))

        // Two overlapping windows both hear the added word.
        val first = subject.onTranscript("قل هو الله رب احد", confidence = 0.95f, windowStartMs = 0)
        val second = subject.onTranscript("هو الله رب احد", confidence = 0.95f, windowStartMs = 900)

        assertTrue(first.newMistakes.none { it.kind == MistakeKind.ExtraWord })
        assertEquals("رب", second.newMistakes.single { it.kind == MistakeKind.ExtraWord }.heard)
    }

    @Test
    fun `a mishearing seen in only one window is not reported as an extra word`() {
        // A phone run split 1:5's وإياك into "ويا كما" in a single window; the
        // windows either side heard it correctly. One sighting is not evidence.
        val subject = tracker(
            expectedWords(5 to "إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ"),
            TrackerConfig(settleWords = 0),
        )

        subject.onTranscript("اياك نعبد", confidence = 0.95f, windowStartMs = 0)
        val misheard = subject.onTranscript("ويا كما استعين", confidence = 0.9f, windowStartMs = 1_500)
        val clean = subject.onTranscript("نعبد واياك نستعين", confidence = 0.95f, windowStartMs = 2_500)

        assertTrue((misheard.newMistakes + clean.newMistakes + subject.finish()).none { it.kind == MistakeKind.ExtraWord })
    }

    @Test
    fun `a repetition loop is never evidence of a mistake`() {
        // Whisper looped on a real phone: "مالك يومهم مالك يومهم مالك يومهم…".
        // That text is not in the audio, so it must not produce extra-word or
        // wrong-word verdicts — only the genuine first words count.
        val words = expectedWords(4 to "مَـٰلِكِ يَوْمِ ٱلدِّينِ")
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        val update = subject.onTranscript(
            "مالك يومهم مالك يومهم مالك يومهم مالك يومهم",
            confidence = 0.98f,
            windowStartMs = 0,
        )

        assertTrue(update.loopTrimmed)
        assertTrue(update.newMistakes.none { it.kind == MistakeKind.ExtraWord })
        assertEquals(WordStatus.Correct, update.state.statuses[0])
    }

    @Test
    fun `a looping phrase cannot drag the cursor to a later copy of itself`() {
        // On a phone, "الرحمن الرجع الرجع الرجع الرحمن…" let the looped الرحمن
        // match ayah 3's copy, and the cursor jumped over all of ayah 2.
        val words = expectedWords(1 to fatihah1, 2 to fatihah2, 3 to fatihah3)
        val subject = tracker(words)

        subject.onTranscript("بسم الله", confidence = 0.9f, windowStartMs = 0)
        val update = subject.onTranscript(
            "الرحمن الرجع الرجع الرجع الرحمن الرجع الرحمن الرجع",
            confidence = 0.95f,
            windowStartMs = 1_000,
        )

        assertEquals(3, update.state.cursor)
    }

    @Test
    fun `a loop can only advance the cursor through words heard in order from it`() {
        // Live on a Redmi: with the cursor on 1:1's الرحيم, whisper looped
        // "الحمد لله الرحمن الرحيم" — its first copy jumbles ayah 2's opening with
        // ayah 3's words, and that pushed the cursor to the end of ayah 3 while
        // the reciter was still on ayah 2.
        val words = expectedWords(1 to fatihah1, 2 to fatihah2, 3 to fatihah3, 4 to "مَـٰلِكِ يَوْمِ ٱلدِّينِ")
        val subject = tracker(words)

        subject.onTranscript("بسم الله الرحمن", confidence = 0.98f, windowStartMs = 0)
        val looped = subject.onTranscript(
            "الحمد لله الرحمن الرحيم الحمد لله الرحمن الرحيم الحمد لله الرحمن الرحيم",
            confidence = 0.98f,
            windowStartMs = 2_000,
        )

        assertTrue(looped.loopTrimmed)
        assertEquals("a loop must not jump the cursor", 3, looped.state.cursor)
        assertEquals(WordStatus.Pending, looped.state.statuses[8])
        assertEquals(WordStatus.Pending, looped.state.statuses[9])
    }

    @Test
    fun `a phrase the passage itself repeats is not mistaken for a loop`() {
        // 97:2-3 genuinely says ليلة القدر twice in a row across the ayah break.
        val words = expectedWords(
            2 to "وَمَآ أَدْرَىٰكَ مَا لَيْلَةُ ٱلْقَدْرِ",
            3 to "لَيْلَةُ ٱلْقَدْرِ خَيْرٌ مِّنْ أَلْفِ شَهْرٍ",
        )
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("وما ادراك", confidence = 0.95f, windowStartMs = 0)
        val update = subject.onTranscript(
            "ما ليلة القدر ليلة القدر خير من الف شهر",
            confidence = 0.95f,
            windowStartMs = 1_500,
        )

        assertFalse(update.loopTrimmed)
        assertEquals(words.size, update.state.cursor)
        assertEquals(words.size, subject.correctCount())
    }

    @Test
    fun `a word heard too faintly to judge is unclear rather than wrong`() {
        // From a phone recording: the windows holding the reciter's ٱلرَّحِيمِ
        // heard only "الم" at 0.34 confidence. That is too weak to call a
        // mistake — and so the word must not be painted as one either.
        val words = expectedWords(1 to fatihah1)
        val subject = tracker(words, TrackerConfig(settleWords = 0, minMistakeConfidence = 0.45f))

        subject.onTranscript("بسم الله الرحمن", confidence = 0.98f, windowStartMs = 0)
        subject.onTranscript("الرحمن الم", confidence = 0.34f, windowStartMs = 2_000)
        val verdicts = subject.finish()

        assertTrue(verdicts.isEmpty())
        assertEquals(WordStatus.Unclear, subject.state().statuses[3])
    }

    @Test
    fun `a confidently heard different word is still wrong`() {
        val words = expectedWords(1 to fatihah1)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("بسم الله الرحمن", confidence = 0.98f, windowStartMs = 0)
        subject.onTranscript("الرحمن الكريم", confidence = 0.95f, windowStartMs = 2_000)
        val verdicts = subject.finish()

        assertEquals(WordStatus.Wrong, subject.state().statuses[3])
        assertEquals("الكريم", verdicts.single { it.kind == MistakeKind.WrongWord }.heard)
    }

    @Test
    fun `a verdict that later evidence overturns does not stand`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("الحمد لله", confidence = 0.9f, windowStartMs = 0)
        val early = subject.onTranscript("ملك العالمين", confidence = 0.9f, windowStartMs = 1_500)
        assertTrue(early.newMistakes.any { it.kind == MistakeKind.WrongWord })
        // A longer window hears رب after all.
        subject.onTranscript("لله رب العالمين", confidence = 0.9f, windowStartMs = 500)

        assertTrue(subject.standingVerdicts(early.newMistakes + subject.finish()).isEmpty())
    }

    @Test
    fun `fragments at the edges of a window are not reported as extra words`() {
        // From a live session on a phone: windows that cut "لله رب" mid-word
        // produced "مدل لاهره" and "ولاهره بالعاد", which used to be reported as
        // extra words although the reciter added nothing.
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("الحمد لله", confidence = 0.95f, windowStartMs = 0)
        val first = subject.onTranscript("مدل لاهره", confidence = 0.95f, windowStartMs = 1_500)
        val second = subject.onTranscript("ولاهره بالعاد", confidence = 0.95f, windowStartMs = 3_000)

        assertFalse((first.newMistakes + second.newMistakes).any { it.kind == MistakeKind.ExtraWord })
    }

    @Test
    fun `verdicts can be held back until the caller has a second listen`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("الحمد لله", confidence = 0.9f, windowStartMs = 0)
        // A window that missed رب jumps the cursor over it...
        val jump = subject.onTranscript("العالمين", confidence = 0.9f, windowStartMs = 2_000, deferVerdicts = true)
        assertTrue("no verdict may be issued yet", jump.newMistakes.isEmpty())
        // ...then a longer window hears it after all.
        subject.onTranscript("لله رب العالمين", confidence = 0.9f, windowStartMs = 500, deferVerdicts = true)

        assertTrue(subject.takeVerdicts(confidence = 0.9f).isEmpty())
        assertTrue(subject.finish().isEmpty())
        assertEquals(words.size, subject.correctCount())
    }

    @Test
    fun `verdicts are deferred while words are still provisional`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words, TrackerConfig(settleWords = 3))

        // A window clipped mid-word must not immediately be called a mistake.
        val first = subject.onTranscript("الحمد لله رب", confidence = 0.9f, windowStartMs = 0)
        assertTrue(first.newMistakes.isEmpty())
    }

    @Test
    fun `an overlapping window can correct a word a clipped window got wrong`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words, TrackerConfig(settleWords = 2))

        // First window clips the last word to a fragment.
        subject.onTranscript("الحمد لله رب العال", confidence = 0.8f, windowStartMs = 0)
        // The next, overlapping window hears it in full.
        val second = subject.onTranscript(
            "رب العالمين",
            confidence = 0.9f,
            windowStartMs = 1_200,
        )

        assertEquals(WordStatus.Correct, second.state.statuses.last())
        assertTrue(
            "no mistake should survive a corrected word",
            subject.finish().none { it.wordIndex == words.lastIndex },
        )
    }

    @Test
    fun `repeating a phrase does not move the cursor backwards or re-flag words`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words, TrackerConfig(settleWords = 0))

        subject.onTranscript("الحمد لله رب العالمين", confidence = 0.9f, windowStartMs = 0)
        val cursorAfterFirst = subject.state().cursor

        val repeat = subject.onTranscript(
            "الحمد لله رب العالمين",
            confidence = 0.9f,
            windowStartMs = 3_000,
        )

        assertEquals(cursorAfterFirst, repeat.state.cursor)
        assertTrue(repeat.newMistakes.isEmpty())
    }

    @Test
    fun `progress carries across an ayah boundary in one session`() {
        val words = expectedWords(2 to fatihah2, 3 to "ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ")
        val subject = tracker(words)

        subject.onTranscript(
            "الحمد لله رب العالمين الرحمن الرحيم",
            confidence = 0.9f,
            windowStartMs = 0,
        )

        assertTrue(subject.state().isComplete)
        assertEquals(words.size, subject.correctCount())
    }

    @Test
    fun `low confidence disagreements are not reported`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(
            words,
            TrackerConfig(settleWords = 0, minMistakeConfidence = 0.8f),
        )

        val update = subject.onTranscript(
            "الحمد لله ملك العالمين",
            confidence = 0.2f,
            windowStartMs = 0,
        )

        assertTrue(update.newMistakes.isEmpty())
    }

    @Test
    fun `extra words need more confidence than other mistakes`() {
        val subject = tracker(
            expectedWords(112 to ikhlas),
            TrackerConfig(settleWords = 0, minExtraWordConfidence = 0.9f),
        )

        val first = subject.onTranscript("قل هو الله رب احد", confidence = 0.75f, windowStartMs = 0)
        val second = subject.onTranscript("هو الله رب احد", confidence = 0.75f, windowStartMs = 900)

        assertFalse((first.newMistakes + second.newMistakes).any { it.kind == MistakeKind.ExtraWord })
    }

    @Test
    fun `the same extra word is only reported once across overlapping windows`() {
        val subject = tracker(expectedWords(112 to ikhlas), TrackerConfig(settleWords = 0))

        // رب is mid-phrase, so it is a genuine extra word rather than an edge fragment.
        val first = subject.onTranscript("قل هو رب الله", confidence = 0.95f, windowStartMs = 0)
        val second = subject.onTranscript("قل هو رب الله", confidence = 0.95f, windowStartMs = 900)
        val third = subject.onTranscript("هو رب الله", confidence = 0.95f, windowStartMs = 1_800)

        // Reported once, on the second sighting, and never again.
        assertEquals(0, first.newMistakes.count { it.kind == MistakeKind.ExtraWord })
        assertEquals(1, second.newMistakes.count { it.kind == MistakeKind.ExtraWord })
        assertEquals(0, third.newMistakes.count { it.kind == MistakeKind.ExtraWord })
    }

    @Test
    fun `finish reports every word never reached`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words)

        subject.onTranscript("الحمد لله", confidence = 0.9f, windowStartMs = 0)
        val remaining = subject.finish()

        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.kind == MistakeKind.MissedWord })
    }

    @Test
    fun `skipping the current word advances and records a miss`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words)

        val mistake = subject.skipCurrent(atMs = 1_500)

        assertEquals(MistakeKind.MissedWord, mistake?.kind)
        assertEquals(0, mistake?.wordIndex)
        assertEquals(1, subject.state().cursor)
        assertEquals(WordStatus.Current, subject.state().statuses[1])
    }

    @Test
    fun `silence and noise leave the session untouched`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words)

        val update = subject.onTranscript("   ...  ", confidence = 0.1f, windowStartMs = 0)

        assertEquals(0, update.state.cursor)
        assertTrue(update.newMistakes.isEmpty())
        assertEquals(0, update.advancedBy)
    }

    @Test
    fun `an empty passage completes immediately without crashing`() {
        val subject = tracker(emptyList())

        val update = subject.onTranscript("الحمد", confidence = 0.9f, windowStartMs = 0)

        assertTrue(update.state.isComplete)
        assertTrue(update.newMistakes.isEmpty())
    }

    @Test
    fun `peek reveals the word at the cursor without judging it`() {
        val words = expectedWords(2 to fatihah2)
        val subject = tracker(words)

        subject.onTranscript("الحمد", confidence = 0.9f, windowStartMs = 0)

        assertEquals(words[1].uthmani, subject.peek()?.uthmani)
        assertEquals(1, subject.correctCount())
    }
}
