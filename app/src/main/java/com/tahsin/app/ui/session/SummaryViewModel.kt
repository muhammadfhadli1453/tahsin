package com.tahsin.app.ui.session

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.data.user.SessionMistakeEntity
import com.tahsin.app.data.user.SessionSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SummaryUiState(
    val summary: SessionSummary? = null,
    val surahName: String = "",
    val mistakes: List<SessionMistakeEntity> = emptyList(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val message: String = "",
)

class SummaryViewModel(
    private val container: AppContainer,
    private val sessionId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SummaryUiState())
    val uiState: StateFlow<SummaryUiState> = _uiState.asStateFlow()

    private var player: MediaPlayer? = null
    private var tickJob: Job? = null

    init {
        viewModelScope.launch {
            val summary = container.userRepository.getSession(sessionId)
            if (summary == null) {
                _uiState.update { it.copy(message = "That session is no longer available") }
                return@launch
            }
            val surah = container.quranRepository.getSurah(summary.range.surahId)
            _uiState.update {
                it.copy(
                    summary = summary,
                    surahName = surah?.nameEn ?: "Surah ${summary.range.surahId}",
                    mistakes = container.userRepository.getMistakes(sessionId),
                    // A WAV whose header under-reports its audio reads as zero
                    // here, so never let that become the displayed length.
                    durationMs = summary.audioPath
                        ?.let { path -> container.recordingStore.durationMs(path) }
                        ?.takeIf { it > 0 }
                        ?: summary.durationMs,
                )
            }
        }
    }

    fun togglePlayback() {
        val path = _uiState.value.summary?.audioPath ?: return
        val existing = player
        if (existing != null) {
            if (existing.isPlaying) {
                existing.pause()
                _uiState.update { it.copy(isPlaying = false) }
            } else {
                existing.start()
                _uiState.update { it.copy(isPlaying = true) }
                startTicking()
            }
            return
        }
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    _uiState.update { it.copy(isPlaying = false, positionMs = 0) }
                    seekTo(0)
                }
                start()
            }.onFailure {
                _uiState.update { state -> state.copy(message = "Could not play the recording") }
                release()
                player = null
                return
            }
        }
        _uiState.update { state ->
            val reported = player?.duration?.toLong()?.takeIf { it > 0 }
            state.copy(isPlaying = true, durationMs = reported ?: state.durationMs)
        }
        startTicking()
    }

    /** Jumps playback to a mistake's offset, starting playback if it was idle. */
    fun seekTo(atMs: Long) {
        if (player == null) togglePlayback()
        player?.seekTo(atMs.toInt().coerceAtLeast(0))
        _uiState.update { it.copy(positionMs = atMs) }
    }

    fun stopPlayback() {
        tickJob?.cancel()
        tickJob = null
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            stopPlayback()
            _uiState.value.summary?.audioPath?.let { container.recordingStore.delete(it) }
            container.userRepository.deleteSession(sessionId)
            onDeleted()
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (player?.isPlaying == true) {
                _uiState.update {
                    it.copy(positionMs = player?.currentPosition?.toLong() ?: it.positionMs)
                }
                delay(200)
            }
        }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }

    class Factory(
        private val container: AppContainer,
        private val sessionId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SummaryViewModel(container, sessionId) as T
    }
}
