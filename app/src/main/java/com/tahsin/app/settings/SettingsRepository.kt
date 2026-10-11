package com.tahsin.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tahsin.app.domain.recitation.EngineTuning
import com.tahsin.app.domain.recitation.TrackerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tahsin_settings")

enum class ThemeChoice { System, Light, Dark }

/** How eagerly a disagreement is reported as a mistake. */
enum class Sensitivity(val label: String, val description: String) {
    Lenient("Lenient", "Only clear, confident mistakes. Fewest false alarms."),
    Balanced("Balanced", "Recommended for most reciters."),
    Strict("Strict", "Flags near-misses too. Expect more false alarms."),
}

/** How much of an unrecited word is shown in memorisation mode. */
enum class HintLevel(val label: String) {
    Hidden("Fully hidden"),
    FirstLetter("First letter"),
    WordShape("Word length only"),
}

/** Trades latency against transcription accuracy. */
enum class EngineProfile(val label: String) {
    Responsive("Responsive"),
    Balanced("Balanced"),
    Accurate("Accurate"),
}

data class TahsinSettings(
    val vibrationEnabled: Boolean = true,
    val soundAlertsEnabled: Boolean = false,
    val keepRecordings: Boolean = true,
    val autoAdvanceAyah: Boolean = true,
    val showTranscript: Boolean = false,
    val tajweedEnabled: Boolean = true,
    val sensitivity: Sensitivity = Sensitivity.Balanced,
    val hintLevel: HintLevel = HintLevel.Hidden,
    val engineProfile: EngineProfile = EngineProfile.Balanced,
    val theme: ThemeChoice = ThemeChoice.System,
    val activeModelFile: String? = null,
    /** Daily goal in minutes of recitation. */
    val dailyGoalMinutes: Int = 10,
    val arabicFontScale: Float = 1.0f,
) {
    fun trackerConfig(): TrackerConfig = when (sensitivity) {
        Sensitivity.Lenient -> TrackerConfig(
            matchThreshold = 0.62,
            settleWords = 4,
            minMistakeConfidence = 0.6f,
            minExtraWordConfidence = 0.85f,
        )
        Sensitivity.Balanced -> TrackerConfig()
        Sensitivity.Strict -> TrackerConfig(
            matchThreshold = 0.82,
            settleWords = 2,
            minMistakeConfidence = 0.3f,
            minExtraWordConfidence = 0.6f,
        )
    }

    fun engineTuning(): EngineTuning = when (engineProfile) {
        EngineProfile.Responsive -> EngineTuning.Responsive
        EngineProfile.Balanced -> EngineTuning.Balanced
        EngineProfile.Accurate -> EngineTuning.Accurate
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val vibration = booleanPreferencesKey("vibration_enabled")
        val soundAlerts = booleanPreferencesKey("sound_alerts_enabled")
        val keepRecordings = booleanPreferencesKey("keep_recordings")
        val autoAdvance = booleanPreferencesKey("auto_advance_ayah")
        val showTranscript = booleanPreferencesKey("show_transcript")
        val tajweed = booleanPreferencesKey("tajweed_enabled")
        val sensitivity = stringPreferencesKey("sensitivity")
        val hintLevel = stringPreferencesKey("hint_level")
        val engineProfile = stringPreferencesKey("engine_profile")
        val theme = stringPreferencesKey("theme")
        val activeModel = stringPreferencesKey("active_model_file")
        val dailyGoal = intPreferencesKey("daily_goal_minutes")
        val fontScale = intPreferencesKey("arabic_font_scale_percent")
    }

    val settings: Flow<TahsinSettings> = context.dataStore.data.map { prefs ->
        TahsinSettings(
            vibrationEnabled = prefs[Keys.vibration] ?: true,
            soundAlertsEnabled = prefs[Keys.soundAlerts] ?: false,
            keepRecordings = prefs[Keys.keepRecordings] ?: true,
            autoAdvanceAyah = prefs[Keys.autoAdvance] ?: true,
            showTranscript = prefs[Keys.showTranscript] ?: false,
            tajweedEnabled = prefs[Keys.tajweed] ?: true,
            sensitivity = prefs[Keys.sensitivity].toEnum(Sensitivity.Balanced),
            hintLevel = prefs[Keys.hintLevel].toEnum(HintLevel.Hidden),
            engineProfile = prefs[Keys.engineProfile].toEnum(EngineProfile.Balanced),
            theme = prefs[Keys.theme].toEnum(ThemeChoice.System),
            activeModelFile = prefs[Keys.activeModel],
            dailyGoalMinutes = prefs[Keys.dailyGoal] ?: 10,
            arabicFontScale = (prefs[Keys.fontScale] ?: 100) / 100f,
        )
    }

    suspend fun setVibrationEnabled(enabled: Boolean) = putBoolean(Keys.vibration, enabled)
    suspend fun setSoundAlertsEnabled(enabled: Boolean) = putBoolean(Keys.soundAlerts, enabled)
    suspend fun setKeepRecordings(enabled: Boolean) = putBoolean(Keys.keepRecordings, enabled)
    suspend fun setAutoAdvance(enabled: Boolean) = putBoolean(Keys.autoAdvance, enabled)
    suspend fun setShowTranscript(enabled: Boolean) = putBoolean(Keys.showTranscript, enabled)
    suspend fun setTajweedEnabled(enabled: Boolean) = putBoolean(Keys.tajweed, enabled)

    suspend fun setSensitivity(value: Sensitivity) = putString(Keys.sensitivity, value.name)
    suspend fun setHintLevel(value: HintLevel) = putString(Keys.hintLevel, value.name)
    suspend fun setEngineProfile(value: EngineProfile) = putString(Keys.engineProfile, value.name)
    suspend fun setTheme(value: ThemeChoice) = putString(Keys.theme, value.name)

    suspend fun setActiveModelFile(fileName: String?) {
        context.dataStore.edit { prefs ->
            if (fileName == null) prefs.remove(Keys.activeModel) else prefs[Keys.activeModel] = fileName
        }
    }

    suspend fun setDailyGoalMinutes(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.dailyGoal] = minutes.coerceIn(1, 300) }
    }

    suspend fun setArabicFontScale(scale: Float) {
        context.dataStore.edit { prefs ->
            prefs[Keys.fontScale] = (scale * 100).toInt().coerceIn(70, 200)
        }
    }

    private suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }

    private suspend fun putString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }
}

private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
