package com.tahsin.app.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tahsin.app.TahsinApplication
import com.tahsin.app.domain.model.AyahRange
import com.tahsin.app.domain.model.SessionMode
import com.tahsin.app.ui.session.label
import com.tahsin.app.ui.theme.arabicStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangePickerScreen(
    mode: SessionMode,
    surahId: Int,
    initialAyah: Int?,
    onBack: () -> Unit,
    onStart: (SessionMode, AyahRange) -> Unit,
) {
    val application = LocalContext.current.applicationContext as TahsinApplication
    val viewModel: RangePickerViewModel = viewModel(
        key = "picker-$surahId",
        factory = RangePickerViewModel.Factory(application.container, surahId, initialAyah),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.surah?.nameEn ?: "Surah $surahId")
                        Text(
                            text = "${mode.label()} · pick a passage",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            // The app draws edge to edge, so a custom bottom bar has to clear the
            // system navigation bar itself. With gesture navigation that strip is
            // thin enough to miss, but a three-button bar (the MIUI default) hid
            // these buttons completely.
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Text(
                    text = uiState.selectionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onStart(mode, AyahRange(surahId, uiState.startAyah, uiState.endAyah))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.surah != null,
                    ) { Text("Start ${mode.label()}") }
                    Button(
                        onClick = {
                            val surah = uiState.surah ?: return@Button
                            onStart(mode, AyahRange(surahId, 1, surah.ayahCount))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.surah != null,
                    ) { Text("Whole surah") }
                }
            }
        },
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "Tap an ayah to set the start, tap a second one to set the end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(uiState.ayahs, key = { it.id }) { ayah ->
                val selected = ayah.ayahNumber in uiState.startAyah..uiState.endAyah
                val isEdge = ayah.ayahNumber == uiState.startAyah || ayah.ayahNumber == uiState.endAyah
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.onAyahTapped(ayah.ayahNumber) },
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isEdge -> MaterialTheme.colorScheme.primaryContainer
                            selected -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Ayah ${ayah.ayahNumber}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {},
                                    enabled = false,
                                    label = { Text("Juz ${ayah.juz} · p. ${ayah.page}") },
                                )
                                uiState.memorizationStrength[ayah.ayahNumber]?.let { strength ->
                                    Text(
                                        text = "${(strength * 100).toInt()}% solid",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { viewModel.toggleBookmark(ayah.ayahNumber) },
                            ) {
                                val bookmarked = ayah.ayahNumber in uiState.bookmarkedAyahs
                                Icon(
                                    imageVector = if (bookmarked) {
                                        Icons.Default.Bookmark
                                    } else {
                                        Icons.Default.BookmarkBorder
                                    },
                                    contentDescription = if (bookmarked) {
                                        "Remove bookmark"
                                    } else {
                                        "Bookmark this ayah"
                                    },
                                )
                            }
                        }
                        Text(
                            text = ayah.textUthmani,
                            style = arabicStyle(22f, uiState.fontScale),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
