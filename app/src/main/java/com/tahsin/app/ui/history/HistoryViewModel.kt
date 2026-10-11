package com.tahsin.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.data.user.AyahMistakeCount
import com.tahsin.app.data.user.SessionSummary
import com.tahsin.app.data.user.WordMistakeCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DailyMinutes(val date: LocalDate, val minutes: Int)

data class HistoryUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val mostMissedWords: List<WordMistakeCount> = emptyList(),
    val weakestAyahs: List<AyahMistakeCount> = emptyList(),
    val dailyMinutes: List<DailyMinutes> = emptyList(),
    val surahNames: Map<Int, String> = emptyMap(),
) {
    val totalMs: Long get() = sessions.sumOf { it.durationMs }
    val averageAccuracy: Float
        get() = sessions.takeIf { it.isNotEmpty() }?.map { it.accuracy }?.average()?.toFloat() ?: 0f
}

class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.quranRepository.observeSurahs().collect { surahs ->
                _uiState.update { state ->
                    state.copy(surahNames = surahs.associate { it.id to it.nameEn })
                }
            }
        }
        viewModelScope.launch {
            combine(
                container.userRepository.observeRecentSessions(200),
                container.userRepository.observeMostMissedWords(15),
                container.userRepository.observeWeakestAyahs(15),
                container.userRepository.observeRecentDays(60),
            ) { sessions, words, ayahs, days ->
                // Fill the gaps so a quiet day reads as zero rather than vanishing.
                val today = container.userRepository.epochDay()
                val byDay = days.associate { it.epochDay to it.secondsRecited / 60 }
                val window = (13 downTo 0).map { offset ->
                    val epochDay = today - offset
                    DailyMinutes(
                        date = container.userRepository.localDate(epochDay),
                        minutes = byDay[epochDay] ?: 0,
                    )
                }
                Triple(sessions, words to ayahs, window)
            }.collect { (sessions, insights, window) ->
                _uiState.update {
                    it.copy(
                        sessions = sessions,
                        mostMissedWords = insights.first,
                        weakestAyahs = insights.second,
                        dailyMinutes = window,
                    )
                }
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(container) as T
    }
}
