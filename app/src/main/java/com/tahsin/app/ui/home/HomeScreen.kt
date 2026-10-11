package com.tahsin.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tahsin.app.TahsinApplication
import com.tahsin.app.domain.model.SessionMode
import com.tahsin.app.ui.components.StatTile
import com.tahsin.app.ui.session.formatDuration
import com.tahsin.app.ui.session.label
import com.tahsin.app.ui.session.rangeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBrowse: (SessionMode) -> Unit,
    onResume: (Int, Int) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: (Long) -> Unit,
) {
    val application = LocalContext.current.applicationContext as TahsinApplication
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(application.container),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tahsin") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search the Quran")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!uiState.modelInstalled) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = "No speech model installed",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "Recitation checking runs entirely on this device, so it " +
                                    "needs a Whisper model. Download one once, or import a .bin " +
                                    "file if you are offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onOpenSettings) { Text("Set up the model") }
                        }
                    }
                }
            }

            item { GoalCard(uiState) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = "Streak",
                        value = "${uiState.streak.currentStreak}d",
                        caption = "best ${uiState.streak.longestStreak}d",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Words today",
                        value = "${uiState.today?.wordsCorrect ?: 0}",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Mistakes today",
                        value = "${uiState.today?.mistakeCount ?: 0}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            uiState.resume?.let { resume ->
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onResume(resume.surahId, resume.ayahNumber) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Continue where you left off", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = "${resume.surahName} · ayah ${resume.ayahNumber}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            item {
                Text("Start a session", style = MaterialTheme.typography.titleSmall)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(
                        label = SessionMode.Recite.label(),
                        description = "Read along",
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        modifier = Modifier.weight(1f),
                        onClick = { onBrowse(SessionMode.Recite) },
                    )
                    ModeButton(
                        label = SessionMode.Memorize.label(),
                        description = "Hidden words",
                        icon = Icons.Default.Psychology,
                        modifier = Modifier.weight(1f),
                        onClick = { onBrowse(SessionMode.Memorize) },
                    )
                    ModeButton(
                        label = SessionMode.Qiyam.label(),
                        description = "Long session",
                        icon = Icons.Default.Mic,
                        modifier = Modifier.weight(1f),
                        onClick = { onBrowse(SessionMode.Qiyam) },
                    )
                }
            }

            if (uiState.weakestAyahs.isNotEmpty()) {
                item {
                    Text(
                        text = "Needs review",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(uiState.weakestAyahs, key = { "${it.surahId}:${it.ayahNumber}" }) { weak ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onResume(weak.surahId, weak.ayahNumber) },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Surah ${weak.surahId}, ayah ${weak.ayahNumber}")
                            Text(
                                text = "${(weak.strength * 100).toInt()}% solid",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (uiState.recentSessions.isNotEmpty()) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recent sessions", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = onOpenHistory) { Text("All") }
                    }
                }
                items(uiState.recentSessions, key = { it.id }) { session ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenSession(session.id) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "Surah ${session.range.surahId} · ${rangeLabel(session.range)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "${(session.accuracy * 100).toInt()}% · " +
                                    "${session.mistakeCount} mistakes · " +
                                    formatDuration(session.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCard(uiState: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { uiState.goalProgress },
                    modifier = Modifier.size(72.dp),
                    strokeWidth = 7.dp,
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    text = "${(uiState.goalProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Column {
                Text("Today's goal", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = "${uiState.minutesToday} of ${uiState.goalMinutes} min",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = if (uiState.goalProgress >= 1f) {
                        "Goal reached — streak safe."
                    } else {
                        "${uiState.goalMinutes - uiState.minutesToday} min to go."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(modifier = modifier, onClick = onClick) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
