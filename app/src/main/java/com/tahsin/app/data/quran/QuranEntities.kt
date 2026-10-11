package com.tahsin.app.data.quran

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "surahs")
data class SurahEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "name_ar") val nameAr: String,
    @ColumnInfo(name = "name_en") val nameEn: String,
    @ColumnInfo(name = "ayah_count") val ayahCount: Int,
    @ColumnInfo(name = "revelation_type") val revelationType: String,
)

@Entity(
    tableName = "ayahs",
    foreignKeys = [
        ForeignKey(
            entity = SurahEntity::class,
            parentColumns = ["id"],
            childColumns = ["surah_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("surah_id"), Index("juz"), Index("page")],
)
data class AyahEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "surah_id") val surahId: Int,
    @ColumnInfo(name = "ayah_number") val ayahNumber: Int,
    @ColumnInfo(name = "juz") val juz: Int,
    /** Madani mushaf page (1-604), from Tanzil's metadata. */
    @ColumnInfo(name = "page") val page: Int,
    @ColumnInfo(name = "text_uthmani") val textUthmani: String,
    @ColumnInfo(name = "text_normalized") val textNormalized: String,
)
