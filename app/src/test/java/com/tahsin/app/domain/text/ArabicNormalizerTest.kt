package com.tahsin.app.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicNormalizerTest {

    @Test
    fun `strips tashkeel and folds alef wasla`() {
        assertEquals("بسم الله الرحمن الرحيم", ArabicNormalizer.normalize("بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ"))
    }

    @Test
    fun `folds ta marbuta and alef maqsura`() {
        assertEquals("رحمه", ArabicNormalizer.normalize("رَحْمَة"))
        assertEquals("علي", ArabicNormalizer.normalize("عَلَى"))
    }

    @Test
    fun `quranic and imlaai spellings of the same word agree`() {
        // The mushaf writes a long vowel as waw + superscript alef where modern
        // spelling uses a plain alef; both must reduce to the same token.
        assertEquals(
            ArabicNormalizer.normalize("الصلاة"),
            ArabicNormalizer.normalize("ٱلصَّلَوٰةَ"),
        )
    }

    @Test
    fun `drops punctuation latin text and digits`() {
        assertEquals("قل هو الله احد", ArabicNormalizer.normalize("قُلْ هُوَ ٱللَّهُ أَحَدٌ! (1) ok"))
    }

    @Test
    fun `tokenize splits on whitespace and drops empties`() {
        assertEquals(
            listOf("الحمد", "لله", "رب", "العلمين"),
            ArabicNormalizer.tokenize("  ٱلْحَمْدُ   لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ  "),
        )
    }

    @Test
    fun `a dagger alef yields both the plain and the restored reading`() {
        // Modern spelling restores the alef here …
        assertEquals(
            listOf("العلمين", "العالمين"),
            ArabicNormalizer.normalizeVariants("ٱلْعَـٰلَمِينَ"),
        )
        // … and conventionally omits it here, so both readings are offered.
        assertEquals(
            listOf("الرحمن", "الرحمان"),
            ArabicNormalizer.normalizeVariants("ٱلرَّحْمَـٰنِ"),
        )
    }

    @Test
    fun `a word without a dagger alef has a single reading`() {
        assertEquals(listOf("رب"), ArabicNormalizer.normalizeVariants("رَبِّ"))
    }

    @Test
    fun `display words stay index aligned with comparison words`() {
        val uthmani = "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ"
        val display = ArabicNormalizer.displayWords(uthmani)
        val comparison = ArabicNormalizer.tokenize(uthmani)
        assertEquals(comparison.size, display.size)
        display.forEachIndexed { index, word ->
            assertEquals(comparison[index], ArabicNormalizer.normalize(word))
        }
    }

    @Test
    fun `a standalone waqf mark folds into the previous display word`() {
        // U+06D6 is a waqf sign; on its own it must not become a phantom word.
        val display = ArabicNormalizer.displayWords("قُلْ ۖ هُوَ")
        assertEquals(2, display.size)
        assertTrue(display[0].contains("ۖ"))
    }

    @Test
    fun `empty input is handled`() {
        assertEquals("", ArabicNormalizer.normalize(""))
        assertEquals(emptyList<String>(), ArabicNormalizer.tokenize("،.!"))
    }
}

class WordSimilarityTest {

    @Test
    fun `identical words score one`() {
        assertEquals(1.0, WordSimilarity.of("الحمد", "الحمد"), 0.0001)
    }

    @Test
    fun `unrelated words score low`() {
        assertTrue(WordSimilarity.of("محمد", "العالمين") < 0.4)
    }

    @Test
    fun `a dagger alef word matches its modern spelling through its variants`() {
        val variants = ArabicNormalizer.normalizeVariants("ٱلْعَـٰلَمِينَ")
        assertTrue(WordSimilarity.best("العالمين", variants) > 0.99)
        assertTrue(WordSimilarity.best("العلمين", variants) > 0.99)
    }

    @Test
    fun `best over variants never scores below the primary form`() {
        val variants = ArabicNormalizer.normalizeVariants("ٱلرَّحْمَـٰنِ")
        assertTrue(
            WordSimilarity.best("الرحمن", variants) >=
                WordSimilarity.of("الرحمن", variants.first()),
        )
    }

    @Test
    fun `an asr letter confusion scores higher than a real substitution`() {
        // ه/ح are acoustically close; ه/ب are not.
        val confusable = WordSimilarity.of("رحمن", "رهمن")
        val unrelated = WordSimilarity.of("رحمن", "ربمن")
        assertTrue("$confusable should beat $unrelated", confusable > unrelated)
    }

    @Test
    fun `a short word does not match a long one`() {
        assertTrue(WordSimilarity.of("و", "العالمين") < 0.3)
    }

    @Test
    fun `empty strings are handled`() {
        assertEquals(1.0, WordSimilarity.of("", ""), 0.0001)
        assertEquals(0.0, WordSimilarity.of("", "رب"), 0.0001)
    }
}
