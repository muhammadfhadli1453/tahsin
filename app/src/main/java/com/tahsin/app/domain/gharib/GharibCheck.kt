package com.tahsin.app.domain.gharib

import com.tahsin.app.domain.model.ExpectedWord

/**
 * Gharib words: the handful of places where Hafs reads against the letters as
 * written — imala in 11:41 مَجْر۪ىٰهَا, ishmam in 12:11, tas-hil in 41:44, and so on.
 *
 * The speech model cannot hear these. It writes what the word *is*, not how it
 * was voiced, so مجراها read with or without imala comes out the same. Each kind
 * of gharib that can be checked gets its own acoustic measurement instead, run
 * only on that word's audio.
 */
enum class GharibKind(
    /** The mushaf mark that identifies this kind. */
    val mark: Char,
    /** What the reciter should do, shown when the check fails. */
    val guidance: String,
) {
    /** U+06EA, a small low circle under the ra: the alif is read leaning toward ē. */
    Imala('۪', "read with imala — the ā after ra leans to ē (majrēhā)"),
    ;

    companion object {
        fun of(word: ExpectedWord): GharibKind? = entries.firstOrNull { word.uthmani.indexOf(it.mark) >= 0 }
    }
}

enum class GharibVerdict { Correct, Wrong, Unclear }

/**
 * Imala, measured. In مَجْر۪ىٰهَا the long vowel after ra is fronted to ē, which
 * raises the second formant (F2) far above the plain ā of the هَا that follows.
 * Read without imala — majrāhā, or majrohā — the ra is heavy and its vowel's F2
 * sits *below* that ā.
 *
 * So the measure is the F2 of the word's two longest sustained vowels, earlier
 * minus later. On four master reciters it was +180 to +650 Hz for this word,
 * and -310 to -430 Hz for ذِكْرَاهَا (79:43), the same ending without imala.
 * Between the two thresholds the verdict is Unclear and nothing is reported.
 *
 * Which vowels count as "sustained" depends on how steady F2 must hold, and on
 * a voice whose track breaks into many short runs (Alafasy) that choice can
 * flip the sign. So the measure is taken at three tolerances and must agree:
 * Wrong needs most of them clearly negative and none positive at all. A false
 * accusation costs more than a missed one.
 */
object ImalaCheck {
    const val CORRECT_ABOVE_HZ = 100.0
    const val WRONG_BELOW_HZ = -150.0
    private const val MIN_VOWEL_FRAMES = 8
    private val TOLERANCES = listOf(0.05, 0.07, 0.10)

    /** [deltasHz] holds one reading per tolerance; null where too little was voiced. */
    data class Measurement(val deltasHz: List<Double?>, val verdict: GharibVerdict)

    fun measure(word: FloatArray, sampleRate: Int): Measurement {
        val track = FormantTracker.medianFilter(FormantTracker.f2Track(word, sampleRate))
        val deltas = TOLERANCES.map { tolerance -> delta(track, tolerance) }
        val readings = deltas.filterNotNull()
        val majority = TOLERANCES.size / 2 + 1
        val verdict = when {
            readings.count { it <= WRONG_BELOW_HZ } >= majority && readings.none { it >= 0 } -> GharibVerdict.Wrong
            readings.count { it >= CORRECT_ABOVE_HZ } >= majority -> GharibVerdict.Correct
            else -> GharibVerdict.Unclear
        }
        return Measurement(deltas, verdict)
    }

    /** F2 of the earlier of the two longest sustained vowels minus the later's. */
    private fun delta(track: DoubleArray, tolerance: Double): Double? {
        val vowels = FormantTracker.stableRuns(track, tolerance).filter { it.length >= MIN_VOWEL_FRAMES }
        if (vowels.size < 2) return null
        val (earlier, later) = vowels.sortedByDescending { it.length }.take(2).sortedBy { it.start }
        return earlier.median - later.median
    }
}
