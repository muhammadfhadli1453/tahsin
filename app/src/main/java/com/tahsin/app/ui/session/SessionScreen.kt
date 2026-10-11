package com.tahsin.app.ui.session

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tahsin.app.TahsinApplication
import com.tahsin.app.domain.model.AyahRange
import com.tahsin.app.domain.model.Mistake
import com.tahsin.app.domain.model.MistakeKind
import com.tahsin.app.domain.model.SessionMode
import com.tahsin.app.ui.components.MicLevelBar
import com.tahsin.app.ui.components.MushafAyah
import com.tahsin.app.ui.theme.arabicStyle
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    mode: SessionMode,
    range: AyahRange,
    onBack: () -> Unit,
    onOpenSummary: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as TahsinApplication
    val viewModel: SessionViewModel = viewModel(
        key = "session-${mode.name}-${range.surahId}-${range.startAyah}-${range.endAyah}",
        factory = SessionViewModel.Factory(application, application.container, mode, range),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        permissionPermanentlyDenied = !granted
        if (granted) viewModel.start()
    }

    LaunchedEffect(uiState.latestMistake) {
        val mistake = uiState.latestMistake ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(mistake.describe())
    }

    val listState = rememberLazyListState()
    // Follow the reciter down the passage as the cursor crosses ayah boundaries.
    LaunchedEffect(uiState.currentAyahNumber, uiState.settings.autoAdvanceAyah) {
        if (!uiState.settings.autoAdvanceAyah) return@LaunchedEffect
        val ayahNumber = uiState.currentAyahNumber ?: return@LaunchedEffect
        val index = uiState.ayahs.indexOfFirst { it.ayahNumber == ayahNumber }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.surah?.nameAr ?: "",
                            style = MaterialTheme.typography.titleMedium.copy(
                                textDirection = TextDirection.Rtl,
                            ),
                        )
                        Text(
                            text = "${uiState.surah?.nameEn ?: ""} · " +
                                rangeLabel(range) + " · ${mode.label()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::restart) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restart passage")
                    }
                },
            )
        },
        floatingActionButton = {
            SessionFab(
                phase = uiState.phase,
                onStart = {
                    if (micGranted) {
                        viewModel.start()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onPause = viewModel::togglePause,
                onFinish = viewModel::stop,
                onOpenSummary = { uiState.finishedSessionId?.let(onOpenSummary) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SessionHeader(uiState)

            if (uiState.phase == SessionPhase.NeedsModel || uiState.phase == SessionPhase.Error) {
                ProblemPanel(
                    message = uiState.message,
                    showSettings = uiState.phase == SessionPhase.NeedsModel,
                    onOpenSettings = onOpenSettings,
                )
                return@Column
            }

            if (uiState.phase == SessionPhase.Loading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(uiState.message, textAlign = TextAlign.Center)
                }
                return@Column
            }

            if (!micGranted && permissionPermanentlyDenied) {
                Text(
                    text = "Microphone access is required to check your recitation. " +
                        "Enable it for Tahsin in Android settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            uiState.stuckWord?.let { stuck ->
                StuckPanel(
                    hidden = mode == SessionMode.Memorize,
                    wordText = stuck.uthmani,
                    revealed = stuck.position in uiState.revealedPositions,
                    onReveal = viewModel::revealCurrentWord,
                    onSkip = viewModel::skipCurrentWord,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(uiState.ayahs, key = { _, ayah -> ayah.id }) { _, ayah ->
                    val words = uiState.expected.filter { it.ayahNumber == ayah.ayahNumber }
                    Column {
                        Text(
                            text = "${ayah.surahId}:${ayah.ayahNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        MushafAyah(
                            words = words,
                            statuses = uiState.statuses,
                            fontScale = uiState.settings.arabicFontScale,
                            maskUpcoming = mode == SessionMode.Memorize,
                            hintLevel = uiState.settings.hintLevel,
                            revealedPositions = uiState.revealedPositions,
                            showTajweed = uiState.settings.tajweedEnabled,
                        )
                    }
                }

                if (uiState.mistakes.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "Mistakes this session (${uiState.mistakes.size})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(uiState.mistakes.asReversed().take(30)) { mistake ->
                        Text(
                            text = mistake.describe(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(uiState: SessionUiState) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(uiState.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatDuration(uiState.elapsedMs),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = {
                if (uiState.wordsTotal == 0) 0f else uiState.cursor.toFloat() / uiState.wordsTotal
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        MicLevelBar(
            level = uiState.micLevel,
            active = uiState.phase == SessionPhase.Listening,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${uiState.wordsCorrect}/${uiState.wordsTotal} words",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "${(uiState.accuracy * 100).toInt()}% accurate",
                style = MaterialTheme.typography.labelSmall,
            )
            if (uiState.modelName.isNotEmpty()) {
                Text(
                    text = uiState.modelName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (uiState.settings.showTranscript && uiState.lastTranscript.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        text = "Heard (${(uiState.lastConfidence * 100).toInt()}% confident)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = uiState.lastTranscript,
                        style = arabicStyle(16f, uiState.settings.arabicFontScale),
                    )
                }
            }
        }
    }
}

@Composable
private fun StuckPanel(
    hidden: Boolean,
    wordText: String,
    revealed: Boolean,
    onReveal: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Stuck here?", style = MaterialTheme.typography.titleSmall)
                if (revealed || !hidden) {
                    Text(text = wordText, style = arabicStyle(22f))
                } else {
                    Text(
                        text = "Tap Peek to see the next word.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (hidden && !revealed) {
                AssistChip(
                    onClick = onReveal,
                    label = { Text("Peek") },
                    leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                )
            }
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun ProblemPanel(message: String, showSettings: Boolean, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error,
        )
        if (showSettings) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

@Composable
private fun SessionFab(
    phase: SessionPhase,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    onOpenSummary: () -> Unit,
) {
    when (phase) {
        SessionPhase.Ready -> ExtendedFloatingActionButton(
            onClick = onStart,
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            text = { Text("Start") },
        )
        SessionPhase.Listening, SessionPhase.Paused -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPause) {
                Icon(
                    imageVector = if (phase == SessionPhase.Paused) {
                        Icons.Default.PlayArrow
                    } else {
                        Icons.Default.Pause
                    },
                    contentDescription = if (phase == SessionPhase.Paused) "Resume" else "Pause",
                )
            }
            ExtendedFloatingActionButton(
                onClick = onFinish,
                icon = { Icon(Icons.Default.Check, contentDescription = null) },
                text = { Text("Finish") },
            )
        }
        SessionPhase.Finished -> ExtendedFloatingActionButton(
            onClick = onOpenSummary,
            icon = { Icon(Icons.Default.Check, contentDescription = null) },
            text = { Text("See results") },
        )
        else -> Spacer(Modifier.size(0.dp))
    }
}

internal fun Mistake.describe(): String = when (kind) {
    MistakeKind.WrongWord -> "$surahId:$ayahNumber — expected “$expected”, heard “$heard”"
    MistakeKind.MissedWord -> "$surahId:$ayahNumber — skipped “$expected”"
    MistakeKind.ExtraWord -> "$surahId:$ayahNumber — extra word “$heard”"
    MistakeKind.Pronunciation -> "$surahId:$ayahNumber — “$expected”: ${note ?: "check the reading"}"
}

internal fun SessionMode.label(): String = when (this) {
    SessionMode.Recite -> "Recite"
    SessionMode.Memorize -> "Memorize"
    SessionMode.Qiyam -> "Qiyam"
}

internal fun rangeLabel(range: AyahRange): String =
    if (range.startAyah == range.endAyah) {
        "Ayah ${range.startAyah}"
    } else {
        "Ayahs ${range.startAyah}–${range.endAyah}"
    }

internal fun formatDuration(ms: Long): String {
    val duration = ms.milliseconds
    val minutes = duration.inWholeMinutes
    val seconds = duration.inWholeSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
