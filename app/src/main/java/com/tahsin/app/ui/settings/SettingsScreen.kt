package com.tahsin.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tahsin.app.TahsinApplication
import com.tahsin.app.settings.EngineProfile
import com.tahsin.app.settings.HintLevel
import com.tahsin.app.settings.Sensitivity
import com.tahsin.app.domain.text.TajweedRule
import com.tahsin.app.settings.ThemeChoice
import com.tahsin.app.ui.theme.LocalTajweedPalette
import com.tahsin.app.ui.theme.arabicStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as TahsinApplication
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(application.container),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val settings = uiState.settings

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importModel) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionTitle("Speech model") }
            item {
                Text(
                    text = "Recognition runs on this device. Nothing you recite leaves the phone. " +
                        "A model has to be installed once — downloaded, or imported from storage " +
                        "if the device stays offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            uiState.downloading?.let { download ->
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("Downloading ${download.fileName}")
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { download.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${download.bytes / 1_048_576} MB of " +
                                        "${download.totalBytes / 1_048_576} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                OutlinedButton(onClick = viewModel::cancelDownload) { Text("Cancel") }
                            }
                        }
                    }
                }
            }

            if (uiState.installed.isNotEmpty()) {
                item { Text("Installed", style = MaterialTheme.typography.labelLarge) }
                items(uiState.installed, key = { it.fileName }) { model ->
                    Card(colors = CardDefaults.cardColors()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.activeModelFile == model.fileName ||
                                    (settings.activeModelFile == null &&
                                        uiState.installed.firstOrNull()?.fileName == model.fileName),
                                onClick = { viewModel.selectModel(model.fileName) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName)
                                Text(
                                    text = "${model.fileName} · ${model.sizeBytes / 1_048_576} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.deleteModel(model.fileName) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete model")
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import a .bin model from storage")
                }
            }

            item { Text("Available to download", style = MaterialTheme.typography.labelLarge) }
            items(uiState.catalog, key = { it.id }) { model ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.displayName + if (model.recommended) " · recommended" else "")
                            Text(
                                text = "${model.approxBytes / 1_048_576} MB — ${model.description}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (model.downloadUrl != null) {
                            IconButton(
                                onClick = { viewModel.download(model) },
                                enabled = uiState.downloading == null &&
                                    uiState.installed.none { it.fileName == model.fileName },
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download")
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Mistake detection") }
            item {
                EnumChoice(
                    options = Sensitivity.entries,
                    selected = settings.sensitivity,
                    label = { it.label },
                    description = { it.description },
                    onSelect = viewModel::setSensitivity,
                )
            }
            item {
                SwitchRow(
                    title = "Vibrate on a mistake",
                    subtitle = "A short buzz when a word does not match",
                    checked = settings.vibrationEnabled,
                    onCheckedChange = viewModel::setVibration,
                )
            }
            item {
                SwitchRow(
                    title = "Show what was heard",
                    subtitle = "Displays the live transcript and its confidence — useful for tuning",
                    checked = settings.showTranscript,
                    onCheckedChange = viewModel::setShowTranscript,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Tajweed") }
            item {
                SwitchRow(
                    title = "Colour Tajweed rules",
                    subtitle = "Colours the letters the way a printed mushaf does. " +
                        "This shows how the text should be recited; it does not check " +
                        "your pronunciation.",
                    checked = settings.tajweedEnabled,
                    onCheckedChange = viewModel::setTajweed,
                )
            }
            if (settings.tajweedEnabled) {
                item { TajweedLegend() }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Recognition speed") }
            item {
                EnumChoice(
                    options = EngineProfile.entries,
                    selected = settings.engineProfile,
                    label = { it.label },
                    description = {
                        when (it) {
                            EngineProfile.Responsive -> "Fastest feedback, slightly less accurate"
                            EngineProfile.Balanced -> "Recommended"
                            EngineProfile.Accurate -> "Beam search; needs a fast phone to keep up"
                        }
                    },
                    onSelect = viewModel::setEngineProfile,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Memorisation") }
            item {
                EnumChoice(
                    options = HintLevel.entries,
                    selected = settings.hintLevel,
                    label = { it.label },
                    description = {
                        when (it) {
                            HintLevel.Hidden -> "Upcoming words are replaced with dots"
                            HintLevel.FirstLetter -> "Shows the first letter as a prompt"
                            HintLevel.WordShape -> "Shows only the space a word occupies"
                        }
                    },
                    onSelect = viewModel::setHintLevel,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Sessions") }
            item {
                SwitchRow(
                    title = "Follow the reciter",
                    subtitle = "Scroll to the next ayah automatically",
                    checked = settings.autoAdvanceAyah,
                    onCheckedChange = viewModel::setAutoAdvance,
                )
            }
            item {
                SwitchRow(
                    title = "Keep recordings",
                    subtitle = "Lets you play a session back and jump to each mistake",
                    checked = settings.keepRecordings,
                    onCheckedChange = viewModel::setKeepRecordings,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recordings use ${uiState.recordingBytes / 1_048_576} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = viewModel::clearRecordings) { Text("Delete all") }
                }
            }
            item {
                Column {
                    Text("Daily goal: ${settings.dailyGoalMinutes} minutes")
                    Slider(
                        value = settings.dailyGoalMinutes.toFloat(),
                        onValueChange = { viewModel.setDailyGoal(it.toInt()) },
                        valueRange = 1f..60f,
                        steps = 58,
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Appearance") }
            item {
                EnumChoice(
                    options = ThemeChoice.entries,
                    selected = settings.theme,
                    label = { it.name },
                    description = { null },
                    onSelect = viewModel::setTheme,
                )
            }
            item {
                Column {
                    Text("Arabic text size: ${(settings.arabicFontScale * 100).toInt()}%")
                    Slider(
                        value = settings.arabicFontScale,
                        onValueChange = viewModel::setFontScale,
                        valueRange = 0.7f..2.0f,
                    )
                    Text(
                        text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
                        style = arabicStyle(24f, settings.arabicFontScale),
                    )
                }
            }

            if (uiState.systemInfo.isNotBlank()) {
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    Column {
                        SectionTitle("Device")
                        Text(
                            text = uiState.systemInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { AboutSection() }
        }
    }
}

/** Version, privacy in one line, and the attributions the bundled material requires. */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("About")
        Text("Tahsin ${version.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Your recitation is checked on this phone. Audio, recordings and progress " +
                "never leave it; the internet is only used if you download an extra model.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (credit in CREDITS) {
            Column {
                Text(credit.title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = credit.terms,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = credit.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(credit.url) },
                )
            }
        }
    }
}

private class Credit(val title: String, val terms: String, val url: String)

private val CREDITS = listOf(
    Credit(
        "Quran text: Tanzil Project",
        "Uthmani text and metadata, used verbatim. Creative Commons Attribution 3.0.",
        "https://tanzil.net",
    ),
    Credit(
        "Speech model: Tarteel whisper-base-ar-quran",
        "Fine-tuned from OpenAI Whisper (MIT). Apache License 2.0.",
        "https://huggingface.co/tarteel-ai/whisper-base-ar-quran",
    ),
    Credit(
        "Speech engine: whisper.cpp and ggml",
        "MIT License, Copyright (c) 2023-2024 The ggml authors.",
        "https://github.com/ggml-org/whisper.cpp",
    ),
    Credit(
        "Android Jetpack and Kotlin libraries",
        "Apache License 2.0.",
        "https://www.apache.org/licenses/LICENSE-2.0",
    ),
)

/** Shows every rule in its own colour, using a real Arabic example of the rule. */
@Composable
private fun TajweedLegend() {
    val palette = LocalTajweedPalette.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((rule, example) in TAJWEED_EXAMPLES) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = example,
                        style = arabicStyle(20f),
                        color = palette.colorFor(rule),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .width(150.dp)
                            .padding(end = 12.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(rule.label, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = rule.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One short example per rule, in the style tajweed primers use: just enough of
 * the word for the rule to apply, so the whole fragment carries that rule's
 * colour and a reader does not have to guess which letter the row is about.
 *
 * The madd examples spell the long vowel as alef + maddah (U+0653) rather than
 * the precomposed alef-with-madda, because that is how the mushaf writes it.
 */
private val TAJWEED_EXAMPLES: List<Pair<TajweedRule, String>> = listOf(
    TajweedRule.Ghunnah to "إِنَّ",
    TajweedRule.IkhfaNoon to "أَنزَلَ",
    TajweedRule.IdghamGhunnah to "مَن يَ",
    TajweedRule.IdghamNoGhunnah to "مِن رَّ",
    TajweedRule.Iqlab to "أَنۢبِ",
    TajweedRule.IkhfaShafawi to "هُم بِ",
    TajweedRule.IdghamShafawi to "لَهُم مَّا",
    TajweedRule.Qalqalah to "يَجْ",
    TajweedRule.MaddObligatory to "جَآءَ",
    TajweedRule.MaddPermissible to "مَآ أُ",
    TajweedRule.MaddNecessary to "الضَّآلِّينَ",
    TajweedRule.Silent to "أَنَا۠",
)

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> EnumChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String?,
    onSelect: (T) -> Unit,
) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
        description(selected)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
