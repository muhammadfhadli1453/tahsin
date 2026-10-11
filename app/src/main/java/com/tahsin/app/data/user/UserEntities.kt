package com.tahsin.app.data.user

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "surah_id") val surahId: Int,
    @ColumnInfo(name = "start_ayah") val startAyah: Int,
    @ColumnInfo(name = "end_ayah") val endAyah: Int,
    @ColumnInfo(name = "words_total") val wordsTotal: Int,
    @ColumnInfo(name = "words_correct") val wordsCorrect: Int,
    @ColumnInfo(name = "mistake_count") val mistakeCount: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** Absolute path of the session recording, or `null` if it was not kept. */
    @ColumnInfo(name = "audio_path") val audioPath: String?,
)

@Entity(
    tableName = "session_mistakes",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id"), Index(value = ["surah_id", "ayah_number"])],
)
data class SessionMistakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "surah_id") val surahId: Int,
    @ColumnInfo(name = "ayah_number") val ayahNumber: Int,
    @ColumnInfo(name = "word_index") val wordIndex: Int?,
    @ColumnInfo(name = "expected") val expected: String?,
    @ColumnInfo(name = "heard") val heard: String?,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "confidence") val confidence: Float,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["surah_id", "ayah_number"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "surah_id") val surahId: Int,
    @ColumnInfo(name = "ayah_number") val ayahNumber: Int,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** One row per calendar day, keyed by epoch day in the device's time zone. */
@Entity(tableName = "daily_progress")
data class DailyProgressEntity(
    @PrimaryKey @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "seconds_recited") val secondsRecited: Int,
    @ColumnInfo(name = "ayahs_recited") val ayahsRecited: Int,
    @ColumnInfo(name = "words_correct") val wordsCorrect: Int,
    @ColumnInfo(name = "mistake_count") val mistakeCount: Int,
    @ColumnInfo(name = "session_count") val sessionCount: Int,
)

/**
 * Per-ayah memorisation strength, used to order review and to show what is
 * shaky. [strength] is an exponential moving average of accuracy in `0f..1f`.
 */
@Entity(tableName = "memorization_progress", primaryKeys = ["surah_id", "ayah_number"])
data class MemorizationEntity(
    @ColumnInfo(name = "surah_id") val surahId: Int,
    @ColumnInfo(name = "ayah_number") val ayahNumber: Int,
    @ColumnInfo(name = "strength") val strength: Float,
    @ColumnInfo(name = "review_count") val reviewCount: Int,
    @ColumnInfo(name = "last_reviewed_at") val lastReviewedAt: Long,
)

/** Single-row table holding where the reciter left off. */
@Entity(tableName = "last_position")
data class LastPositionEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "surah_id") val surahId: Int,
    @ColumnInfo(name = "ayah_number") val ayahNumber: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
