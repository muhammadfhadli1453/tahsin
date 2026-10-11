package com.tahsin.app.data.quran

import androidx.test.core.app.ApplicationProvider
import com.tahsin.app.domain.text.TajweedAnalyzer
import com.tahsin.app.domain.text.TajweedRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the prepackaged database contract.
 *
 * Room rejects an asset database whose DDL, `user_version` or `room_master_table`
 * identity hash disagrees with the generated code — which is exactly how the app
 * used to crash on first launch. Regenerating `quran.db` is a manual step
 * (`scripts/import_quran.py`), so this test is what catches a forgotten rebuild.
 */
@RunWith(RobolectricTestRunner::class)
class QuranDatabaseAssetTest {

    private lateinit var database: QuranDatabase

    @Before
    fun setUp() {
        database = QuranDatabase.create(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the prepackaged database opens and is complete`() = runTest {
        val dao = database.quranDao()
        assertEquals(6236, dao.ayahCount())
        assertNotNull(dao.getSurah(1))
        assertEquals(114, dao.getSurah(114)?.id)
    }

    @Test
    fun `surah metadata is populated`() = runTest {
        val fatihah = database.quranDao().getSurah(1)
        assertEquals("Al-Fatihah", fatihah?.nameEn)
        assertEquals(7, fatihah?.ayahCount)
        assertEquals("Meccan", fatihah?.revelationType)
    }

    @Test
    fun `juz boundaries land where they should`() = runTest {
        val dao = database.quranDao()
        assertEquals(1, dao.getAyah(2, 141)?.juz)
        assertEquals(2, dao.getAyah(2, 142)?.juz)
        assertEquals(30, dao.getAyah(78, 1)?.juz)
        assertEquals(30, dao.getAyah(114, 6)?.juz)
    }

    @Test
    fun `madani page boundaries land where they should`() = runTest {
        val dao = database.quranDao()
        assertEquals(1, dao.getAyah(1, 1)?.page)
        assertEquals(2, dao.getAyah(2, 5)?.page)
        assertEquals(3, dao.getAyah(2, 6)?.page)
        assertEquals(604, dao.getAyah(112, 1)?.page)
        assertEquals(604, dao.getAyah(114, 6)?.page)
    }

    @Test
    fun `an ayah range comes back in order`() = runTest {
        val range = database.quranDao().getAyahRange(112, 1, 4)
        assertEquals(listOf(1, 2, 3, 4), range.map { it.ayahNumber })
    }

    @Test
    fun `search ignores diacritics`() = runTest {
        val repository = QuranRepository(database.quranDao())
        val hits = repository.search("ٱلرَّحْمَٰن")
        assertTrue("expected matches for al-Rahman", hits.isNotEmpty())
        assertTrue(hits.all { it.surahNameEn.isNotEmpty() })
    }

    @Test
    fun `tajweed analysis over the whole corpus is well formed and plausible`() = runTest {
        val dao = database.quranDao()
        val counts = mutableMapOf<TajweedRule, Int>()
        var ayahsWithRules = 0

        for (surahId in 1..114) {
            for (ayah in dao.getAyahsForSurah(surahId)) {
                val spans = TajweedAnalyzer.analyze(ayah.textUthmani)
                var previousEnd = 0
                for (span in spans) {
                    assertTrue(
                        "overlapping span in $surahId:${ayah.ayahNumber}",
                        span.start >= previousEnd,
                    )
                    assertTrue(
                        "span outside the text in $surahId:${ayah.ayahNumber}",
                        span.start >= 0 && span.end <= ayah.textUthmani.length,
                    )
                    assertTrue(span.start < span.end)
                    previousEnd = span.end
                    counts[span.rule] = (counts[span.rule] ?: 0) + 1
                }
                if (spans.isNotEmpty()) ayahsWithRules++
            }
        }

        // Most ayahs contain at least one of these rules; a sharp drop here means
        // the analyzer stopped recognising the text's conventions.
        assertTrue("only $ayahsWithRules ayahs had any rule", ayahsWithRules > 5_000)

        // Every rule the analyzer can emit should actually occur in the Quran.
        for (rule in TajweedRule.entries) {
            assertTrue("$rule never fired across the corpus", (counts[rule] ?: 0) > 0)
        }
    }

    @Test
    fun `iqlab and silent counts match the marks in the text`() = runTest {
        val dao = database.quranDao()
        var iqlabMarks = 0
        var silentZeros = 0
        var iqlabSpans = 0
        var silentSpans = 0

        for (surahId in 1..114) {
            for (ayah in dao.getAyahsForSurah(surahId)) {
                val text = ayah.textUthmani
                // Iqlab is marked above the letter after a fatha or damma tanween
                // and below it after a kasratan, so both characters count.
                iqlabMarks += text.count { it == '\u06E2' || it == '\u06ED' }
                silentZeros += text.count { it == '\u06DF' || it == '\u06E0' }
                for (span in TajweedAnalyzer.analyze(text)) {
                    when (span.rule) {
                        TajweedRule.Iqlab -> iqlabSpans++
                        TajweedRule.Silent -> silentSpans++
                        else -> Unit
                    }
                }
            }
        }

        // The mushaf writes exactly one meem per iqlab, so the analyzer should find
        // exactly that many — no more (over-firing) and no fewer (missed).
        assertEquals(iqlabMarks, iqlabSpans)
        assertEquals(silentZeros, silentSpans)
    }

    @Test
    fun `expected words are derived from the mushaf text and stay aligned`() = runTest {
        val repository = QuranRepository(database.quranDao())
        val words = repository.buildExpectedWords(
            com.tahsin.app.domain.model.AyahRange(1, 1, 7),
        )
        assertTrue(words.isNotEmpty())
        // Positions must be contiguous across ayah boundaries for the tracker.
        words.forEachIndexed { index, word -> assertEquals(index, word.position) }
        assertTrue(words.all { it.normalized.isNotEmpty() })
        assertEquals(1, words.first().ayahNumber)
        assertEquals(7, words.last().ayahNumber)
    }
}
