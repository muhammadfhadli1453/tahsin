package com.tahsin.app.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every fixture here is the real Tanzil Uthmani text of the cited ayah, so the
 * test also pins the text conventions the analyzer depends on: iqlab marked with
 * U+06E2, madd with U+0653, and an assimilated noon written bare.
 */
class TajweedAnalyzerTest {

    private fun rulesIn(text: String): List<TajweedRule> =
        TajweedAnalyzer.analyze(text).map { it.rule }

    /** The rule applying to the first occurrence of [needle], if any. */
    private fun ruleAt(text: String, needle: String): TajweedRule? {
        val at = text.indexOf(needle)
        require(at >= 0) { "fixture does not contain $needle" }
        return TajweedAnalyzer.analyze(text)
            .firstOrNull { it.start <= at && at < it.end }
            ?.rule
    }

    @Test
    fun `noon sakinah before an ikhfa letter is ikhfa`() {
        // 2:4 — أُنزِلَ, a bare noon before ز
        val text = "وَمَآ أُنزِلَ مِن قَبْلِكَ"
        assertEquals(TajweedRule.IkhfaNoon, ruleAt(text, "نز"))
    }

    @Test
    fun `noon sakinah at the end of a word before an ikhfa letter is ikhfa`() {
        // 2:4 — مِن قَبْلِكَ, the rule reaches across the space
        val text = "مِن قَبْلِكَ"
        assertEquals(TajweedRule.IkhfaNoon, ruleAt(text, "ن"))
    }

    @Test
    fun `noon sakinah before ya is idgham with ghunnah`() {
        // 2:8 — مَن يَقُولُ
        val text = "مَن يَقُولُ"
        assertEquals(TajweedRule.IdghamGhunnah, ruleAt(text, "ن"))
    }

    @Test
    fun `noon sakinah before ra is idgham without ghunnah`() {
        // 2:5 — مِن رَّبِّهِمْ
        val text = "مِن رَّبِّهِمْ"
        assertEquals(TajweedRule.IdghamNoGhunnah, ruleAt(text, "ن"))
    }

    @Test
    fun `noon sakinah before a throat letter stays plain`() {
        // 6:26 — مِنْهُ; izhar is the default reading, so nothing is highlighted.
        val text = "مِنْهُ"
        assertEquals(null, ruleAt(text, "نْ"))
    }

    @Test
    fun `the small high meem marks iqlab`() {
        // 2:33 — أَنۢبِئْهُم
        val text = "أَنۢبِئْهُم"
        assertEquals(TajweedRule.Iqlab, ruleAt(text, "نۢ"))
    }

    @Test
    fun `iqlab is detected on tanween across a word boundary`() {
        // 2:19 — مُحِيطٌۢ بِٱلْكَـٰفِرِينَ
        val text = "مُحِيطٌۢ بِٱلْكَـٰفِرِينَ"
        assertEquals(TajweedRule.Iqlab, ruleAt(text, "ط"))
    }

    @Test
    fun `tanween before a shaddad meem is idgham with ghunnah`() {
        // 2:19 — كَصَيِّبٍ مِّنَ
        val text = "كَصَيِّبٍ مِّنَ"
        assertEquals(TajweedRule.IdghamGhunnah, ruleAt(text, "بٍ"))
    }

    @Test
    fun `a silent alef after tanween is skipped when looking ahead`() {
        // 112:4 — كُفُوًا أَحَدٌ: the alef is not sounded, so the next letter is أ,
        // a throat letter, and the tanween stays plain rather than becoming ikhfa.
        val text = "كُفُوًا أَحَدٌ"
        assertEquals(null, ruleAt(text, "وً"))
    }

    @Test
    fun `noon with shadda is ghunnah`() {
        // 2:8 — ءَامَنَّا
        val text = "ءَامَنَّا"
        assertEquals(TajweedRule.Ghunnah, ruleAt(text, "نَّ"))
    }

    @Test
    fun `meem with shadda is ghunnah`() {
        // 2:5 — ثُمَّ
        assertEquals(TajweedRule.Ghunnah, ruleAt("ثُمَّ", "مَّ"))
    }

    @Test
    fun `meem sakinah before ba is ikhfa shafawi`() {
        // 2:8 — هُم بِمُؤْمِنِينَ
        val text = "هُم بِمُؤْمِنِينَ"
        assertEquals(TajweedRule.IkhfaShafawi, ruleAt(text, "م "))
    }

    @Test
    fun `meem sakinah before another meem is idgham shafawi`() {
        val text = "لَهُم مَّا"
        assertEquals(TajweedRule.IdghamShafawi, ruleAt(text, "م "))
    }

    @Test
    fun `meem sakinah before anything else stays plain`() {
        // 2:4 — هُمْ يُوقِنُونَ is izhar shafawi
        assertEquals(null, ruleAt("هُمْ يُوقِنُونَ", "مْ"))
    }

    @Test
    fun `qalqalah letters with sukun are marked`() {
        // 2:19 — يَجْعَلُونَ
        assertEquals(TajweedRule.Qalqalah, ruleAt("يَجْعَلُونَ", "جْ"))
    }

    @Test
    fun `a qalqalah letter carrying a vowel is not marked`() {
        // 2:19 — وَبَرْقٌ: the ق takes tanween, so there is no echo mid-phrase.
        assertEquals(null, ruleAt("وَبَرْقٌ", "قٌ"))
    }

    // The mushaf writes a long madd as plain alef plus a maddah mark (U+0653),
    // not as the precomposed alef-with-madda (U+0622), so these fixtures spell it
    // out to keep the distinction visible.
    private val maddah = "\u0653"

    @Test
    fun `madd meeting a hamza inside the word is muttasil`() {
        // 2:19 — ٱلسَّمَآءِ
        val text = "\u0671لس\u0651\u064Eم\u064Eا\u0653ء\u0650"
        assertEquals(TajweedRule.MaddObligatory, ruleAt(text, "ا$maddah"))
    }

    @Test
    fun `madd at a word end before a hamza is munfasil`() {
        // 2:4 — بِمَآ أُنزِلَ
        val text = "ب\u0650م\u064Eا\u0653 \u0623\u064Fنز\u0650ل\u064E"
        assertEquals(TajweedRule.MaddPermissible, ruleAt(text, "ا$maddah"))
    }

    @Test
    fun `madd meeting a shadda is the six count madd`() {
        // 1:7 — ٱلضَّآلِّينَ
        val text = "\u0671لض\u0651\u064Eا\u0653ل\u0651\u0650ين\u064E"
        assertEquals(TajweedRule.MaddNecessary, ruleAt(text, "ا$maddah"))
    }

    @Test
    fun `a letter carrying the small high zero is silent`() {
        // 18:38 — لَّـٰكِنَّا۠, and the common ٱلظُّنُونَا۠
        val text = "لَّـٰكِنَّا۠"
        assertTrue(TajweedRule.Silent in rulesIn(text))
    }

    @Test
    fun `plain text with no rules produces no spans`() {
        assertEquals(emptyList<TajweedRule>(), rulesIn("رَبِّ"))
    }

    @Test
    fun `empty and mark-only input is handled`() {
        assertEquals(emptyList<TajweedRule>(), rulesIn(""))
        assertEquals(emptyList<TajweedRule>(), rulesIn("ۚ ۖ"))
    }

    @Test
    fun `spans never overlap and stay inside the text`() {
        val text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ"
        val spans = TajweedAnalyzer.analyze(text)
        var previousEnd = 0
        for (span in spans) {
            assertTrue("span starts before the previous one ends", span.start >= previousEnd)
            assertTrue(span.end <= text.length)
            assertTrue(span.start < span.end)
            previousEnd = span.end
        }
    }
}
