package com.tahsin.app.domain.recitation

import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.model.Mistake
import com.tahsin.app.domain.model.MistakeKind
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.domain.text.ArabicNormalizer
import com.tahsin.app.domain.text.WordSimilarity
import kotlin.math.max
import kotlin.math.min

data class TrackerConfig(
    /** Similarity at or above which a heard word counts as the expected word. */
    val matchThreshold: Double = 0.72,
    /** How far behind the cursor a new window is allowed to re-align. */
    val lookBack: Int = 8,
    /** How far ahead of the cursor a new window is allowed to reach. */
    val lookAhead: Int = 16,
    /**
     * Words the cursor must pass before a verdict is final. Keeping a short
     * provisional tail lets the next (overlapping) window correct a word that a
     * partial window clipped, which is where most phantom mistakes come from.
     */
    val settleWords: Int = 3,
    /** Minimum acoustic confidence for a disagreement to be reported at all. */
    val minMistakeConfidence: Float = 0.45f,
    /** Extra words are noisier than the other kinds, so they need more certainty. */
    val minExtraWordConfidence: Float = 0.72f,
)

data class TrackerState(
    val statuses: List<WordStatus>,
    val cursor: Int,
    val settledUpTo: Int,
    val isComplete: Boolean,
)

data class TrackerUpdate(
    val state: TrackerState,
    /** Mistakes finalised by this update; never repeated across updates. */
    val newMistakes: List<Mistake>,
    val advancedBy: Int,
    /** The transcript contained a repetition loop and was cut back to its start. */
    val loopTrimmed: Boolean = false,
)

/**
 * Tracks a recitation against an expected word sequence, fed one overlapping
 * transcript window at a time.
 *
 * Three properties matter for this to behave on real audio:
 *  - **Monotonic progress.** The cursor never moves backwards, so a reciter who
 *    repeats a phrase does not get re-marked for words already passed.
 *  - **Best-score memory.** Each expected word keeps the best similarity ever
 *    seen for it, so overlapping windows reinforce rather than fight each other.
 *  - **Deferred verdicts.** A word is only judged once the cursor is
 *    [TrackerConfig.settleWords] past it, which is what keeps clipped window
 *    edges from being reported as mistakes.
 */
class RecitationTracker(
    val expected: List<ExpectedWord>,
    private val config: TrackerConfig = TrackerConfig(),
) {
    private val bestScore = DoubleArray(expected.size) { -1.0 }
    private val heard = arrayOfNulls<String>(expected.size)
    private val heardConfidence = FloatArray(expected.size)
    private val heardAtMs = LongArray(expected.size) { -1L }

    /** Words recited but voiced wrongly, found by an acoustic gharib check. */
    private val pronunciationFaults = BooleanArray(expected.size)

    private var cursor = 0
    private var settledUpTo = 0
    /** How many windows have heard each candidate extra word, keyed by place and word. */
    private val extraSightings = HashMap<String, Int>()

    /**
     * Phrases of two to four words that this passage itself says twice in a row
     * — 97:2-3's ليلة القدر, 24:40's موج من فوقه. A transcript repeating one of
     * these is reciting, not looping.
     */
    private val passageRepeats: List<List<ExpectedWord>> = buildList {
        for (unit in 2..4) {
            for (i in 0..expected.size - 2 * unit) {
                val first = expected.subList(i, i + unit).map { it.normalized }
                val second = expected.subList(i + unit, i + 2 * unit).map { it.normalized }
                if (first == second) add(expected.subList(i, i + unit))
            }
        }
    }

    val size: Int get() = expected.size

    fun state(): TrackerState = TrackerState(
        statuses = statuses(),
        cursor = cursor,
        settledUpTo = settledUpTo,
        isComplete = cursor >= expected.size,
    )

    /** Number of expected words judged correct so far. */
    fun correctCount(): Int = expected.indices.count { isCorrect(it) }

    private fun isCorrect(index: Int) = bestScore[index] >= config.matchThreshold && !pronunciationFaults[index]

    /**
     * Records that the word at [position] was said but voiced wrongly. The
     * transcript cannot show this, so it overrides a match, and no later
     * window can clear it.
     */
    fun markPronunciationFault(position: Int) {
        if (position in expected.indices) pronunciationFaults[position] = true
    }

    /**
     * Folds one transcript window into the session.
     *
     * @param transcript raw Whisper output for the window.
     * @param confidence acoustic confidence of the window, in `0f..1f`.
     * @param windowStartMs offset of the window's start from the session start.
     * @param deferVerdicts hold back mistake verdicts so the caller can take a
     *   second listen first; collect them afterwards with [takeVerdicts].
     */
    fun onTranscript(
        transcript: String,
        confidence: Float,
        windowStartMs: Long,
        deferVerdicts: Boolean = false,
    ): TrackerUpdate {
        if (expected.isEmpty()) {
            return TrackerUpdate(state(), emptyList(), 0)
        }
        val heardTokens = ArabicNormalizer.tokenize(transcript)
        if (heardTokens.isEmpty()) {
            return TrackerUpdate(state(), emptyList(), 0)
        }
        // A whisper repetition loop is text the model produced, not text in the
        // audio. Keep only what came before the loop, and let that count as
        // evidence that words were recited but never as evidence of a mistake.
        val loopCut = repetitionLoopStart(heardTokens)
        val looped = loopCut != null
        val spoken = if (loopCut != null) heardTokens.subList(0, loopCut) else heardTokens

        val from = max(0, cursor - config.lookBack)
        val to = min(expected.size, cursor + spoken.size + config.lookAhead)
        if (from >= to) {
            return TrackerUpdate(state(), emptyList(), 0)
        }

        val alignment = align(spoken, from, to)
        val cursorBefore = cursor

        if (looped) {
            // Even a loop's first copy can be jumbled — "الحمد لله الرحمن الرحيم"
            // splices ayah 2's opening onto ayah 3 — so it may only move the
            // cursor through words heard in order starting exactly at the cursor.
            // Nothing else in it counts, for or against.
            val confident = alignment.matches
                .filter { it.score >= config.matchThreshold }
                .associateBy { it.expectedIndex }
            while (cursor < expected.size) {
                val pair = confident[cursor] ?: break
                bestScore[cursor] = max(bestScore[cursor], pair.score)
                cursor++
            }
        } else {
            for (pair in alignment.matches) {
                val index = pair.expectedIndex
                if (pair.score > bestScore[index]) {
                    bestScore[index] = pair.score
                }
                if (pair.score < config.matchThreshold) {
                    // Remember what was heard here in case this becomes a verdict.
                    if (heard[index] == null || confidence > heardConfidence[index]) {
                        heard[index] = pair.spokenWord
                        heardConfidence[index] = confidence
                        heardAtMs[index] = windowStartMs
                    }
                }
            }
            if (alignment.lastMatchedExpectedIndex >= 0) {
                cursor = max(cursor, alignment.lastMatchedExpectedIndex + 1)
            }
        }

        val mistakes = mutableListOf<Mistake>()
        if (!looped) collectExtraWords(alignment, spoken, confidence, windowStartMs, mistakes)
        if (!deferVerdicts) settle(confidence, mistakes)

        return TrackerUpdate(
            state = state(),
            newMistakes = mistakes,
            advancedBy = cursor - cursorBefore,
            loopTrimmed = looped,
        )
    }

    /**
     * Drops verdicts that later evidence overturned: a word judged missed or
     * wrong from one window that a later, better window heard correctly.
     */
    fun standingVerdicts(mistakes: List<Mistake>): List<Mistake> = mistakes.filter { mistake ->
        if (mistake.kind == MistakeKind.Pronunciation) return@filter true
        val wordIndex = mistake.wordIndex ?: return@filter true
        val position = expected.indexOfFirst {
            it.surahId == mistake.surahId && it.ayahNumber == mistake.ayahNumber && it.wordIndex == wordIndex
        }
        position < 0 || bestScore[position] < config.matchThreshold
    }

    /**
     * Where a repetition loop starts in [tokens]: the index just past the first
     * copy of the looping phrase, or null if there is none.
     *
     * Any phrase of one to four words repeated three times in a row is a loop —
     * that never happens anywhere in the Quran. A phrase of two to four words
     * repeated twice is a loop unless this passage itself repeats it. A single
     * word said twice is allowed (دكا دكا, صفا صفا).
     */
    private fun repetitionLoopStart(tokens: List<String>): Int? {
        for (i in tokens.indices) {
            for (unit in 1..4) {
                if (i + 2 * unit > tokens.size) break
                val phrase = tokens.subList(i, i + unit)
                var copies = 1
                while (i + (copies + 1) * unit <= tokens.size &&
                    tokens.subList(i + copies * unit, i + (copies + 1) * unit) == phrase
                ) {
                    copies++
                }
                val loop = copies >= 3 || (copies == 2 && unit >= 2 && !isPassageRepeat(phrase))
                if (loop) return i + unit
            }
        }
        return null
    }

    private fun isPassageRepeat(phrase: List<String>): Boolean = passageRepeats.any { repeat ->
        repeat.size == phrase.size &&
            repeat.indices.all { WordSimilarity.best(phrase[it], repeat[it].variants) >= config.matchThreshold }
    }

    /** Issues the verdicts held back by a [onTranscript] call with `deferVerdicts`. */
    fun takeVerdicts(confidence: Float): List<Mistake> {
        val mistakes = mutableListOf<Mistake>()
        settle(confidence, mistakes)
        return mistakes
    }

    /**
     * Marks the whole session finished and returns any remaining verdicts,
     * including words never reached.
     */
    fun finish(confidence: Float = 1f): List<Mistake> {
        cursor = expected.size
        val mistakes = mutableListOf<Mistake>()
        settle(confidence, mistakes, settleAll = true)
        return mistakes
    }

    /** Reveals the expected word at the cursor without judging it (memorisation hint). */
    fun peek(): ExpectedWord? = expected.getOrNull(cursor)

    /**
     * Accepts the word at the cursor as recited, used when the reciter asks for a
     * hint and moves on. The word is recorded as a miss so insights stay honest.
     */
    fun skipCurrent(atMs: Long): Mistake? {
        val word = expected.getOrNull(cursor) ?: return null
        cursor = min(expected.size, cursor + 1)
        val mistake = Mistake(
            kind = MistakeKind.MissedWord,
            surahId = word.surahId,
            ayahNumber = word.ayahNumber,
            wordIndex = word.wordIndex,
            expected = word.uthmani,
            heard = null,
            atMs = atMs,
            confidence = 1f,
        )
        // Skipping is an explicit user action, so the verdict is immediate.
        bestScore[word.position] = 0.0
        settledUpTo = max(settledUpTo, word.position + 1)
        return mistake
    }

    private fun statuses(): List<WordStatus> = List(expected.size) { index ->
        when {
            pronunciationFaults[index] -> WordStatus.Wrong
            bestScore[index] >= config.matchThreshold -> WordStatus.Correct
            // Only call it wrong when the evidence is strong enough to report as
            // a mistake; otherwise the display would accuse where the verdict
            // (see settle) deliberately does not.
            index < cursor && heard[index] != null &&
                heardConfidence[index] >= config.minMistakeConfidence -> WordStatus.Wrong
            index < cursor && heard[index] != null -> WordStatus.Unclear
            index < cursor -> WordStatus.Missed
            index == cursor -> WordStatus.Current
            else -> WordStatus.Pending
        }
    }

    private fun settle(confidence: Float, into: MutableList<Mistake>, settleAll: Boolean = false) {
        val bound = if (settleAll) expected.size else cursor - config.settleWords
        while (settledUpTo < bound) {
            val index = settledUpTo
            settledUpTo++
            if (bestScore[index] >= config.matchThreshold) continue

            val word = expected[index]
            val heardWord = heard[index]
            val reportConfidence = if (heardWord != null) heardConfidence[index] else confidence
            if (reportConfidence < config.minMistakeConfidence) continue

            into += Mistake(
                kind = if (heardWord != null) MistakeKind.WrongWord else MistakeKind.MissedWord,
                surahId = word.surahId,
                ayahNumber = word.ayahNumber,
                wordIndex = word.wordIndex,
                expected = word.uthmani,
                heard = heardWord,
                atMs = heardAtMs[index].takeIf { it >= 0 } ?: 0L,
                confidence = reportConfidence,
            )
        }
    }

    private fun collectExtraWords(
        alignment: Alignment,
        spoken: List<String>,
        confidence: Float,
        windowStartMs: Long,
        into: MutableList<Mistake>,
    ) {
        if (confidence < config.minExtraWordConfidence) return
        val anchor = expected.getOrNull(min(cursor, expected.size - 1)) ?: return
        for (spokenIndex in alignment.insertions) {
            // A window cuts whatever is being said at its edges, and whisper
            // turns the half-words it finds there into nonsense tokens. Those are
            // never evidence of an added word: a word the reciter genuinely adds
            // sits mid-phrase in at least one of the overlapping windows.
            if (spokenIndex == 0 || spokenIndex == spoken.lastIndex) continue
            val word = spoken.getOrNull(spokenIndex) ?: continue
            // Windows overlap, so a word the reciter really adds is heard by at
            // least two of them; a one-off mishearing — وإياك split into "ويا كما"
            // — does not repeat the same way. Report on the second sighting, and
            // only then: later windows re-hearing it must not report it again.
            val key = "${anchor.position}:$word"
            val sightings = (extraSightings[key] ?: 0) + 1
            extraSightings[key] = sightings
            if (sightings != EXTRA_WORD_SIGHTINGS) continue
            into += Mistake(
                kind = MistakeKind.ExtraWord,
                surahId = anchor.surahId,
                ayahNumber = anchor.ayahNumber,
                wordIndex = null,
                expected = null,
                heard = word,
                atMs = windowStartMs,
                confidence = confidence,
            )
        }
    }

    // ---- Semi-global alignment -------------------------------------------------

    private class MatchPair(val expectedIndex: Int, val spokenWord: String, val score: Double)

    private class Alignment(
        val matches: List<MatchPair>,
        val insertions: List<Int>,
        val lastMatchedExpectedIndex: Int,
    )

    /**
     * Aligns [spoken] against `expected[from, to)`.
     *
     * Trailing expected words may be skipped for free, as may leading words
     * already behind the cursor — the window starts and ends wherever the audio
     * happens to. Skipping a pending word costs as much as missing it. Spoken
     * words that align to nothing are charged an insertion penalty and surface
     * as extra words.
     */
    private fun align(spoken: List<String>, from: Int, to: Int): Alignment {
        val rows = to - from
        val cols = spoken.size
        val dp = Array(rows + 1) { DoubleArray(cols + 1) }
        val back = Array(rows + 1) { ByteArray(cols + 1) }

        for (j in 1..cols) {
            dp[0][j] = dp[0][j - 1] + INSERTION_PENALTY
            back[0][j] = MOVE_INSERT
        }
        // Words behind the cursor were already heard, so a window may start
        // anywhere among them for free. Skipping a *pending* word, though, means
        // it was never said, and costs the same as missing one mid-phrase.
        // Without that distinction, a phrase the Quran repeats — the end of 1:1
        // and all of 1:3, say — matched its earlier copy as cheaply as the copy
        // at the cursor, and the copy at the cursor went unmatched.
        val behindCursor = (cursor - from).coerceIn(0, rows)
        for (i in 1..rows) {
            dp[i][0] = if (i <= behindCursor) 0.0 else (i - behindCursor) * SKIP_EXPECTED_PENALTY
            back[i][0] = MOVE_SKIP_EXPECTED
        }

        for (i in 1..rows) {
            // Re-hearing a word already confirmed is expected with overlapping
            // windows, but it should never outscore hearing a word still waiting
            // for confirmation. A word the cursor jumped over is still waiting,
            // so it gets no penalty: otherwise a second listen that hears it
            // would credit an earlier, already-confirmed copy instead.
            val position = from + i - 1
            val rehearPenalty =
                if (position < cursor && bestScore[position] >= config.matchThreshold) REHEAR_PENALTY else 0.0
            for (j in 1..cols) {
                val similarity = WordSimilarity.best(spoken[j - 1], expected[from + i - 1].variants)
                val matchScore = dp[i - 1][j - 1] + (similarity * 2.0 - 1.0) - rehearPenalty
                val skipExpected = dp[i - 1][j] + SKIP_EXPECTED_PENALTY
                val insert = dp[i][j - 1] + INSERTION_PENALTY

                var best = matchScore
                var move = MOVE_MATCH
                if (skipExpected > best) {
                    best = skipExpected
                    move = MOVE_SKIP_EXPECTED
                }
                if (insert > best) {
                    best = insert
                    move = MOVE_INSERT
                }
                dp[i][j] = best
                back[i][j] = move
            }
        }

        // Trailing expected words may be skipped for free, so end wherever the
        // score peaked in the last column.
        var endRow = 0
        for (i in 0..rows) {
            if (dp[i][cols] > dp[endRow][cols]) endRow = i
        }

        val matches = mutableListOf<MatchPair>()
        val insertions = mutableListOf<Int>()
        var i = endRow
        var j = cols
        var lastMatched = -1
        while (i > 0 || j > 0) {
            val move = when {
                i == 0 -> MOVE_INSERT
                j == 0 -> MOVE_SKIP_EXPECTED
                else -> back[i][j]
            }
            when (move) {
                MOVE_MATCH -> {
                    val expectedIndex = from + i - 1
                    val spokenWord = spoken[j - 1]
                    val score = WordSimilarity.best(spokenWord, expected[expectedIndex].variants)
                    matches += MatchPair(expectedIndex, spokenWord, score)
                    if (score >= config.matchThreshold && expectedIndex > lastMatched) {
                        lastMatched = expectedIndex
                    }
                    i--
                    j--
                }
                MOVE_INSERT -> {
                    insertions += j - 1
                    j--
                }
                else -> i--
            }
        }
        return Alignment(matches, insertions, lastMatched)
    }

    private companion object {
        const val SKIP_EXPECTED_PENALTY = -0.6
        const val INSERTION_PENALTY = -0.7
        const val REHEAR_PENALTY = 0.1
        const val EXTRA_WORD_SIGHTINGS = 2
        const val MOVE_MATCH: Byte = 0
        const val MOVE_SKIP_EXPECTED: Byte = 1
        const val MOVE_INSERT: Byte = 2
    }
}
