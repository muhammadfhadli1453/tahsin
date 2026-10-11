package com.tahsin.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tahsin.app.TahsinApplication
import com.tahsin.app.ui.components.StatTile
import com.tahsin.app.ui.session.formatDuration
import com.tahsin.app.ui.session.label
import com.tahsin.app.ui.session.rangeLabel
import com.tahsin.app.ui.theme.arabicStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onOpenPassage: (Int, Int) -> Unit,
) {
    val application = LocalContext.current.applicationContext as TahsinApplication
    val viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.Factory(application.container),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History & insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sessions") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Insights") })
            }
            when (tab) {
                0 -> SessionsTab(uiState, onOpenSession)
                else -> InsightsTab(uiState, onOpenPassage)
            }
        }
    }
}

@Composable
private fun SessionsTab(uiState: HistoryUiState, onOpenSession: (Long) -> Unit) {
    if (uiState.sessions.isEmpty()) {
        EmptyState("No sessions yet. Recite a passage and it will show up here.")
        return
    }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uiState.sessions, key = { it.id }) { session ->
            Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenSession(session.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "${uiState.surahNames[session.range.surahId] ?: "Surah ${session.range.surahId}"} · " +
                            rangeLabel(session.range),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${session.mode.label()} · ${(session.accuracy * 100).toInt()}% · " +
                            "${session.mistakeCount} mistakes · ${formatDuration(session.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (session.audioPath != null) {
                        Text(
                            text = "Recording available",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightsTab(uiState: HistoryUiState, onOpenPassage: (Int, Int) -> Unit) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "Total time",
                    value = formatDuration(uiState.totalMs),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Sessions",
                    value = uiState.sessions.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Avg accuracy",
                    value = "${(uiState.averageAccuracy * 100).toInt()}%",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Text("Last 14 days", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            MinutesBarChart(uiState.dailyMinutes)
        }

        item {
            HorizontalDivider()
            Text(
                text = "Most-missed words",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (uiState.mostMissedWords.isEmpty()) {
            item {
                Text(
                    text = "Nothing yet — mistakes you make will be collected here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(uiState.mostMissedWords, key = { "${it.surahId}:${it.ayahNumber}:${it.expected}" }) { row ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenPassage(row.surahId, row.ayahNumber) },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(text = row.expected, style = arabicStyle(22f))
                        Text(
                            text = "${row.surahId}:${row.ayahNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "×${row.occurrences}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Text(
                text = "Weakest ayahs",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(uiState.weakestAyahs, key = { "w${it.surahId}:${it.ayahNumber}" }) { row ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenPassage(row.surahId, row.ayahNumber) },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${uiState.surahNames[row.surahId] ?: "Surah ${row.surahId}"} · " +
                            "ayah ${row.ayahNumber}",
                    )
                    Text("${row.occurrences} mistakes")
                }
            }
        }
    }
}

/** Minimal bar chart; the dataset is at most 14 points so a Canvas is overkill. */
@Composable
private fun MinutesBarChart(minutesByDay: List<DailyMinutes>) {
    if (minutesByDay.isEmpty()) {
        Text(
            text = "No recitation recorded yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val observedPeak = minutesByDay.maxOf { it.minutes }
    val peak = observedPeak.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        // The bars live in their own fixed-height row so they all grow from one
        // baseline; putting the labels in the same row pushed each bar up by the
        // height of its own label.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (day in minutesByDay) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(
                            fraction = (day.minutes.toFloat() / peak).coerceIn(0.02f, 1f),
                        )
                        .background(
                            color = if (day.minutes > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (day in minutesByDay) {
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (observedPeak > 0) {
            Text(
                text = "Peak $observedPeak min/day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Under a minute recorded so far",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
