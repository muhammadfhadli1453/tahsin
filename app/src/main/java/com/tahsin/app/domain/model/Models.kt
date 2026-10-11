package com.tahsin.app.domain.model

import com.tahsin.app.domain.text.TajweedSpan

data class Surah(
    val id: Int,
    val nameAr: String,
    val nameEn: String,
    val ayahCount: Int,
    val revelationType: String,
)

data class Ayah(
    val id: Long,
    val surahId: Int,
    val ayahNumber: Int,
    val juz: Int,
    /** Madani mushaf page, 1-604. */
    val page: Int,
    val textUthmani: String,
    val textNormalized: String,
    val words: List<AyahWord>,
)

data class AyahWord(
    val index: Int,
    val uthmani: String,
    val normalized: String,
    /** Accepted comparison forms; [normalized] is always the first entry. */
    val variants: List<String> = listOf(normalized),
    /** Tajweed spans with offsets relative to [uthmani]. */
    val tajweed: List<TajweedSpan> = emptyList(),
)

/** One expected word in a session, addressed by its position in the whole session. */
data class ExpectedWord(
    val position: Int,
    val surahId: Int,
    val ayahNumber: Int,
    val wordIndex: Int,
    val uthmani: String,
    val normalized: String,
    /** Accepted comparison forms; [normalized] is always the first entry. */
    val variants: List<String> = listOf(normalized),
    /** Tajweed spans with offsets relative to [uthmani]. */
    val tajweed: List<TajweedSpan> = emptyList(),
)

enum class WordStatus {
    /** Not reached yet. */
    Pending,

    /** The word the reciter is expected to say next. */
    Current,

    Correct,

    /** Something was heard here but it did not match. */
    Wrong,

    /** The reciter moved past this word without saying it. */
    Missed,

    /**
     * Something was heard here, but too faintly or ambiguously to say whether it
     * was right. Shown neutrally: neither confirmed nor counted as a mistake.
     */
    Unclear,
}

enum class MistakeKind {
    WrongWord,
    MissedWord,
    ExtraWord,

    /** The right word, voiced wrongly where the mushaf marks a special reading (gharib). */
    Pronunciation,
}

data class Mistake(
    val kind: MistakeKind,
    val surahId: Int,
    val ayahNumber: Int,
    /** Word index inside the ayah, or `null` for an extra word. */
    val wordIndex: Int?,
    val expected: String?,
    val heard: String?,
    /** Offset from the start of the session, in milliseconds. */
    val atMs: Long,
    val confidence: Float,
    /** How to read it correctly, for a [MistakeKind.Pronunciation]. */
    val note: String? = null,
)

enum class SessionMode {
    /** Follow along with the mushaf visible. */
    Recite,

    /** Words stay hidden until recited correctly. */
    Memorize,

    /** Long continuous session across many ayahs, minimal interruption. */
    Qiyam,
}

data class AyahRange(
    val surahId: Int,
    val startAyah: Int,
    val endAyah: Int,
) {
    val ayahCount: Int get() = endAyah - startAyah + 1
}
