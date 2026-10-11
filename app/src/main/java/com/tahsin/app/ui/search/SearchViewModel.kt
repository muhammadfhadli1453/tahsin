package com.tahsin.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.data.quran.SearchHit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,
    val fontScale: Float = 1f,
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(fontScale = container.settingsRepository.settings.first().arabicFontScale)
            }
        }
        viewModelScope.launch {
            // Each keystroke would otherwise scan 6236 rows; settle first.
            queries.debounce(250).distinctUntilChanged().collect { query ->
                if (query.isBlank()) {
                    _uiState.update { it.copy(results = emptyList(), isSearching = false) }
                    return@collect
                }
                _uiState.update { it.copy(isSearching = true) }
                val results = container.quranRepository.search(query)
                _uiState.update { it.copy(results = results, isSearching = false) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        queries.value = query
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(container) as T
    }
}
