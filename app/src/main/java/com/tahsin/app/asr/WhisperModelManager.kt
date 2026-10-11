package com.tahsin.app.asr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

private const val LOG_TAG = "TahsinModels"

data class InstalledModel(
    val file: File,
    val sizeBytes: Long,
    val displayName: String,
) {
    val fileName: String get() = file.name
}

sealed interface ModelInstallProgress {
    data class Running(val fraction: Float, val bytes: Long, val totalBytes: Long) : ModelInstallProgress
    data class Done(val model: InstalledModel) : ModelInstallProgress
    data class Failed(val message: String) : ModelInstallProgress
}

class ModelInstallException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Owns the on-device Whisper models.
 *
 * The app is offline-first: a model bundled in `assets/models/` is installed on
 * first run, any ggml file can be imported from device storage, and downloading
 * from Hugging Face is only one of three ways to get a model — never a
 * precondition for the app to work.
 */
class WhisperModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun installedModels(): List<InstalledModel> =
        modelsDir.listFiles { file -> file.isFile && file.extension == "bin" }
            ?.sortedBy { it.name }
            ?.map { it.toInstalled() }
            ?: emptyList()

    fun modelFile(fileName: String): File = File(modelsDir, fileName)

    fun isInstalled(fileName: String): Boolean = modelFile(fileName).isUsableModel()

    /**
     * Copies any `.bin` model shipped inside the APK under `assets/models` into app
     * storage. Returns the first model installed, or `null` if none is bundled.
     */
    suspend fun installBundledModels(): InstalledModel? = withContext(Dispatchers.IO) {
        val assets = runCatching { context.assets.list("models") }.getOrNull().orEmpty()
        var first: InstalledModel? = null
        for (name in assets) {
            if (!name.endsWith(".bin")) continue
            val destination = modelFile(name)
            if (!destination.isUsableModel()) {
                runCatching {
                    context.assets.open("models/$name").use { input ->
                        writeAtomically(destination, input, null, -1L)
                    }
                }.onFailure {
                    Log.w(LOG_TAG, "Could not install bundled model $name: ${it.message}")
                }
            }
            if (destination.isUsableModel() && first == null) {
                first = destination.toInstalled()
            }
        }
        first
    }

    /** Copies a user-picked file into app storage, verifying it is a ggml model. */
    suspend fun importFromUri(uri: Uri): InstalledModel = withContext(Dispatchers.IO) {
        val name = displayNameOf(uri) ?: "imported-model.bin"
        val fileName = if (name.endsWith(".bin")) name else "$name.bin"
        val destination = modelFile(fileName)
        val total = sizeOf(uri) ?: -1L
        context.contentResolver.openInputStream(uri)
            ?.use { input -> writeAtomically(destination, input, null, total) }
            ?: throw ModelInstallException("Could not open the selected file")
        destination.toInstalled()
    }

    /**
     * Downloads [model] from the catalog, reporting progress. Resumes are not
     * attempted; a failed download leaves no partial file behind.
     */
    suspend fun download(
        model: CatalogModel,
        onProgress: (ModelInstallProgress.Running) -> Unit = {},
    ): InstalledModel = withContext(Dispatchers.IO) {
        val url = model.downloadUrl
            ?: throw ModelInstallException("${model.displayName} must be imported from storage")
        val destination = modelFile(model.fileName)
        if (destination.isUsableModel()) return@withContext destination.toInstalled()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw ModelInstallException("Download failed with HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: model.approxBytes
            connection.inputStream.use { input ->
                writeAtomically(destination, input, onProgress, total)
            }
        } catch (e: IOException) {
            throw ModelInstallException("Download failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
        destination.toInstalled()
    }

    fun delete(fileName: String): Boolean = modelFile(fileName).delete()

    /**
     * Picks the model to load: the caller's preference when it is installed,
     * otherwise any installed model, otherwise `null`.
     */
    fun resolveActiveModel(preferredFileName: String?): InstalledModel? {
        preferredFileName?.let { preferred ->
            val file = modelFile(preferred)
            if (file.isUsableModel()) return file.toInstalled()
        }
        return installedModels().firstOrNull()
    }

    private suspend fun writeAtomically(
        destination: File,
        input: InputStream,
        onProgress: ((ModelInstallProgress.Running) -> Unit)?,
        total: Long,
    ) {
        val temp = File(destination.parentFile, "${destination.name}.part")
        temp.delete()
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var written = 0L
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    // Reporting every chunk floods the UI; every 2 MB is plenty.
                    if (onProgress != null && written - lastReport >= 2L * 1024 * 1024) {
                        lastReport = written
                        val fraction = if (total > 0) (written.toFloat() / total) else 0f
                        onProgress(
                            ModelInstallProgress.Running(
                                fraction.coerceIn(0f, 1f),
                                written,
                                total,
                            ),
                        )
                    }
                }
                output.flush()
            }
            if (!temp.isUsableModel()) {
                throw ModelInstallException(
                    "That file is not a ggml Whisper model (wrong magic bytes or too small)",
                )
            }
            destination.delete()
            if (!temp.renameTo(destination)) {
                throw ModelInstallException("Could not move the model into place")
            }
            onProgress?.invoke(ModelInstallProgress.Running(1f, temp.length(), total))
        } finally {
            temp.delete()
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun sizeOf(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
        }
    }.getOrNull()

    private fun File.toInstalled(): InstalledModel = InstalledModel(
        file = this,
        sizeBytes = length(),
        displayName = ModelCatalog.byFileName(name)?.displayName ?: name.removeSuffix(".bin"),
    )

    companion object {
        /**
         * Container magics, in the byte order they actually appear on disk.
         *
         * whisper.cpp writes the ggml magic as the little-endian uint32
         * `0x67676d6c`, so the first four bytes of a real model read "lmgg", not
         * "ggml". GGUF stores its magic as characters and does read "GGUF".
         */
        private val GGML_MAGIC_LE = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)
        private val GGML_MAGIC_BE = byteArrayOf(0x67, 0x67, 0x6d, 0x6c)
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
        private val ACCEPTED_MAGICS = listOf(GGML_MAGIC_LE, GGML_MAGIC_BE, GGUF_MAGIC)
        private const val MIN_MODEL_BYTES = 10L * 1024 * 1024

        internal fun File.isUsableModel(): Boolean {
            if (!isFile || length() < MIN_MODEL_BYTES) return false
            return runCatching {
                inputStream().use { stream ->
                    val magic = ByteArray(4)
                    stream.read(magic) == 4 && ACCEPTED_MAGICS.any { magic.contentEquals(it) }
                }
            }.getOrDefault(false)
        }
    }
}
