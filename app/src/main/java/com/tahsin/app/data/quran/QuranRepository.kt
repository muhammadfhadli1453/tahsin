package com.tahsin.app.data.quran

import com.tahsin.app.domain.model.Ayah
import com.tahsin.app.domain.model.AyahRange
import com.tahsin.app.domain.model.AyahWord
import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.model.Surah
import com.tahsin.app.domain.text.ArabicNormalizer
import com.tahsin.app.domain.text.TajweedAnalyzer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SearchHit(
    val ayah: Ayah,
    val surahNameEn: String,
    val surahNameAr: String,
)

class QuranRepository(private val dao: QuranDao) {

    fun observeSurahs(): Flow<List<Surah>> =
        dao.observeSurahs().map { rows -> rows.map { it.toDomain() } }

    suspend fun getSurah(surahId: Int): Surah? = dao.getSurah(surahId)?.toDomain()

    suspend fun getAyahsForSurah(surahId: Int): List<Ayah> =
        dao.getAyahsForSurah(surahId).map { it.toDomain() }

    suspend fun getAyah(surahId: Int, ayahNumber: Int): Ayah? =
        dao.getAyah(surahId, ayahNumber)?.toDomain()

    suspend fun getAyahRange(range: AyahRange): List<Ayah> =
        dao.getAyahRange(range.surahId, range.startAyah, range.endAyah).map { it.toDomain() }

    /**
     * Flattens an ayah range into the word sequence a session is checked against.
     * Positions are contiguous across ayah boundaries, which is what lets a
     * session run continuously without restarting the tracker per ayah.
     */
    suspend fun buildExpectedWords(range: AyahRange): List<ExpectedWord> {
        val ayahs = getAyahRange(range)
        val words = mutableListOf<ExpectedWord>()
        for (ayah in ayahs) {
            for (word in ayah.words) {
                words += ExpectedWord(
                    position = words.size,
                    surahId = ayah.surahId,
                    ayahNumber = ayah.ayahNumber,
                    wordIndex = word.index,
                    uthmani = word.uthmani,
                    normalized = word.normalized,
                    variants = word.variants,
                    tajweed = word.tajweed,
                )
            }
        }
        return words
    }

    suspend fun search(query: String, limit: Int = 60): List<SearchHit> {
        val needle = ArabicNormalizer.normalize(query)
        if (needle.length < 2) return emptyList()
        val hits = dao.searchAyahs(needle, limit)
        // Surah names come from a 114-row table, so caching per query is enough.
        val surahNames = HashMap<Int, SurahEntity?>()
        return hits.map { row ->
            val surah = surahNames.getOrPut(row.surahId) { dao.getSurah(row.surahId) }
            SearchHit(
                ayah = row.toDomain(),
                surahNameEn = surah?.nameEn ?: "Surah ${row.surahId}",
                surahNameAr = surah?.nameAr ?: "",
            )
        }
    }

    suspend fun isReady(): Boolean = runCatching { dao.ayahCount() > 6000 }.getOrDefault(false)

    private fun SurahEntity.toDomain() = Surah(id, nameAr, nameEn, ayahCount, revelationType)

    private fun AyahEntity.toDomain(): Ayah {
        // Tajweed rules cross word boundaries (a noon at the end of one word is
        // governed by the first letter of the next), so they are derived from the
        // whole ayah and then sliced back onto each word.
        val ayahTajweed = TajweedAnalyzer.analyze(textUthmani)
        val words = ArabicNormalizer.displayWordsWithOffsets(textUthmani)
            .map { word -> word to ArabicNormalizer.normalizeVariants(word.text) }
            .filter { (_, variants) -> variants.first().isNotEmpty() }
            .mapIndexed { index, (word, variants) ->
                AyahWord(
                    index = index,
                    uthmani = word.text,
                    normalized = variants.first(),
                    variants = variants,
                    tajweed = ayahTajweed
                        .filter { it.start >= word.start && it.end <= word.end }
                        .map { it.copy(start = it.start - word.start, end = it.end - word.start) },
                )
            }
        return Ayah(
            id = id,
            surahId = surahId,
            ayahNumber = ayahNumber,
            juz = juz,
            page = page,
            textUthmani = textUthmani,
            textNormalized = textNormalized,
            words = words,
        )
    }
}
