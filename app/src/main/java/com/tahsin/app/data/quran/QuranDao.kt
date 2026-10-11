package com.tahsin.app.data.quran

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuranDao {
    @Query("SELECT * FROM surahs ORDER BY id")
    fun observeSurahs(): Flow<List<SurahEntity>>

    @Query("SELECT * FROM surahs WHERE id = :surahId LIMIT 1")
    suspend fun getSurah(surahId: Int): SurahEntity?

    @Query("SELECT * FROM ayahs WHERE surah_id = :surahId ORDER BY ayah_number")
    suspend fun getAyahsForSurah(surahId: Int): List<AyahEntity>

    @Query(
        """
        SELECT * FROM ayahs
        WHERE surah_id = :surahId AND ayah_number BETWEEN :startAyah AND :endAyah
        ORDER BY ayah_number
        """,
    )
    suspend fun getAyahRange(surahId: Int, startAyah: Int, endAyah: Int): List<AyahEntity>

    @Query("SELECT * FROM ayahs WHERE surah_id = :surahId AND ayah_number = :ayahNumber LIMIT 1")
    suspend fun getAyah(surahId: Int, ayahNumber: Int): AyahEntity?

    /**
     * Substring search over the diacritic-free form of every ayah. The caller is
     * responsible for normalising [needle] the same way the column was built.
     */
    @Query(
        """
        SELECT * FROM ayahs
        WHERE text_normalized LIKE '%' || :needle || '%'
        ORDER BY surah_id, ayah_number
        LIMIT :limit
        """,
    )
    suspend fun searchAyahs(needle: String, limit: Int = 100): List<AyahEntity>

    @Query("SELECT COUNT(*) FROM ayahs")
    suspend fun ayahCount(): Int
}
