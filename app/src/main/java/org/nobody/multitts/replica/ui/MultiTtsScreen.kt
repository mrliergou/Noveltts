package org.nobody.multitts.replica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.nobody.multitts.replica.R
import org.nobody.multitts.replica.engine.TtsEngineInfo
import org.nobody.multitts.replica.data.SpeechVoice
import org.nobody.multitts.replica.ui.theme.Accent
import org.nobody.multitts.replica.ui.theme.AppBackground
import org.nobody.multitts.replica.ui.theme.Ink
import org.nobody.multitts.replica.ui.theme.Muted
import org.nobody.multitts.replica.ui.theme.Teal

@Composable
fun MultiTtsScreen(
    viewModel: MainViewModel,
    onOpenSystemSpeechSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed,
    )
    val scope = rememberCoroutineScope()
    var showVoicePicker by remember { mutableStateOf(false) }
    var collectionDialog by remember { mutableStateOf<CollectionDialog?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
                androidx.compose.material3.HorizontalDivider()
                DrawerEntry("文字合成") { scope.launch { drawerState.close() } }
                DrawerEntry("语音引擎") {
                    scope.launch { drawerState.close() }
                    onOpenSystemSpeechSettings()
                }
                DrawerEntry("角色管理") {
                    scope.launch { drawerState.close() }
                    collectionDialog = CollectionDialog.Roles
                }
                DrawerEntry("音色库") {
                    scope.launch { drawerState.close() }
                    showVoicePicker = true
                }
                DrawerEntry("文本替换") {
                    scope.launch { drawerState.close() }
                    collectionDialog = CollectionDialog.Replacements
                }
                DrawerEntry("设置") {
                    scope.launch { drawerState.close() }
                    collectionDialog = CollectionDialog.Settings
                }
            }
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
            topBar = {
                AppTopBar(
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onEngineClick = onOpenSystemSpeechSettings,
                )
            },
            bottomBar = {
                PlaybackBar(
                    status = state.speech.statusMessage,
                    isSpeaking = state.speech.isSpeaking,
                    onSpeak = viewModel::speak,
                    onStop = viewModel::stop,
                )
            },
            containerColor = AppBackground,
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HomeIntro()
                VoiceControls(
                    speed = state.speed,
                    volume = state.volume,
                    pitch = state.pitch,
                    onSpeedChange = viewModel::updateSpeed,
                    onVolumeChange = viewModel::updateVolume,
                    onPitchChange = viewModel::updatePitch,
                )
                EngineSelector(
                    engines = state.engines,
                    selectedPackage = state.enginePackage,
                    systemDefaultPackage = state.systemDefaultPackage,
                    onEngineSelected = viewModel::selectEngine,
                )
                TextComposer(
                    text = state.text,
                    voice = state.speech.voices.firstOrNull { it.name == state.speech.selectedVoice },
                    voices = state.speech.voices,
                    onTextChange = viewModel::updateText,
                    onExampleClick = {
                        viewModel.updateText("你好，欢迎使用 MultiTTS。选择喜欢的声音，让每一段文字都变得生动。")
                    },
                    onVoiceClick = { showVoicePicker = true },
                )
                EngineStatus(
                    isReady = state.speech.isReady,
                    engineName = state.speech.engineName,
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }

    if (showVoicePicker) {
        VoicePickerDialog(
            voices = state.speech.voices,
            selectedVoice = state.speech.selectedVoice,
            onSelect = { voice ->
                viewModel.selectVoice(voice)
                showVoicePicker = false
            },
            onDismiss = { showVoicePicker = false },
        )
    }

    when (val dialog = collectionDialog) {
        CollectionDialog.Roles -> SavedItemsDialog(
            title = "角色管理",
            items = state.roles,
            firstFieldLabel = "角色名称",
            onAdd = viewModel::addRole,
            onRemove = viewModel::removeRole,
            onDismiss = { collectionDialog = null },
        )

        CollectionDialog.Replacements -> ReplacementDialog(
            items = state.replacements,
            onAdd = viewModel::addReplacement,
            onRemove = viewModel::removeReplacement,
            onDismiss = { collectionDialog = null },
        )

        CollectionDialog.Settings -> SettingsDialog(
            onOpenSystemSpeechSettings = onOpenSystemSpeechSettings,
            onClearRecentText = viewModel::clearRecentText,
            onDismiss = { collectionDialog = null },
        )

        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(onMenuClick: () -> Unit, onEngineClick: () -> Unit) {
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("MultiTTS", fontWeight = FontWeight.Bold)
                Text(
                    "多语音合成工作台",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                Text("☰", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        },
        actions = {
            TextButton(onClick = onEngineClick) {
                Text("引擎", color = Color.White)
            }
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = Color.White,
        ),
    )
}

@Composable
private fun HomeIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = stringResource(R.string.hero_kicker),
            color = Accent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.home_title),
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.home_description),
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun VoiceControls(
    speed: Int,
    volume: Int,
    pitch: Int,
    onSpeedChange: (Int) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onPitchChange: (Int) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            SliderRow("语速", speed, "慢", "快", onSpeedChange)
            androidx.compose.material3.HorizontalDivider()
            SliderRow("音量", volume, "轻", "响", onVolumeChange)
            androidx.compose.material3.HorizontalDivider()
            SliderRow("音调", pitch, "低", "高", onPitchChange)
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    low: String,
    high: String,
    onValueChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
    ) {
        Text(label, modifier = Modifier.width(42.dp), fontWeight = FontWeight.SemiBold, color = Ink)
        Text(low, style = MaterialTheme.typography.labelSmall, color = Muted)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(high, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(
            text = value.toString(),
            modifier = Modifier.width(34.dp).padding(start = 8.dp),
            color = Teal,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EngineSelector(
    engines: List<TtsEngineInfo>,
    selectedPackage: String?,
    systemDefaultPackage: String?,
    onEngineSelected: (String?) -> Unit,
) {
    val selfPackage = LocalContext.current.packageName
    val defaultLabel = engines.firstOrNull { it.packageName == systemDefaultPackage }?.label
        ?: systemDefaultPackage.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("合成引擎", fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            Text(
                text = if (systemDefaultPackage == selfPackage) "本应用是系统默认引擎" else "系统默认：$defaultLabel",
                style = MaterialTheme.typography.labelSmall,
                color = if (systemDefaultPackage == selfPackage) Teal else Muted,
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EngineChip(
                label = "跟随系统",
                selected = selectedPackage == null,
                onClick = { onEngineSelected(null) },
            )
            engines.forEach { engine ->
                EngineChip(
                    label = if (engine.packageName == selfPackage) "${engine.label} · 本应用" else engine.label,
                    selected = selectedPackage == engine.packageName,
                    onClick = { onEngineSelected(engine.packageName) },
                )
            }
        }
        Text(
            text = "工作台用所选引擎朗读；选中本应用时，它会再把文本委托给其它引擎合成。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}

@Composable
private fun EngineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) Teal else MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(40.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Text(
                text = label,
                color = if (selected) Color.White else Ink,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TextComposer(
    text: String,
    voice: SpeechVoice?,
    voices: List<SpeechVoice>,
    onTextChange: (String) -> Unit,
    onExampleClick: () -> Unit,
    onVoiceClick: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.audition_text),
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onExampleClick) {
                    Text(stringResource(R.string.fill_example))
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 7,
                placeholder = { Text(stringResource(R.string.text_hint)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.voice), color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = voice?.let { "${it.displayName} · ${it.localeName}" }
                            ?: stringResource(R.string.default_voice),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = onVoiceClick, enabled = voices.isNotEmpty()) {
                    Text(stringResource(R.string.voice_pool))
                }
            }
        }
    }
}

@Composable
private fun EngineStatus(isReady: Boolean, engineName: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = MaterialTheme.shapes.small,
            color = if (isReady) Teal else Accent,
        ) {}
        Text(
            text = when {
                !isReady -> stringResource(R.string.engine_connecting)
                engineName.isBlank() -> stringResource(R.string.engine_ready)
                else -> stringResource(R.string.engine_connected, engineName)
            },
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PlaybackBar(
    status: String,
    isSpeaking: Boolean,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSpeak,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                ) {
                    Text(
                        if (isSpeaking) stringResource(R.string.speaking) else stringResource(R.string.start_speaking),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.size(width = 54.dp, height = 50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppBackground,
                        contentColor = Ink,
                    ),
                ) {
                    Text("■", modifier = Modifier.semantics { contentDescription = "停止朗读" })
                }
            }
            Text(status, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
private fun VoicePickerDialog(
    voices: List<SpeechVoice>,
    selectedVoice: String,
    onSelect: (SpeechVoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_pool)) },
        text = {
            if (voices.isEmpty()) {
                Text(stringResource(R.string.no_voices))
            } else {
                LazyColumn {
                    items(voices, key = { it.name }) { voice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(voice) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = voice.name == selectedVoice,
                                onClick = { onSelect(voice) },
                            )
                            Column {
                                Text(voice.displayName, color = Ink)
                                Text(voice.localeName, color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun SavedItemsDialog(
    title: String,
    items: Set<String>,
    firstFieldLabel: String,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "目前只做保存，尚未参与合成；接入数据层后再生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(firstFieldLabel) },
                    singleLine = true,
                )
                SavedItemList(items, onRemove)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(value)
                    value = ""
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun ReplacementDialog(
    items: Set<String>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var find by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.replacements)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "替换规则目前只做保存，朗读时尚未生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                OutlinedTextField(find, { find = it }, label = { Text(stringResource(R.string.find_text)) }, singleLine = true)
                OutlinedTextField(replaceWith, { replaceWith = it }, label = { Text(stringResource(R.string.replace_with)) }, singleLine = true)
                SavedItemList(items, onRemove)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(find, replaceWith)
                    find = ""
                    replaceWith = ""
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun SavedItemList(items: Set<String>, onRemove: (String) -> Unit) {
    if (items.isEmpty()) {
        Text(stringResource(R.string.empty_saved_items), color = Muted)
        return
    }
    LazyColumn(modifier = Modifier.height(150.dp)) {
        items(items.toList().sorted(), key = { it }) { value ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, modifier = Modifier.weight(1f), color = Ink)
                TextButton(onClick = { onRemove(value) }) {
                    Text(stringResource(R.string.remove))
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    onOpenSystemSpeechSettings: () -> Unit,
    onClearRecentText: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                TextButton(onClick = onOpenSystemSpeechSettings) {
                    Text(stringResource(R.string.open_system_speech_settings))
                }
                TextButton(onClick = onClearRecentText) {
                    Text(stringResource(R.string.clear_recent_text))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

@Composable
private fun DrawerEntry(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        modifier = modifier.padding(horizontal = 12.dp),
    )
}

private sealed interface CollectionDialog {
    data object Roles : CollectionDialog
    data object Replacements : CollectionDialog
    data object Settings : CollectionDialog
}
