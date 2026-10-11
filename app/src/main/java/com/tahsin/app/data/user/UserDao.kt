package com.tahsin.app.data.user

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class WordMistakeCount(
    val expected: String,
    val surahId: Int,
    val ayahNumber: Int,
    val occurrences: Int,
)

data class AyahMistakeCount(
    val surahId: Int,
    val ayahNumber: Int,
    val occurrences: Int,
)

@Dao
interface UserDao {

    // ---- Sessions -------------------------------------------------------------

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY started_at DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int = 100): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT audio_path FROM sessions WHERE audio_path IS NOT NULL")
    suspend fun allAudioPaths(): List<String>

    // ---- Mistakes -------------------------------------------------------------

    @Insert
    suspend fun insertMistakes(mistakes: List<SessionMistakeEntity>)

    @Query("SELECT * FROM session_mistakes WHERE session_id = :sessionId ORDER BY at_ms")
    suspend fun getMistakesForSession(sessionId: Long): List<SessionMistakeEntity>

    @Query(
        """
        SELECT expected AS expected, surah_id AS surahId, ayah_number AS ayahNumber,
               COUNT(*) AS occurrences
        FROM session_mistakes
        WHERE expected IS NOT NULL AND kind != 'ExtraWord'
        GROUP BY expected, surah_id, ayah_number
        ORDER BY occurrences DESC
        LIMIT :limit
        """,
    )
    fun observeMostMissedWords(limit: Int = 20): Flow<List<WordMistakeCount>>

    @Query(
        """
        SELECT surah_id AS surahId, ayah_number AS ayahNumber, COUNT(*) AS occurrences
        FROM session_mistakes
        GROUP BY surah_id, ayah_number
        ORDER BY occurrences DESC
        LIMIT :limit
        """,
    )
    fun observeWeakestAyahs(limit: Int = 20): Flow<List<AyahMistakeCount>>

    // ---- Bookmarks ------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE surah_id = :surahId AND ayah_number = :ayahNumber")
    suspend fun deleteBookmark(surahId: Int, ayahNumber: Int)

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM bookmarks WHERE surah_id = :surahId AND ayah_number = :ayahNumber)",
    )
    fun observeIsBookmarked(surahId: Int, ayahNumber: Int): Flow<Boolean>

    // ---- Daily progress -------------------------------------------------------

    @Query("SELECT * FROM daily_progress WHERE epoch_day = :epochDay LIMIT 1")
    suspend fun getDay(epochDay: Long): DailyProgressEntity?

    @Upsert
    suspend fun upsertDay(day: DailyProgressEntity)

    @Query("SELECT * FROM daily_progress ORDER BY epoch_day DESC LIMIT :limit")
    fun observeRecentDays(limit: Int = 120): Flow<List<DailyProgressEntity>>

    @Transaction
    suspend fun addToDay(
        epochDay: Long,
        seconds: Int,
        ayahs: Int,
        wordsCorrect: Int,
        mistakes: Int,
    ) {
        val existing = getDay(epochDay)
        upsertDay(
            DailyProgressEntity(
                epochDay = epochDay,
                secondsRecited = (existing?.secondsRecited ?: 0) + seconds,
                ayahsRecited = (existing?.ayahsRecited ?: 0) + ayahs,
                wordsCorrect = (existing?.wordsCorrect ?: 0) + wordsCorrect,
                mistakeCount = (existing?.mistakeCount ?: 0) + mistakes,
                sessionCount = (existing?.sessionCount ?: 0) + 1,
            ),
        )
    }

    // ---- Memorisation ---------------------------------------------------------

    @Query("SELECT * FROM memorization_progress WHERE surah_id = :surahId AND ayah_number = :ayahNumber LIMIT 1")
    suspend fun getMemorization(surahId: Int, ayahNumber: Int): MemorizationEntity?

    @Upsert
    suspend fun upsertMemorization(entity: MemorizationEntity)

    @Query("SELECT * FROM memorization_progress ORDER BY strength ASC LIMIT :limit")
    fun observeWeakestMemorization(limit: Int = 20): Flow<List<MemorizationEntity>>

    @Query("SELECT * FROM memorization_progress WHERE surah_id = :surahId")
    fun observeMemorizationForSurah(surahId: Int): Flow<List<MemorizationEntity>>

    // ---- Last position --------------------------------------------------------

    @Upsert
    suspend fun upsertLastPosition(position: LastPositionEntity)

    @Query("SELECT * FROM last_position WHERE id = 1 LIMIT 1")
    fun observeLastPosition(): Flow<LastPositionEntity?>
}
