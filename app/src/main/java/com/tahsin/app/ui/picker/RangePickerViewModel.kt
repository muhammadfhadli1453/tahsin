package com.tahsin.app.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.domain.model.Ayah
import com.tahsin.app.domain.model.Surah
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RangePickerUiState(
    val surah: Surah? = null,
    val ayahs: List<Ayah> = emptyList(),
    val startAyah: Int = 1,
    val endAyah: Int = 1,
    val bookmarkedAyahs: Set<Int> = emptySet(),
    val memorizationStrength: Map<Int, Float> = emptyMap(),
    val fontScale: Float = 1f,
    val isLoading: Boolean = true,
) {
    val selectionLabel: String
        get() = if (startAyah == endAyah) {
            "Selected: ayah $startAyah"
        } else {
            "Selected: ayahs $startAyah–$endAyah (${endAyah - startAyah + 1} ayahs)"
        }
}

class RangePickerViewModel(
    private val container: AppContainer,
    private val surahId: Int,
    initialAyah: Int?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RangePickerUiState(
            startAyah = initialAyah ?: 1,
            endAyah = initialAyah ?: 1,
        ),
    )
    val uiState: StateFlow<RangePickerUiState> = _uiState.asStateFlow()

    /**
     * True when the next tap should move the end of the range rather than the
     * start. It begins true because the screen opens with a start already
     * selected, so the reciter's first tap should extend that selection.
     */
    private var awaitingEnd = true

    init {
        viewModelScope.launch {
            val surah = container.quranRepository.getSurah(surahId)
            val ayahs = container.quranRepository.getAyahsForSurah(surahId)
            val settings = container.settingsRepository.settings.first()
            _uiState.update {
                it.copy(
                    surah = surah,
                    ayahs = ayahs,
                    fontScale = settings.arabicFontScale,
                    isLoading = false,
                )
            }
        }
        viewModelScope.launch {
            container.userRepository.observeBookmarks().collect { bookmarks ->
                _uiState.update { state ->
                    state.copy(
                        bookmarkedAyahs = bookmarks
                            .filter { it.surahId == surahId }
                            .map { it.ayahNumber }
                            .toSet(),
                    )
                }
            }
        }
        viewModelScope.launch {
            container.userRepository.observeMemorizationForSurah(surahId).collect { rows ->
                _uiState.update { state ->
                    state.copy(memorizationStrength = rows.associate { it.ayahNumber to it.strength })
                }
            }
        }
    }

    /**
     * First tap sets the start and collapses the range; the next tap extends it.
     * Tapping before the current start restarts the selection there.
     */
    fun onAyahTapped(ayahNumber: Int) {
        _uiState.update { state ->
            if (!awaitingEnd || ayahNumber < state.startAyah) {
                awaitingEnd = true
                state.copy(startAyah = ayahNumber, endAyah = ayahNumber)
            } else {
                awaitingEnd = false
                state.copy(endAyah = ayahNumber)
            }
        }
    }

    fun toggleBookmark(ayahNumber: Int) {
        viewModelScope.launch {
            if (ayahNumber in _uiState.value.bookmarkedAyahs) {
                container.userRepository.removeBookmark(surahId, ayahNumber)
            } else {
                container.userRepository.addBookmark(surahId, ayahNumber)
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val surahId: Int,
        private val initialAyah: Int?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RangePickerViewModel(container, surahId, initialAyah) as T
    }
}
