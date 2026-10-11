package com.tahsin.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tahsin.app.TahsinApplication
import com.tahsin.app.ui.components.StatTile
import com.tahsin.app.ui.theme.arabicStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onOpenPassage: (Int, Int) -> Unit,
) {
    val application = LocalContext.current.applicationContext as TahsinApplication
    val viewModel: SummaryViewModel = viewModel(
        key = "summary-$sessionId",
        factory = SummaryViewModel.Factory(application.container, sessionId),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) { onDispose { viewModel.stopPlayback() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete session")
                    }
                },
            )
        },
    ) { padding ->
        val summary = uiState.summary
        if (summary == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(uiState.message.ifEmpty { "Loading…" })
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "${uiState.surahName} · ${rangeLabel(summary.range)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${summary.mode.label()} · ${formatDuration(summary.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = "Accuracy",
                        value = "${(summary.accuracy * 100).toInt()}%",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Words",
                        value = "${summary.wordsCorrect}/${summary.wordsTotal}",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Mistakes",
                        value = summary.mistakeCount.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (summary.audioPath != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = viewModel::togglePlayback) {
                                    Icon(
                                        imageVector = if (uiState.isPlaying) {
                                            Icons.Default.Pause
                                        } else {
                                            Icons.Default.PlayArrow
                                        },
                                        contentDescription = if (uiState.isPlaying) "Pause" else "Play recording",
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Your recitation", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${formatDuration(uiState.positionMs)} / " +
                                            formatDuration(uiState.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (uiState.durationMs == 0L) {
                                        0f
                                    } else {
                                        uiState.positionMs.toFloat() / uiState.durationMs
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tap a mistake below to jump to that moment.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Text(
                    text = if (uiState.mistakes.isEmpty()) {
                        "No mistakes detected — well done."
                    } else {
                        "Mistakes (${uiState.mistakes.size})"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(uiState.mistakes, key = { it.id }) { mistake ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (summary.audioPath != null) viewModel.seekTo(mistake.atMs)
                        else onOpenPassage(mistake.surahId, mistake.ayahNumber)
                    },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "${mistake.surahId}:${mistake.ayahNumber} · ${mistake.kind}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        mistake.expected?.let {
                            Text(text = "Expected: $it", style = arabicStyle(20f))
                        }
                        mistake.heard?.let {
                            Text(text = "Heard: $it", style = arabicStyle(20f))
                        }
                        Text(
                            text = "at ${formatDuration(mistake.atMs)} · " +
                                "${(mistake.confidence * 100).toInt()}% confident",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Recite this passage again to reinforce it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
