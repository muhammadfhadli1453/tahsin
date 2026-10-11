package com.tahsin.app.domain.text

/**
 * A Tajweed rule that can be shown over the mushaf text.
 *
 * These describe how the text *should* be recited. Nothing here inspects the
 * reciter's audio — Tahsin cannot hear whether a ghunnah was held for two counts.
 * This is the colouring a printed mushaf gives you, derived from the text.
 */
enum class TajweedRule(val label: String, val description: String) {
    /** Noon or meem carrying shadda: nasalised for two counts. */
    Ghunnah("Ghunnah", "Hold the nasal sound for two counts"),

    /** Noon sakinah or tanween before one of the fifteen ikhfa letters. */
    IkhfaNoon("Ikhfa", "Hide the noon into a nasal sound"),

    /** Noon sakinah or tanween before ي ن م و. */
    IdghamGhunnah("Idgham (ghunnah)", "Merge the noon into the next letter, nasalised"),

    /** Noon sakinah or tanween before ل ر. */
    IdghamNoGhunnah("Idgham (no ghunnah)", "Merge the noon into the next letter, no nasal"),

    /** Noon sakinah or tanween before ب, marked in the mushaf with a small meem. */
    Iqlab("Iqlab", "Turn the noon into a meem"),

    /** Meem sakinah before ب. */
    IkhfaShafawi("Ikhfa shafawi", "Hide the meem into the ب with a light nasal"),

    /** Meem sakinah before another meem. */
    IdghamShafawi("Idgham shafawi", "Merge the meem into the next meem"),

    /** ق ط ب ج د carrying sukun: echoed. */
    Qalqalah("Qalqalah", "Give the letter a light echo"),

    /** Madd letter meeting a hamza inside the same word: 4-5 counts. */
    MaddObligatory("Madd muttasil", "Stretch for four to five counts"),

    /** Madd letter at the end of a word before a hamza: 4-5 counts. */
    MaddPermissible("Madd munfasil", "Stretch for four to five counts"),

    /** Madd letter meeting a sukun or shadda: six counts. */
    MaddNecessary("Madd lazim", "Stretch for six counts"),

    /** A letter written but not pronounced. */
    Silent("Silent", "Written but not pronounced"),
}

/** A [rule] applying to `text.substring(start, end)` of one ayah. */
data class TajweedSpan(val start: Int, val end: Int, val rule: TajweedRule)

/**
 * Derives Tajweed rules from the Uthmani text of an ayah.
 *
 * The Tanzil Uthmani text makes this tractable: iqlab carries a small high meem
 * (U+06E2), madd carries a maddah (U+0653), unpronounced letters carry a small
 * high zero (U+06DF/U+06E0), and — crucially — an assimilated or hidden noon is
 * written bare, with no sukun at all. So "noon with no vowel" is a reliable
 * marker for noon sakinah, and the rest follows from the letter that comes next.
 */
object TajweedAnalyzer {

    private const val FATHA = 'َ'
    private const val DAMMA = 'ُ'
    private const val KASRA = 'ِ'
    private const val FATHATAN = 'ً'
    private const val DAMMATAN = 'ٌ'
    private const val KASRATAN = 'ٍ'
    private const val SHADDA = 'ّ'
    private const val SUKUN = 'ْ'
    private const val MADDAH = 'ٓ'
    private const val SUPERSCRIPT_ALEF = 'ٰ'
    private const val SMALL_HIGH_MEEM = 'ۢ'
    private const val SMALL_LOW_MEEM = 'ۭ'
    private const val SMALL_HIGH_ROUNDED_ZERO = '۟'
    private const val SMALL_HIGH_RECTANGULAR_ZERO = '۠'
    private const val TATWEEL = 'ـ'

    private val harakat = setOf(FATHA, DAMMA, KASRA)
    private val tanween = setOf(FATHATAN, DAMMATAN, KASRATAN)

    /** Letters after which a noon sakinah stays clear. */
    private val izharLetters = setOf('ء', 'أ', 'إ', 'ؤ', 'ئ', 'ه', 'ع', 'ح', 'غ', 'خ')
    private val idghamGhunnahLetters = setOf('ي', 'ن', 'م', 'و')
    private val idghamNoGhunnahLetters = setOf('ل', 'ر')
    private val qalqalahLetters = setOf('ق', 'ط', 'ب', 'ج', 'د')
    private val hamzaForms = setOf('ء', 'أ', 'إ', 'ؤ', 'ئ', 'آ')

    /** One Arabic letter together with the marks written on it. */
    private class Unit(
        val letter: Char,
        val marks: String,
        val start: Int,
        val end: Int,
        val startsWord: Boolean,
    ) {
        fun has(mark: Char) = marks.indexOf(mark) >= 0
        val hasIqlabMark get() = has(SMALL_HIGH_MEEM) || has(SMALL_LOW_MEEM)
        val hasHarakah get() = marks.any { it in harakat }
        val hasTanween get() = marks.any { it in tanween }
        val isSilent get() = has(SMALL_HIGH_ROUNDED_ZERO) || has(SMALL_HIGH_RECTANGULAR_ZERO)

        /** No vowel of any kind: the Uthmani way of writing an assimilated consonant. */
        val isVowelless get() = !hasHarakah && !hasTanween && !has(SHADDA) && !has(SUPERSCRIPT_ALEF)
    }

    fun analyze(uthmani: String): List<TajweedSpan> {
        val units = tokenize(uthmani)
        if (units.isEmpty()) return emptyList()

        val spans = mutableListOf<TajweedSpan>()
        for ((index, unit) in units.withIndex()) {
            if (unit.isSilent) {
                spans += TajweedSpan(unit.start, unit.end, TajweedRule.Silent)
                continue
            }
            noonRuleFor(units, index, unit)?.let { spans += TajweedSpan(unit.start, unit.end, it) }
            meemRuleFor(units, index, unit)?.let { spans += TajweedSpan(unit.start, unit.end, it) }
            maddRuleFor(units, index, unit)?.let { spans += TajweedSpan(unit.start, unit.end, it) }
            if (unit.letter in qalqalahLetters && unit.has(SUKUN)) {
                spans += TajweedSpan(unit.start, unit.end, TajweedRule.Qalqalah)
            }
        }
        // One rule per position keeps the colouring unambiguous; earlier rules in
        // the pass are the more specific ones.
        return spans.distinctBy { it.start }.sortedBy { it.start }
    }

    private fun noonRuleFor(units: List<Unit>, index: Int, unit: Unit): TajweedRule? {
        // The mushaf writes iqlab explicitly, with a small meem above the letter
        // after a fatha or damma tanween and below it after a kasratan, so both
        // positions have to be recognised. This is checked before everything else
        // because a doubled noon can carry a tanween that is itself flipped to a
        // meem (مُطْمَئِنٌّۢ بِ) — the written instruction is the specific one.
        if (unit.hasIqlabMark) return TajweedRule.Iqlab

        if (unit.letter == 'ن' && unit.has(SHADDA)) return TajweedRule.Ghunnah

        val isNoonSakinah = unit.letter == 'ن' && unit.isVowelless
        if (!isNoonSakinah && !unit.hasTanween) return null

        val next = nextPronounced(units, index)
        val following = next?.letter ?: return null

        // A word opening with alef wasla is elided, and a helping vowel is
        // inserted after the tanween instead — so no noon rule applies. The
        // mushaf leaves these unmarked too (عُزَيْرٌ ٱبْنُ).
        if (following == 'ا' && next.startsWord && !next.hasHarakah) return null
        return when (following) {
            in izharLetters -> null // izhar is the default reading; leave it plain
            in idghamGhunnahLetters -> TajweedRule.IdghamGhunnah
            in idghamNoGhunnahLetters -> TajweedRule.IdghamNoGhunnah
            'ب' -> TajweedRule.Iqlab
            else -> TajweedRule.IkhfaNoon
        }
    }

    private fun meemRuleFor(units: List<Unit>, index: Int, unit: Unit): TajweedRule? {
        if (unit.letter != 'م') return null
        if (unit.has(SHADDA)) return TajweedRule.Ghunnah
        if (!unit.isVowelless) return null
        return when (nextPronounced(units, index)?.letter) {
            'ب' -> TajweedRule.IkhfaShafawi
            'م' -> TajweedRule.IdghamShafawi
            else -> null // izhar shafawi: nothing special to show
        }
    }

    private fun maddRuleFor(units: List<Unit>, index: Int, unit: Unit): TajweedRule? {
        if (!unit.has(MADDAH)) return null
        val next = nextPronounced(units, index) ?: return TajweedRule.MaddNecessary
        return when {
            // A madd running into a doubled or silent consonant is the six-count one.
            next.has(SHADDA) || next.has(SUKUN) -> TajweedRule.MaddNecessary
            next.letter in hamzaForms && !next.startsWord -> TajweedRule.MaddObligatory
            next.letter in hamzaForms -> TajweedRule.MaddPermissible
            else -> TajweedRule.MaddNecessary
        }
    }

    private fun nextPronounced(units: List<Unit>, index: Int): Unit? {
        for (i in index + 1 until units.size) {
            val candidate = units[i]
            if (candidate.isSilent) continue
            // The alef written after a fathatan only carries the tanween and is
            // not sounded — but only when it belongs to the same word. A
            // word-initial alef is a wasla and must not be skipped.
            if (candidate.letter == 'ا' && candidate.marks.isEmpty() &&
                !candidate.startsWord && units[i - 1].hasTanween
            ) {
                continue
            }
            return candidate
        }
        return null
    }

    private fun tokenize(text: String): List<Unit> {
        val units = mutableListOf<Unit>()
        var index = 0
        var startsWord = true
        while (index < text.length) {
            val ch = text[index]
            if (ch.isWhitespace()) {
                startsWord = true
                index++
                continue
            }
            // Tatweel is a shaping joiner, not a letter, but marks sit on it — so
            // fold it and its marks into the letter it extends.
            if (ch == TATWEEL) {
                val marksEnd = consumeMarks(text, index + 1)
                if (units.isNotEmpty()) {
                    val previous = units.removeAt(units.lastIndex)
                    units += Unit(
                        letter = previous.letter,
                        marks = previous.marks + text.substring(index + 1, marksEnd),
                        start = previous.start,
                        end = marksEnd,
                        startsWord = previous.startsWord,
                    )
                }
                index = marksEnd
                continue
            }
            if (!isArabicLetter(ch)) {
                index++
                continue
            }
            val marksEnd = consumeMarks(text, index + 1)
            units += Unit(
                letter = foldLetter(ch),
                marks = text.substring(index + 1, marksEnd),
                start = index,
                end = marksEnd,
                startsWord = startsWord,
            )
            startsWord = false
            index = marksEnd
        }
        return units
    }

    private fun consumeMarks(text: String, from: Int): Int {
        var index = from
        while (index < text.length && isMark(text[index])) index++
        return index
    }

    private fun isMark(ch: Char): Boolean = when (ch.code) {
        in 0x0610..0x061A, in 0x064B..0x065F, 0x0670, in 0x06D6..0x06ED -> true
        else -> false
    }

    private fun isArabicLetter(ch: Char): Boolean =
        ch.code in 0x0621..0x064A || ch.code in 0x0671..0x06D3

    /** Alef wasla and the hamza-carrying alefs behave as plain alef for these rules. */
    private fun foldLetter(ch: Char): Char = when (ch) {
        'ٱ' -> 'ا'
        else -> ch
    }
}
