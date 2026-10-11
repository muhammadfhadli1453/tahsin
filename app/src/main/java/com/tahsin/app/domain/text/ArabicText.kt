package com.tahsin.app.domain.text

/**
 * Folds Quranic Arabic down to a comparison form.
 *
 * Whisper emits plain imlaai Arabic with ordinary punctuation, while the mushaf
 * text carries full tashkeel plus Quranic annotation marks. Everything that only
 * exists in one of those two worlds has to be removed before the two can be
 * compared, otherwise every single word registers as a mistake.
 */
object ArabicNormalizer {

    /**
     * Harakat, tanween, shadda, sukun, superscript alef, Quranic annotation and
     * waqf marks, tatweel, and the bidi/zero-width controls Whisper sometimes
     * emits around Arabic runs.
     */
    private val removable = Regex(
        "[" +
            "\\u0610-\\u061A" + // Quranic honorifics
            "\\u064B-\\u065F" + // harakat, tanween, shadda, sukun
            "\\u0670" + // superscript alef
            "\\u06D6-\\u06ED" + // small high/low marks, waqf signs, sajdah
            "\\u0640" + // tatweel
            "\\u08D3-\\u08FF" + // extended Arabic marks
            "\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u061C" + // invisibles
            "\\uFE00-\\uFE0F" + // variation selectors
            "]",
    )

    /** Punctuation, Latin text and digits that never participate in a word match. */
    private val strippable = Regex("[^\\u0621-\\u064A\\u0671-\\u06D5\\s]")

    private val whitespace = Regex("\\s+")

    private const val DAGGER_ALEF = '\u0670'

    private val letterFolding = mapOf(
        'آ' to 'ا', // آ
        'أ' to 'ا', // أ
        'إ' to 'ا', // إ
        'ٱ' to 'ا', // ٱ alef wasla
        'ى' to 'ي', // ى alef maqsura (dotless ya)
        'ي' to 'ي', // ي (identity, keeps the map total)
        'ة' to 'ه', // ة ta marbuta
        'ؤ' to 'و', // ؤ
        'ئ' to 'ي', // ئ
        'ك' to 'ك', // ك (identity)
        'ک' to 'ك', // ک Persian kaf
        'ی' to 'ي', // ی Persian ya
        'ء' to 'ا', // ء standalone hamza
    )

    /**
     * Quranic orthography writes some long vowels as a consonant carrying a
     * superscript alef (`\u0670`), where modern imlaai spelling — which is what
     * Whisper emits — writes a plain alef. Rewriting those pairs before the marks
     * are stripped keeps words like `\u0627\u0644\u0635\u0644\u0648\u0670\u0629`
     * comparable to `\u0627\u0644\u0635\u0644\u0627\u0629`.
     */
    private val superscriptAlefPairs = listOf(
        "\u0648\u0670" to "\u0627", // waw + superscript alef
        "\u0649\u0670" to "\u0627", // alef maqsura + superscript alef
        "\u064A\u0670" to "\u0627", // ya + superscript alef
    )

    /**
     * Returns the space-separated comparison form of [text]: no diacritics, no
     * punctuation, hamza/alef/ya/ta-marbuta variants folded together.
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        var source = text
        for ((from, to) in superscriptAlefPairs) {
            if (source.contains(from)) source = source.replace(from, to)
        }
        val stripped = strippable.replace(removable.replace(source, ""), " ")
        val folded = StringBuilder(stripped.length)
        for (ch in stripped) {
            folded.append(letterFolding[ch] ?: ch)
        }
        return whitespace.replace(folded, " ").trim()
    }

    /**
     * Comparison forms that a reciter may legitimately produce for [text].
     *
     * A bare superscript alef (the "dagger alef") is ambiguous: in
     * `\u0627\u0644\u0639\u064E\u0640\u0670\u0644\u0645\u0650\u064A\u0646`
     * modern spelling restores the alef, while in
     * `\u0627\u0644\u0631\u064E\u0651\u062D\u0645\u064E\u0640\u0670\u0646`
     * it conventionally does not. Rather than pick a side and be wrong half the
     * time, both readings are offered and the matcher takes whichever scores
     * higher. The first entry is always the primary form.
     */
    fun normalizeVariants(text: String): List<String> {
        val primary = normalize(text)
        if (primary.isEmpty()) return listOf(primary)
        var carriesDaggerAlef = text
        for ((from, _) in superscriptAlefPairs) {
            carriesDaggerAlef = carriesDaggerAlef.replace(from, "")
        }
        if (!carriesDaggerAlef.contains(DAGGER_ALEF)) return listOf(primary)
        val restored = normalize(text.replace(DAGGER_ALEF.toString(), "\u0627"))
        return if (restored == primary) listOf(primary) else listOf(primary, restored)
    }

    fun tokenize(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(' ').filter { it.isNotEmpty() }
    }

    /** A display word together with where it sits in the ayah it came from. */
    data class DisplayWord(val text: String, val start: Int, val end: Int)

    /**
     * Splits the mushaf form of an ayah into display words, keeping the original
     * glyphs and their offsets. Words the comparison form drops entirely (a lone
     * waqf mark, for example) are folded into the previous word so display and
     * comparison sequences stay index-aligned.
     *
     * Each word's [DisplayWord.text] is exactly `uthmani.substring(start, end)`,
     * which is what lets Tajweed spans computed over the whole ayah be sliced
     * back onto individual words.
     */
    fun displayWordsWithOffsets(uthmani: String): List<DisplayWord> {
        val result = mutableListOf<DisplayWord>()
        var index = 0
        while (index < uthmani.length) {
            if (uthmani[index].isWhitespace()) {
                index++
                continue
            }
            val start = index
            while (index < uthmani.length && !uthmani[index].isWhitespace()) index++
            val token = uthmani.substring(start, index)
            if (normalize(token).isEmpty() && result.isNotEmpty()) {
                val previous = result.removeAt(result.lastIndex)
                result += DisplayWord(
                    text = uthmani.substring(previous.start, index),
                    start = previous.start,
                    end = index,
                )
            } else {
                result += DisplayWord(token, start, index)
            }
        }
        return result
    }

    fun displayWords(uthmani: String): List<String> =
        displayWordsWithOffsets(uthmani).map { it.text }
}
