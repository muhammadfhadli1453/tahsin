package com.tahsin.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.asr.CatalogModel
import com.tahsin.app.asr.InstalledModel
import com.tahsin.app.asr.ModelCatalog
import com.tahsin.app.settings.EngineProfile
import com.tahsin.app.settings.HintLevel
import com.tahsin.app.settings.Sensitivity
import com.tahsin.app.settings.TahsinSettings
import com.tahsin.app.settings.ThemeChoice
import com.tahsin.whisper.WhisperContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelDownloadState(
    val fileName: String,
    val fraction: Float,
    val bytes: Long,
    val totalBytes: Long,
)

data class SettingsUiState(
    val settings: TahsinSettings = TahsinSettings(),
    val installed: List<InstalledModel> = emptyList(),
    val catalog: List<CatalogModel> = ModelCatalog.models,
    val downloading: ModelDownloadState? = null,
    val recordingBytes: Long = 0,
    val message: String? = null,
    val systemInfo: String = "",
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        refreshModels()
        viewModelScope.launch {
            _uiState.update {
                it.copy(systemInfo = runCatching { WhisperContext.systemInfo() }.getOrDefault(""))
            }
        }
    }

    private fun refreshModels() {
        _uiState.update {
            it.copy(
                installed = container.modelManager.installedModels(),
                recordingBytes = container.recordingStore.totalBytes(),
            )
        }
    }

    fun download(model: CatalogModel) {
        if (downloadJob != null) return
        downloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    downloading = ModelDownloadState(model.fileName, 0f, 0, model.approxBytes),
                    message = null,
                )
            }
            try {
                val installed = container.modelManager.download(model) { progress ->
                    _uiState.update {
                        it.copy(
                            downloading = ModelDownloadState(
                                fileName = model.fileName,
                                fraction = progress.fraction,
                                bytes = progress.bytes,
                                totalBytes = progress.totalBytes,
                            ),
                        )
                    }
                }
                container.settingsRepository.setActiveModelFile(installed.fileName)
                _uiState.update { it.copy(message = "${installed.displayName} is ready") }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Download failed") }
            } finally {
                _uiState.update { it.copy(downloading = null) }
                downloadJob = null
                refreshModels()
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { it.copy(downloading = null, message = "Download cancelled") }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            try {
                val installed = container.modelManager.importFromUri(uri)
                container.settingsRepository.setActiveModelFile(installed.fileName)
                _uiState.update { it.copy(message = "Imported ${installed.fileName}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Import failed") }
            } finally {
                refreshModels()
            }
        }
    }

    fun selectModel(fileName: String) {
        viewModelScope.launch { container.settingsRepository.setActiveModelFile(fileName) }
    }

    fun deleteModel(fileName: String) {
        viewModelScope.launch {
            container.modelManager.delete(fileName)
            if (_uiState.value.settings.activeModelFile == fileName) {
                container.settingsRepository.setActiveModelFile(null)
            }
            refreshModels()
        }
    }

    fun clearRecordings() {
        viewModelScope.launch {
            container.recordingStore.enforceCap(0)
            refreshModels()
            _uiState.update { it.copy(message = "Recordings deleted") }
        }
    }

    fun setVibration(enabled: Boolean) = edit { setVibrationEnabled(enabled) }
    fun setSoundAlerts(enabled: Boolean) = edit { setSoundAlertsEnabled(enabled) }
    fun setKeepRecordings(enabled: Boolean) = edit { setKeepRecordings(enabled) }
    fun setAutoAdvance(enabled: Boolean) = edit { setAutoAdvance(enabled) }
    fun setShowTranscript(enabled: Boolean) = edit { setShowTranscript(enabled) }
    fun setTajweed(enabled: Boolean) = edit { setTajweedEnabled(enabled) }
    fun setSensitivity(value: Sensitivity) = edit { setSensitivity(value) }
    fun setHintLevel(value: HintLevel) = edit { setHintLevel(value) }
    fun setEngineProfile(value: EngineProfile) = edit { setEngineProfile(value) }
    fun setTheme(value: ThemeChoice) = edit { setTheme(value) }
    fun setDailyGoal(minutes: Int) = edit { setDailyGoalMinutes(minutes) }
    fun setFontScale(scale: Float) = edit { setArabicFontScale(scale) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun edit(block: suspend com.tahsin.app.settings.SettingsRepository.() -> Unit) {
        viewModelScope.launch { container.settingsRepository.block() }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
