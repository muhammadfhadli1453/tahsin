package com.tahsin.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.data.user.DailyProgressEntity
import com.tahsin.app.data.user.MemorizationEntity
import com.tahsin.app.data.user.SessionSummary
import com.tahsin.app.data.user.StreakInfo
import com.tahsin.app.settings.TahsinSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResumePoint(val surahId: Int, val surahName: String, val ayahNumber: Int)

data class HomeUiState(
    val streak: StreakInfo = StreakInfo(0, 0, false),
    val today: DailyProgressEntity? = null,
    val goalMinutes: Int = 10,
    val resume: ResumePoint? = null,
    val recentSessions: List<SessionSummary> = emptyList(),
    val weakestAyahs: List<MemorizationEntity> = emptyList(),
    val modelInstalled: Boolean = true,
    val settings: TahsinSettings = TahsinSettings(),
) {
    val minutesToday: Int get() = (today?.secondsRecited ?: 0) / 60
    val goalProgress: Float
        get() = if (goalMinutes == 0) 0f else (minutesToday.toFloat() / goalMinutes).coerceIn(0f, 1f)
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.userRepository.observeStreak(),
                container.userRepository.observeToday(),
                container.userRepository.observeLastPosition(),
                container.userRepository.observeRecentSessions(5),
                container.settingsRepository.settings,
            ) { streak, today, position, sessions, settings ->
                val resume = position?.let {
                    ResumePoint(
                        surahId = it.surahId,
                        surahName = container.quranRepository.getSurah(it.surahId)?.nameEn
                            ?: "Surah ${it.surahId}",
                        ayahNumber = it.ayahNumber,
                    )
                }
                HomeUiState(
                    streak = streak,
                    today = today,
                    goalMinutes = settings.dailyGoalMinutes,
                    resume = resume,
                    recentSessions = sessions,
                    modelInstalled = container.modelManager
                        .resolveActiveModel(settings.activeModelFile) != null,
                    settings = settings,
                )
            }.collect { state ->
                _uiState.update { state.copy(weakestAyahs = it.weakestAyahs) }
            }
        }
        viewModelScope.launch {
            container.userRepository.observeWeakestMemorization(5).collect { weakest ->
                _uiState.update { it.copy(weakestAyahs = weakest) }
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(container) as T
    }
}
