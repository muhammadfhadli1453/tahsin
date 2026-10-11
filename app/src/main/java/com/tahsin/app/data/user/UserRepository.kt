package com.tahsin.app.data.user

import com.tahsin.app.domain.model.AyahRange
import com.tahsin.app.domain.model.Mistake
import com.tahsin.app.domain.model.MistakeKind
import com.tahsin.app.domain.model.SessionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SessionSummary(
    val id: Long,
    val startedAt: Long,
    val mode: SessionMode,
    val range: AyahRange,
    val wordsTotal: Int,
    val wordsCorrect: Int,
    val mistakeCount: Int,
    val durationMs: Long,
    val audioPath: String?,
) {
    val accuracy: Float
        get() = if (wordsTotal == 0) 0f else wordsCorrect.toFloat() / wordsTotal
}

data class StreakInfo(
    val currentStreak: Int,
    val longestStreak: Int,
    val activeToday: Boolean,
)

class UserRepository(
    private val dao: UserDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ---- Sessions -------------------------------------------------------------

    suspend fun startSession(mode: SessionMode, range: AyahRange, wordsTotal: Int): Long =
        dao.insertSession(
            SessionEntity(
                startedAt = clock(),
                endedAt = null,
                mode = mode.name,
                surahId = range.surahId,
                startAyah = range.startAyah,
                endAyah = range.endAyah,
                wordsTotal = wordsTotal,
                wordsCorrect = 0,
                mistakeCount = 0,
                durationMs = 0,
                audioPath = null,
            ),
        )

    /**
     * Closes out a session: persists its counters and mistakes, rolls the day's
     * totals forward, and updates memorisation strength for each ayah touched.
     */
    suspend fun finishSession(
        sessionId: Long,
        wordsCorrect: Int,
        mistakes: List<Mistake>,
        durationMs: Long,
        audioPath: String?,
        ayahsCompleted: Int,
    ) {
        val existing = dao.getSession(sessionId) ?: return
        val now = clock()
        dao.upsertSession(
            existing.copy(
                endedAt = now,
                wordsCorrect = wordsCorrect,
                mistakeCount = mistakes.size,
                durationMs = durationMs,
                audioPath = audioPath,
            ),
        )
        if (mistakes.isNotEmpty()) {
            dao.insertMistakes(
                mistakes.map { mistake ->
                    SessionMistakeEntity(
                        sessionId = sessionId,
                        kind = mistake.kind.name,
                        surahId = mistake.surahId,
                        ayahNumber = mistake.ayahNumber,
                        wordIndex = mistake.wordIndex,
                        expected = mistake.expected,
                        heard = mistake.heard,
                        atMs = mistake.atMs,
                        confidence = mistake.confidence,
                    )
                },
            )
        }
        dao.addToDay(
            epochDay = epochDay(now),
            seconds = (durationMs / 1000).toInt(),
            ayahs = ayahsCompleted,
            wordsCorrect = wordsCorrect,
            mistakes = mistakes.size,
        )
        updateMemorization(existing, mistakes, now)
    }

    private suspend fun updateMemorization(
        session: SessionEntity,
        mistakes: List<Mistake>,
        now: Long,
    ) {
        val mistakesByAyah = mistakes
            .filter { it.kind != MistakeKind.ExtraWord }
            .groupingBy { it.ayahNumber }
            .eachCount()

        for (ayahNumber in session.startAyah..session.endAyah) {
            val wrong = mistakesByAyah[ayahNumber] ?: 0
            // Ayah length is not known here, so score against a nominal 10 words;
            // the moving average washes out the approximation over repeat reviews.
            val observed = (1f - wrong / 10f).coerceIn(0f, 1f)
            val previous = dao.getMemorization(session.surahId, ayahNumber)
            val strength = if (previous == null) {
                observed
            } else {
                previous.strength * (1f - EMA_ALPHA) + observed * EMA_ALPHA
            }
            dao.upsertMemorization(
                MemorizationEntity(
                    surahId = session.surahId,
                    ayahNumber = ayahNumber,
                    strength = strength,
                    reviewCount = (previous?.reviewCount ?: 0) + 1,
                    lastReviewedAt = now,
                ),
            )
        }
    }

    suspend fun deleteSession(id: Long) = dao.deleteSession(id)

    suspend fun getMistakes(sessionId: Long): List<SessionMistakeEntity> =
        dao.getMistakesForSession(sessionId)

    suspend fun getSession(id: Long): SessionSummary? = dao.getSession(id)?.toSummary()

    fun observeRecentSessions(limit: Int = 100): Flow<List<SessionSummary>> =
        dao.observeRecentSessions(limit).map { rows -> rows.map { it.toSummary() } }

    suspend fun knownAudioPaths(): Set<String> = dao.allAudioPaths().toSet()

    // ---- Insights -------------------------------------------------------------

    fun observeMostMissedWords(limit: Int = 20): Flow<List<WordMistakeCount>> =
        dao.observeMostMissedWords(limit)

    fun observeWeakestAyahs(limit: Int = 20): Flow<List<AyahMistakeCount>> =
        dao.observeWeakestAyahs(limit)

    fun observeRecentDays(limit: Int = 120): Flow<List<DailyProgressEntity>> =
        dao.observeRecentDays(limit)

    fun observeStreak(): Flow<StreakInfo> = dao.observeRecentDays(365).map { days ->
        computeStreak(days.map { it.epochDay }.toSortedSet().toList(), epochDay(clock()))
    }

    fun observeToday(): Flow<DailyProgressEntity?> = dao.observeRecentDays(1).map { days ->
        days.firstOrNull()?.takeIf { it.epochDay == epochDay(clock()) }
    }

    // ---- Bookmarks & position -------------------------------------------------

    fun observeBookmarks(): Flow<List<BookmarkEntity>> = dao.observeBookmarks()

    fun observeIsBookmarked(surahId: Int, ayahNumber: Int): Flow<Boolean> =
        dao.observeIsBookmarked(surahId, ayahNumber)

    suspend fun addBookmark(surahId: Int, ayahNumber: Int, note: String? = null) =
        dao.insertBookmark(
            BookmarkEntity(
                surahId = surahId,
                ayahNumber = ayahNumber,
                note = note,
                createdAt = clock(),
            ),
        )

    suspend fun removeBookmark(surahId: Int, ayahNumber: Int) =
        dao.deleteBookmark(surahId, ayahNumber)

    fun observeLastPosition(): Flow<LastPositionEntity?> = dao.observeLastPosition()

    suspend fun setLastPosition(surahId: Int, ayahNumber: Int) = dao.upsertLastPosition(
        LastPositionEntity(surahId = surahId, ayahNumber = ayahNumber, updatedAt = clock()),
    )

    fun observeWeakestMemorization(limit: Int = 20): Flow<List<MemorizationEntity>> =
        dao.observeWeakestMemorization(limit)

    fun observeMemorizationForSurah(surahId: Int): Flow<List<MemorizationEntity>> =
        dao.observeMemorizationForSurah(surahId)

    fun epochDay(atMillis: Long = clock()): Long =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().toEpochDay()

    fun localDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    private fun SessionEntity.toSummary() = SessionSummary(
        id = id,
        startedAt = startedAt,
        mode = runCatching { SessionMode.valueOf(mode) }.getOrDefault(SessionMode.Recite),
        range = AyahRange(surahId, startAyah, endAyah),
        wordsTotal = wordsTotal,
        wordsCorrect = wordsCorrect,
        mistakeCount = mistakeCount,
        durationMs = durationMs,
        audioPath = audioPath,
    )

    companion object {
        private const val EMA_ALPHA = 0.4f

        /**
         * Current streak counts back from today, or from yesterday if today has no
         * activity yet, so an untouched morning does not read as a broken streak.
         */
        fun computeStreak(activeDaysAscending: List<Long>, today: Long): StreakInfo {
            if (activeDaysAscending.isEmpty()) return StreakInfo(0, 0, false)
            val active = activeDaysAscending.toHashSet()
            val activeToday = today in active

            var current = 0
            var day = if (activeToday) today else today - 1
            while (day in active) {
                current++
                day--
            }

            var longest = 0
            var run = 0
            var previous: Long? = null
            for (value in activeDaysAscending) {
                run = if (previous != null && value == previous + 1) run + 1 else 1
                longest = maxOf(longest, run)
                previous = value
            }
            return StreakInfo(current, maxOf(longest, current), activeToday)
        }
    }
}
