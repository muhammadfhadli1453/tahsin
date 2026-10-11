package com.tahsin.app.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Owns session recordings on disk.
 *
 * Recordings are the one thing here that can grow without bound, so the store
 * both prunes files no session references any more and enforces a size cap.
 */
class RecordingStore(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newRecordingFile(sessionStartedAt: Long): File =
        File(directory, "session-$sessionStartedAt.wav")

    fun all(): List<File> = directory.listFiles { f -> f.isFile && f.extension == "wav" }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    fun totalBytes(): Long = all().sumOf { it.length() }

    fun delete(path: String): Boolean = File(path).takeIf { it.parentFile == directory }?.delete() ?: false

    /**
     * Repairs recordings whose header under-reports their audio — the signature
     * of a session that ended without the writer closing cleanly. Returns the
     * number of files repaired.
     */
    fun repairTruncatedHeaders(sampleRate: Int = 16_000): Int =
        all().count { WavFileWriter.repairIfTruncated(it, sampleRate) }

    /** Removes recordings that no session row points at. */
    fun deleteOrphans(referenced: Set<String>) {
        all().forEach { file ->
            if (file.absolutePath !in referenced) file.delete()
        }
    }

    /** Deletes oldest recordings until the directory is under [maxBytes]. */
    fun enforceCap(maxBytes: Long = DEFAULT_CAP_BYTES): List<String> {
        val removed = mutableListOf<String>()
        val files = all().sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (total > maxBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            if (oldest.delete()) removed += oldest.absolutePath
        }
        return removed
    }

    /** Duration of a recording, or 0 if it cannot be read. */
    fun durationMs(path: String): Long {
        // MediaMetadataRetriever only became AutoCloseable in API 29, and minSdk is 26.
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val DEFAULT_CAP_BYTES = 600L * 1024 * 1024
    }
}
