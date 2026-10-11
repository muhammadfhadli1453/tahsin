package com.tahsin.app.ui.surahlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.domain.model.Surah
import com.tahsin.app.domain.text.ArabicNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SurahListUiState(
    val surahs: List<Surah> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
)

class SurahListViewModel(container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(SurahListUiState())
    val uiState: StateFlow<SurahListUiState> = _uiState.asStateFlow()

    private var allSurahs: List<Surah> = emptyList()

    init {
        viewModelScope.launch {
            container.quranRepository.observeSurahs().collect { surahs ->
                allSurahs = surahs
                _uiState.update {
                    it.copy(surahs = filter(it.query), isLoading = false)
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, surahs = filter(query)) }
    }

    private fun filter(query: String): List<Surah> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return allSurahs
        val arabicNeedle = ArabicNormalizer.normalize(trimmed)
        return allSurahs.filter { surah ->
            surah.nameEn.contains(trimmed, ignoreCase = true) ||
                surah.id.toString() == trimmed ||
                (arabicNeedle.isNotEmpty() &&
                    ArabicNormalizer.normalize(surah.nameAr).contains(arabicNeedle))
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SurahListViewModel(container) as T
    }
}
