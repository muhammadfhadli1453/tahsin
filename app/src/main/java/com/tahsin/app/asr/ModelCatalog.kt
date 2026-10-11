package com.tahsin.app.asr

/**
 * A Whisper model the app knows how to obtain.
 *
 * Downloading is a convenience, not a requirement: any ggml Whisper model can be
 * side-loaded from device storage instead, which is how the app stays usable on
 * a device that never goes online.
 */
data class CatalogModel(
    val id: String,
    val fileName: String,
    val displayName: String,
    val approxBytes: Long,
    val description: String,
    val downloadUrl: String?,
    val recommended: Boolean = false,
)

object ModelCatalog {

    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "tiny-q5_1",
            fileName = "ggml-tiny-q5_1.bin",
            displayName = "Tiny (quantised)",
            approxBytes = 32L * 1024 * 1024,
            description = "Fastest, lowest accuracy. Use on older or low-RAM devices.",
            downloadUrl = "$HF_BASE/ggml-tiny-q5_1.bin",
        ),
        CatalogModel(
            id = "base-q5_1",
            fileName = "ggml-base-q5_1.bin",
            displayName = "Base (quantised)",
            approxBytes = 60L * 1024 * 1024,
            description = "Good balance of speed and accuracy. Recommended default.",
            downloadUrl = "$HF_BASE/ggml-base-q5_1.bin",
            recommended = true,
        ),
        CatalogModel(
            id = "base",
            fileName = "ggml-base.bin",
            displayName = "Base (full precision)",
            approxBytes = 148L * 1024 * 1024,
            description = "Slightly more accurate than the quantised base, and slower.",
            downloadUrl = "$HF_BASE/ggml-base.bin",
        ),
        CatalogModel(
            id = "small-q5_1",
            fileName = "ggml-small-q5_1.bin",
            displayName = "Small (quantised)",
            approxBytes = 190L * 1024 * 1024,
            description = "Best accuracy here, but needs a recent phone to keep up live.",
            downloadUrl = "$HF_BASE/ggml-small-q5_1.bin",
        ),
        CatalogModel(
            id = "quran-finetuned",
            fileName = "ggml-base-ar-quran.bin",
            displayName = "Quran fine-tuned",
            approxBytes = 57L * 1024 * 1024,
            // Built into release builds; developers import their own conversion
            // (scripts/convert_whisper_model.sh) into debug builds.
            description = "Trained on Quran recitation; the most accurate here. Built into the app.",
            downloadUrl = null,
        ),
    )

    fun byFileName(fileName: String): CatalogModel? = models.firstOrNull { it.fileName == fileName }

    val default: CatalogModel = models.first { it.recommended }
}
