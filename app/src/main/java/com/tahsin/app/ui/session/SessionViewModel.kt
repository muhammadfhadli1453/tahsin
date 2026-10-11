package com.tahsin.app.ui.session

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tahsin.app.AppContainer
import com.tahsin.app.audio.MicrophoneRecorder
import com.tahsin.app.audio.MicrophoneUnavailableException
import com.tahsin.app.domain.model.Ayah
import com.tahsin.app.domain.model.AyahRange
import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.model.Mistake
import com.tahsin.app.domain.model.SessionMode
import com.tahsin.app.domain.model.Surah
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.domain.recitation.RecitationEngine
import com.tahsin.app.domain.recitation.RecitationTracker
import com.tahsin.app.domain.recitation.TimedTranscriber
import com.tahsin.app.domain.recitation.TimedTranscriberSource
import com.tahsin.app.settings.TahsinSettings
import com.tahsin.whisper.WhisperContext
import com.tahsin.whisper.WhisperModelException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "TahsinSession"

enum class SessionPhase {
    Loading,

    /** No usable Whisper model is installed. */
    NeedsModel,

    Ready,
    Listening,
    Paused,
    Finished,
    Error,
}

data class SessionUiState(
    val phase: SessionPhase = SessionPhase.Loading,
    val mode: SessionMode = SessionMode.Recite,
    val surah: Surah? = null,
    val ayahs: List<Ayah> = emptyList(),
    val expected: List<ExpectedWord> = emptyList(),
    val statuses: List<WordStatus> = emptyList(),
    val cursor: Int = 0,
    val mistakes: List<Mistake> = emptyList(),
    val latestMistake: Mistake? = null,
    val micLevel: Float = 0f,
    val isSpeaking: Boolean = false,
    val elapsedMs: Long = 0,
    val stuckWord: ExpectedWord? = null,
    val revealedPositions: Set<Int> = emptySet(),
    val lastTranscript: String = "",
    val lastConfidence: Float = 0f,
    val message: String = "",
    val modelName: String = "",
    val settings: TahsinSettings = TahsinSettings(),
    val finishedSessionId: Long? = null,
) {
    val wordsTotal: Int get() = expected.size
    val wordsCorrect: Int get() = statuses.count { it == WordStatus.Correct }
    val accuracy: Float get() = if (wordsTotal == 0) 0f else wordsCorrect.toFloat() / wordsTotal

    /** Ayah the cursor currently sits in, used to scroll and to auto-advance. */
    val currentAyahNumber: Int?
        get() = expected.getOrNull(cursor.coerceAtMost(expected.lastIndex.coerceAtLeast(0)))?.ayahNumber

    val isActive: Boolean
        get() = phase == SessionPhase.Listening || phase == SessionPhase.Paused
}

class SessionViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val mode: SessionMode,
    private val range: AyahRange,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState(mode = mode))
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var whisper: WhisperContext? = null
    private var engine: RecitationEngine? = null
    private var tracker: RecitationTracker? = null
    private var runJob: Job? = null
    private var sessionRowId: Long? = null
    private var recordingFile: File? = null
    private var sessionStartedAt = 0L
    private var finished = false
    private var recorder: MicrophoneRecorder? = null

    /** Milliseconds of audio actually captured, or 0 before a session starts. */
    private fun recordedMs(): Long = recorder?.recordedMs ?: 0L

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val settings = container.settingsRepository.settings.first()
        _uiState.update { it.copy(settings = settings) }

        val surah = container.quranRepository.getSurah(range.surahId)
        val ayahs = container.quranRepository.getAyahRange(range)
        if (surah == null || ayahs.isEmpty()) {
            _uiState.update {
                it.copy(phase = SessionPhase.Error, message = "That passage could not be loaded")
            }
            return
        }
        val expected = container.quranRepository.buildExpectedWords(range)
        tracker = RecitationTracker(expected, settings.trackerConfig())

        _uiState.update {
            it.copy(
                surah = surah,
                ayahs = ayahs,
                expected = expected,
                statuses = tracker?.state()?.statuses ?: emptyList(),
                message = "",
            )
        }

        val model = container.modelManager.resolveActiveModel(settings.activeModelFile)
        if (model == null) {
            _uiState.update {
                it.copy(
                    phase = SessionPhase.NeedsModel,
                    message = "No speech model installed yet. Add one in Settings — " +
                        "download it once, or import a .bin file from storage.",
                )
            }
            return
        }

        _uiState.update { it.copy(phase = SessionPhase.Loading, message = "Loading ${model.displayName}…") }
        try {
            whisper = WhisperContext.createFromFile(model.file.absolutePath)
            _uiState.update {
                it.copy(
                    phase = SessionPhase.Ready,
                    modelName = model.displayName,
                    message = readyMessage(),
                )
            }
        } catch (e: WhisperModelException) {
            Log.w(LOG_TAG, "Model load failed", e)
            _uiState.update {
                it.copy(
                    phase = SessionPhase.NeedsModel,
                    message = e.message ?: "The speech model could not be loaded",
                )
            }
        }
    }

    private fun readyMessage(): String = when (mode) {
        SessionMode.Recite -> "Tap the mic and begin reciting"
        SessionMode.Memorize -> "Words stay hidden until you recite them"
        SessionMode.Qiyam -> "Continuous mode — recite as long as you like"
    }

    fun start() {
        val whisperContext = whisper ?: return
        val activeTracker = tracker ?: return
        if (runJob != null) return

        val settings = _uiState.value.settings
        sessionStartedAt = System.currentTimeMillis()
        recordingFile = if (settings.keepRecordings) {
            container.recordingStore.newRecordingFile(sessionStartedAt)
        } else {
            null
        }

        val sessionRecorder = MicrophoneRecorder()
        recorder = sessionRecorder
        val recitationEngine = RecitationEngine(
            whisper = whisperContext::transcribe,
            recorder = sessionRecorder,
            tracker = activeTracker,
            tuning = settings.engineTuning(),
            // Gharib words are checked by ear; that needs a second copy of the
            // model with word timings, opened only around those words.
            timedTranscribers = TimedTranscriberSource {
                whisperContext.openWithWordTimings()?.let { timed ->
                    TimedTranscriber(transcriber = timed::transcribe, close = timed::release)
                }
            },
        )
        engine = recitationEngine

        runJob = viewModelScope.launch {
            sessionRowId = container.userRepository.startSession(
                mode = mode,
                range = range,
                wordsTotal = _uiState.value.expected.size,
            )
            _uiState.update { it.copy(phase = SessionPhase.Listening, message = "Listening…") }
            try {
                recitationEngine.run(recordingFile) { snapshot ->
                    // A later, better window can clear a word judged too early, so
                    // the list shown is re-filtered against current evidence.
                    val mistakes = activeTracker.standingVerdicts(
                        _uiState.value.mistakes + snapshot.newMistakes,
                    )
                    if (snapshot.newMistakes.isNotEmpty()) alertMistake()
                    _uiState.update { state ->
                        state.copy(
                            statuses = snapshot.state.statuses,
                            cursor = snapshot.state.cursor,
                            mistakes = mistakes,
                            latestMistake = snapshot.newMistakes.lastOrNull() ?: state.latestMistake,
                            micLevel = snapshot.micLevel,
                            isSpeaking = snapshot.isSpeaking,
                            elapsedMs = snapshot.elapsedMs,
                            stuckWord = snapshot.stuckOn,
                            lastTranscript = snapshot.lastTranscript.ifBlank { state.lastTranscript },
                            lastConfidence = snapshot.lastConfidence,
                        )
                    }
                }
                finish(completed = true)
            } catch (e: MicrophoneUnavailableException) {
                _uiState.update {
                    it.copy(phase = SessionPhase.Error, message = e.message ?: "Microphone error")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Session failed", e)
                _uiState.update {
                    it.copy(phase = SessionPhase.Error, message = "Session error: ${e.message}")
                }
            } finally {
                runJob = null
                engine = null
            }
        }
    }

    fun togglePause() {
        val recitationEngine = engine ?: return
        recitationEngine.isPaused = !recitationEngine.isPaused
        _uiState.update {
            it.copy(
                phase = if (recitationEngine.isPaused) SessionPhase.Paused else SessionPhase.Listening,
                message = if (recitationEngine.isPaused) "Paused" else "Listening…",
                stuckWord = null,
            )
        }
    }

    /** Reveals the word the reciter is stuck on without ending the session. */
    fun revealCurrentWord() {
        val cursor = _uiState.value.cursor
        _uiState.update { it.copy(revealedPositions = it.revealedPositions + cursor) }
    }

    /** Accepts a hint and moves on, recording the word as missed. */
    fun skipCurrentWord() {
        val activeTracker = tracker ?: return
        val mistake = activeTracker.skipCurrent(_uiState.value.elapsedMs) ?: return
        _uiState.update { state ->
            state.copy(
                statuses = activeTracker.state().statuses,
                cursor = activeTracker.state().cursor,
                mistakes = state.mistakes + mistake,
                latestMistake = mistake,
                stuckWord = null,
                // state.cursor is still the skipped word here, so reveal it.
                revealedPositions = state.revealedPositions + state.cursor,
            )
        }
    }

    fun stop() {
        // Detach and cancel the capture job first; finish() must never cancel it,
        // because on the auto-complete path finish() runs inside that very job.
        val job = runJob
        runJob = null
        engine = null
        // Cancel here and now. Deferring it into a coroutine means that if this
        // scope dies first — the reciter navigating away — nothing ever stops the
        // capture, and the microphone keeps running for the life of the process.
        job?.cancel()
        viewModelScope.launch {
            // Still wait for the job to unwind before reading the recording: its
            // `finally` is what stops the recorder and closes the WAV.
            job?.join()
            finish(completed = false)
        }
    }

    fun restart() {
        runJob?.cancel()
        runJob = null
        engine = null
        val expected = _uiState.value.expected
        tracker = RecitationTracker(expected, _uiState.value.settings.trackerConfig())
        sessionRowId = null
        recordingFile = null
        finished = false
        _uiState.update {
            it.copy(
                phase = if (whisper != null) SessionPhase.Ready else it.phase,
                statuses = tracker?.state()?.statuses ?: emptyList(),
                cursor = 0,
                mistakes = emptyList(),
                latestMistake = null,
                elapsedMs = 0,
                stuckWord = null,
                revealedPositions = emptySet(),
                lastTranscript = "",
                finishedSessionId = null,
                message = readyMessage(),
            )
        }
    }

    /**
     * Persists and closes out the session. Safe to call from inside the capture
     * job (auto-complete) or from outside it (the Finish button) — it never
     * cancels the job, and the writes are non-cancellable so navigating away
     * mid-save still records the session.
     */
    private suspend fun finish(completed: Boolean) {
        val activeTracker = tracker ?: return
        if (finished) return
        finished = true
        val id = sessionRowId

        val remaining = if (completed) activeTracker.finish() else emptyList()
        val allMistakes = activeTracker.standingVerdicts(_uiState.value.mistakes + remaining)
        // The recorder's own clock is authoritative. The UI's elapsed time is
        // only as fresh as the last snapshot, which can lag badly when inference
        // is slow — and would otherwise be stored as the session's length.
        val elapsed = maxOf(recordedMs(), _uiState.value.elapsedMs)

        if (id != null) {
            val ayahsCompleted = countCompletedAyahs(activeTracker)
            withContext(NonCancellable) {
                container.userRepository.finishSession(
                    sessionId = id,
                    wordsCorrect = activeTracker.correctCount(),
                    mistakes = allMistakes,
                    durationMs = elapsed,
                    audioPath = recordingFile
                        ?.takeIf { it.exists() && it.length() > WAV_HEADER_BYTES }
                        ?.absolutePath,
                    ayahsCompleted = ayahsCompleted,
                )
                _uiState.value.currentAyahNumber?.let { ayahNumber ->
                    container.userRepository.setLastPosition(range.surahId, ayahNumber)
                }
                container.recordingStore.enforceCap()
            }
        }

        _uiState.update {
            it.copy(
                phase = SessionPhase.Finished,
                statuses = activeTracker.state().statuses,
                mistakes = allMistakes,
                finishedSessionId = id,
                stuckWord = null,
                message = if (completed) "Passage complete" else "Session ended",
            )
        }
    }

    /** An ayah counts as recited when every one of its words was matched. */
    private fun countCompletedAyahs(activeTracker: RecitationTracker): Int {
        val statuses = activeTracker.state().statuses
        return _uiState.value.expected
            .groupBy { it.ayahNumber }
            .count { (_, words) -> words.all { statuses.getOrNull(it.position) == WordStatus.Correct } }
    }

    private fun alertMistake() {
        val settings = _uiState.value.settings
        if (!settings.vibrationEnabled) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onCleared() {
        runJob?.cancel()
        runJob = null
        engine = null
        // Stop the microphone directly rather than trusting coroutine
        // cancellation: viewModelScope may already be cancelled here, and a
        // recorder left running would hold the mic — and keep writing audio —
        // for the rest of the process's life.
        runCatching { recorder?.stop() }
        recorder = null

        val context = whisper
        whisper = null
        // The model holds hundreds of MB, so release it off the main thread but
        // outside the (now cancelled) viewModelScope.
        container.applicationScope.launch { context?.release() }
        super.onCleared()
    }

    private companion object {
        /** A WAV file with only a header has no audio worth keeping. */
        const val WAV_HEADER_BYTES = 44
    }

    class Factory(
        private val app: Application,
        private val container: AppContainer,
        private val mode: SessionMode,
        private val range: AyahRange,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SessionViewModel(app, container, mode, range) as T
    }
}
