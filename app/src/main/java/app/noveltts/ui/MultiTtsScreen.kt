package app.noveltts.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.noveltts.data.voicepack.CatalogEntry
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.data.voicepack.VoicePack
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleRule
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import java.util.UUID
import kotlin.math.roundToInt

private const val SAMPLE_TEXT =
    "你好，欢迎使用 NovelTTS。选择喜欢的声音，让每一段文字都变得生动。\n\n" +
        "他压低声音说：“今晚的月色真美。”"

/**
 * 工作台主界面：左侧抽屉切换「工作台 / 语音包 / 角色 / 替换 / 设置」五个页面。
 *
 * 调用签名由 `MainActivity` 固定，改动需同步修改 Activity。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiTtsScreen(
    viewModel: MainViewModel,
    onOpenSystemSpeechSettings: () -> Unit,
    openVoicePacksOnStart: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importPack(uri)
    }

    LaunchedEffect(openVoicePacksOnStart) {
        if (openVoicePacksOnStart) viewModel.selectSection(AppSection.PACKS)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(272.dp)) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 18.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = "NovelTTS",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Text(
                            text = "多语音合成工作台",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    AppSection.values().forEach { section ->
                        NavigationDrawerItem(
                            label = { Text(section.label) },
                            selected = state.section == section,
                            onClick = {
                                viewModel.selectSection(section)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    Spacer(Modifier.height(8.dp))
                    NavigationDrawerItem(
                        label = { Text("打开系统语音设置") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSystemSpeechSettings()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "系统音色名 multitts:<包>:<引擎>:<音色>",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("菜单") }
                    },
                    title = {
                        Column {
                            Text(text = "NovelTTS", fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(
                                text = state.section.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
                    },
                )
            },
            bottomBar = { BottomPanel(state = state, viewModel = viewModel) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (state.section) {
                    AppSection.WORKBENCH -> WorkbenchPage(state = state, viewModel = viewModel)
                    AppSection.PACKS -> PacksPage(
                        state = state,
                        viewModel = viewModel,
                        onImport = {
                            importLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*"),
                            )
                        },
                    )

                    AppSection.ROLES -> RolesPage(state = state, viewModel = viewModel)
                    AppSection.REPLACEMENTS -> ReplacementsPage(state = state, viewModel = viewModel)
                    AppSection.SETTINGS -> SettingsPage(
                        state = state,
                        viewModel = viewModel,
                        onOpenSystemSpeechSettings = onOpenSystemSpeechSettings,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 工作台
// ---------------------------------------------------------------------------

@Composable
private fun WorkbenchPage(state: MainUiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("文字 · 声音 · 表达", style = MaterialTheme.typography.labelMedium, color = Accent)
        Text(
            text = "让文字，开口说话。",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            text = "输入一段文字，为旁白和对话选择不同音色。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        SectionCard {
            SliderRow("语速", "慢", "快", state.speed, viewModel::updateSpeed)
            SliderRow("音量", "轻", "响", state.volume, viewModel::updateVolume)
            SliderRow("音调", "低", "高", state.pitch, viewModel::updatePitch)
        }

        SectionTitle("朗读音色")
        VoiceField("旁白音色", state.narratorVoiceId, state.voices, viewModel::selectNarrator)
        VoiceField("对话音色", state.dialogueVoiceId, state.voices, viewModel::selectDialogue)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("试听文本", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.updateText(SAMPLE_TEXT) }) { Text("填入示例") }
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::updateText,
            placeholder = { Text("在这里输入或粘贴要朗读的文字…") },
            minLines = 5,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("分段预览（${state.preview.size}）")
        if (state.preview.isEmpty()) {
            Text(
                text = "输入文本后，这里会显示旁白与对话的分段结果。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        } else {
            state.preview.forEach { segment -> PreviewRow(segment) }
        }
        if (state.pipelineIssues.isNotEmpty()) {
            state.pipelineIssues.forEach { issue ->
                Text(
                    text = "⚠ ${issue.field}：${issue.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(segment: PreviewSegment) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(text = if (segment.isDialogue) "对话" else "旁白", highlight = segment.isDialogue)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = segment.voiceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
                segment.roleName?.let { role ->
                    Text(
                        text = "角色：$role",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(text = segment.text, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    startLabel: String,
    endLabel: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.width(10.dp))
        Text(startLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        )
        Text(endLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.width(8.dp))
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
            modifier = Modifier.width(32.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 语音包
// ---------------------------------------------------------------------------

@Composable
private fun PacksPage(state: MainUiState, viewModel: MainViewModel, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "语音包",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            text = "支持原版包（config.yaml + engines.yaml）与旧版 pack.json 包。包只声明音色，安装后即可在系统 TTS 中选择。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, enabled = state.packBusyId == null) { Text("导入本地 zip") }
            if (state.packBusyId == PACK_BUSY_IMPORT) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.catalogUrl,
                onValueChange = viewModel::setCatalogUrl,
                label = { Text("在线目录地址") },
                placeholder = { Text("https://…/catalog.json") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.refreshCatalog() }, enabled = state.packBusyId == null) { Text("刷新") }
        }

        if (state.packProgress in 0..100) {
            LinearProgressIndicator(
                progress = { state.packProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.packStatus?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = Ink)
        }

        SectionTitle("已安装（${state.packs.size}）")
        if (state.packs.isEmpty()) {
            Text(
                text = "还没有语音包。可导入本地 zip，或填写在线目录后刷新下载。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        state.packs.forEach { pack ->
            PackCard(
                pack = pack,
                busy = state.packBusyId != null,
                onRemove = { viewModel.removePack(pack) },
            )
        }

        SectionTitle("在线语音包（${state.catalog.size}）")
        if (state.catalog.isEmpty()) {
            Text(
                text = "填写目录地址后点「刷新」，这里会列出可下载的语音包。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        state.catalog.forEach { entry ->
            CatalogRow(
                entry = entry,
                installed = state.packs.any { it.id == entry.id },
                busy = state.packBusyId == entry.id,
                enabled = state.packBusyId == null,
                onDownload = { viewModel.downloadPack(entry) },
            )
        }
    }
}

@Composable
private fun PackCard(pack: VoicePack, busy: Boolean, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pack.name}  v${pack.version}",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                Badge(text = if (pack.isOriginal) "原版包" else "兼容包", highlight = pack.isOriginal)
            }
            val meta = buildString {
                append("${pack.voices.size} 个音色")
                if (pack.author.isNotBlank()) append(" · ${pack.author}")
            }
            Text(text = meta, style = MaterialTheme.typography.labelSmall, color = Muted)
            if (pack.description.isNotBlank()) {
                Text(text = pack.description, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (pack.engines.isNotEmpty()) {
                Text(
                    text = "引擎：" + pack.engines.joinToString { "${it.code}${if (it.supported) "" else "（未支持）"}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            pack.voices.take(6).forEach { voice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = voice.name, style = MaterialTheme.typography.bodySmall, color = Ink)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = voice.engineCode ?: voice.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.weight(1f),
                    )
                    Badge(text = if (voice.isOnline) "在线" else "离线")
                }
            }
            if (pack.voices.size > 6) {
                Text(
                    text = "… 还有 ${pack.voices.size - 6} 个音色",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove, enabled = !busy) { Text("删除") }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${entry.name}  v${entry.version}", fontWeight = FontWeight.SemiBold, color = Ink)
                val meta = buildString {
                    if (entry.voiceCount > 0) append("${entry.voiceCount} 个音色")
                    if (entry.sizeBytes > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(formatSize(entry.sizeBytes))
                    }
                    if (entry.sha256.isNullOrBlank()) append(" · 无校验和")
                }
                if (meta.isNotEmpty()) {
                    Text(text = meta, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                if (entry.description.isNotBlank()) {
                    Text(text = entry.description, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                installed -> Text(text = "已安装", style = MaterialTheme.typography.labelMedium, color = Muted)
                else -> TextButton(onClick = onDownload, enabled = enabled) { Text("下载") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 角色
// ---------------------------------------------------------------------------

@Composable
private fun RolesPage(state: MainUiState, viewModel: MainViewModel) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RoleRule?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "角色规则",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    text = "用正则匹配引号内的对话，命中后改用指定音色；优先级大的先匹配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Button(onClick = { creating = true }) { Text("新增") }
        }

        if (state.roles.isEmpty()) {
            Text(
                text = "还没有角色规则。未命中的对话会使用「对话音色」。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        state.roles.forEach { rule ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = rule.name, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(
                                text = "匹配：${rule.pattern}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                            val meta = buildString {
                                append("音色：").append(viewModel.voiceDisplayName(rule.voiceId))
                                append(" · 优先级 ").append(rule.priority)
                                if (rule.onlyDialogue) append(" · 仅对话")
                            }
                            Text(text = meta, style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { viewModel.toggleRole(rule, it) })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = rule }) { Text("编辑") }
                        TextButton(onClick = { viewModel.deleteRole(rule) }) { Text("删除") }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        RoleEditorDialog(
            rule = editing,
            voices = state.voices,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                viewModel.saveRole(rule)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun RoleEditorDialog(
    rule: RoleRule?,
    voices: List<VoiceDescriptor>,
    onDismiss: () -> Unit,
    onSave: (RoleRule) -> Unit,
) {
    var name by remember { mutableStateOf(rule?.name.orEmpty()) }
    var pattern by remember { mutableStateOf(rule?.pattern.orEmpty()) }
    var voiceId by remember {
        mutableStateOf(rule?.voiceId ?: voices.firstOrNull { it.usable }?.systemName.orEmpty())
    }
    var priority by remember { mutableStateOf((rule?.priority ?: 100).toString()) }
    var onlyDialogue by remember { mutableStateOf(rule?.onlyDialogue ?: true) }
    var enabled by remember { mutableStateOf(rule?.enabled ?: true) }
    var pickingVoice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "新增角色" else "编辑角色") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("匹配表达式（正则）") },
                    supportingText = { Text("例如 林黛玉|黛玉") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("音色", style = MaterialTheme.typography.labelSmall, color = Muted)
                        Text(
                            text = voices.firstOrNull { it.systemName == voiceId }?.displayName ?: "未选择",
                            color = Ink,
                        )
                    }
                    TextButton(onClick = { pickingVoice = true }) { Text("选择") }
                }
                OutlinedTextField(
                    value = priority,
                    onValueChange = { value -> priority = value.filter { it.isDigit() }.take(4) },
                    label = { Text("优先级（数字越大越先匹配）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仅匹配对话", color = Ink, modifier = Modifier.weight(1f))
                    Switch(checked = onlyDialogue, onCheckedChange = { onlyDialogue = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", color = Ink, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && pattern.isNotBlank() && voiceId.isNotBlank(),
                onClick = {
                    onSave(
                        RoleRule(
                            id = rule?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            pattern = pattern.trim(),
                            voiceId = voiceId,
                            priority = priority.toIntOrNull() ?: 100,
                            onlyDialogue = onlyDialogue,
                            enabled = enabled,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (pickingVoice) {
        VoicePickerDialog(
            title = "角色音色",
            voices = voices,
            selectedId = voiceId,
            onSelect = {
                voiceId = it
                pickingVoice = false
            },
            onDismiss = { pickingVoice = false },
        )
    }
}

// ---------------------------------------------------------------------------
// 替换
// ---------------------------------------------------------------------------

@Composable
private fun ReplacementsPage(state: MainUiState, viewModel: MainViewModel) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReplacementRule?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "替换规则",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    text = "按「顺序」从小到大执行；多音字可用普通替换或正则分组。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Button(onClick = { creating = true }) { Text("新增") }
        }

        if (state.replacements.isEmpty()) {
            Text(
                text = "还没有替换规则。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        state.replacements.forEach { rule ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = rule.name, fontWeight = FontWeight.SemiBold, color = Ink)
                                if (rule.isRegex) {
                                    Spacer(Modifier.width(6.dp))
                                    Badge(text = "正则", highlight = true)
                                }
                            }
                            Text(
                                text = "${rule.find}  →  ${rule.replaceWith}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink,
                            )
                            Text(
                                text = "顺序 ${rule.order}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { viewModel.toggleReplacement(rule, it) },
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = rule }) { Text("编辑") }
                        TextButton(onClick = { viewModel.deleteReplacement(rule) }) { Text("删除") }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ReplacementEditorDialog(
            rule = editing,
            onTest = viewModel::testReplacements,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                viewModel.saveReplacement(rule)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun ReplacementEditorDialog(
    rule: ReplacementRule?,
    onTest: (String) -> String,
    onDismiss: () -> Unit,
    onSave: (ReplacementRule) -> Unit,
) {
    var name by remember { mutableStateOf(rule?.name.orEmpty()) }
    var find by remember { mutableStateOf(rule?.find.orEmpty()) }
    var replaceWith by remember { mutableStateOf(rule?.replaceWith.orEmpty()) }
    var isRegex by remember { mutableStateOf(rule?.isRegex ?: false) }
    var order by remember { mutableStateOf((rule?.order ?: 100).toString()) }
    var enabled by remember { mutableStateOf(rule?.enabled ?: true) }
    var testInput by remember { mutableStateOf("") }
    var testOutput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "新增替换" else "编辑替换") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text(if (isRegex) "查找（正则）" else "查找") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replaceWith,
                    onValueChange = { replaceWith = it },
                    label = { Text("替换为") },
                    supportingText = { Text("正则可用 \$1 引用分组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用正则", color = Ink, modifier = Modifier.weight(1f))
                    Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                }
                OutlinedTextField(
                    value = order,
                    onValueChange = { value -> order = value.filter { it.isDigit() }.take(4) },
                    label = { Text("顺序（越小越先执行）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", color = Ink, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                HorizontalDivider()
                OutlinedTextField(
                    value = testInput,
                    onValueChange = { testInput = it },
                    label = { Text("测试文本（用已保存规则）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { testOutput = onTest(testInput) }, enabled = testInput.isNotBlank()) {
                        Text("测试")
                    }
                }
                if (testOutput.isNotBlank()) {
                    Text(
                        text = "结果：$testOutput",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && find.isNotBlank(),
                onClick = {
                    onSave(
                        ReplacementRule(
                            id = rule?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            find = find,
                            replaceWith = replaceWith,
                            isRegex = isRegex,
                            enabled = enabled,
                            order = order.toIntOrNull() ?: 100,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 设置
// ---------------------------------------------------------------------------

@Composable
private fun SettingsPage(
    state: MainUiState,
    viewModel: MainViewModel,
    onOpenSystemSpeechSettings: () -> Unit,
) {
    var license by remember { mutableStateOf("") }
    var endpoint by remember(state.edgeEndpoint) { mutableStateOf(state.edgeEndpoint) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )

        SectionTitle("朗读行为")
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("过滤纯标点片段", color = Ink)
                    Text(
                        text = "像「……」这类没有文字的片段不送去合成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                Switch(
                    checked = state.punctuationFilter,
                    onCheckedChange = viewModel::setPunctuationFilter,
                )
            }
        }
        VoiceField("系统默认音色", state.systemVoiceId, state.voices, viewModel::selectSystemVoice)

        SectionTitle("在线音源")
        SectionCard {
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Edge 端点") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { viewModel.setEdgeEndpoint(endpoint) },
                    enabled = endpoint.isNotBlank() && endpoint != state.edgeEndpoint,
                ) { Text("保存端点") }
            }
        }

        SectionTitle("微软离线许可证")
        SectionCard {
            Text(
                text = if (state.licenseConfigured) {
                    "已配置：${state.licenseMask}"
                } else {
                    "尚未配置。离线音色会显示在应用内，但不可用，也不会暴露给系统 TTS。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.licenseConfigured) Ink else Muted,
            )
            OutlinedTextField(
                value = license,
                onValueChange = { license = it },
                label = { Text("Embedded Speech 许可证") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.licenseConfigured) {
                    TextButton(onClick = { viewModel.setLicense("") }) { Text("清除") }
                }
                TextButton(
                    onClick = {
                        viewModel.setLicense(license)
                        license = ""
                    },
                    enabled = license.isNotBlank(),
                ) { Text("保存许可证") }
            }
        }

        SectionTitle("系统集成")
        SectionCard {
            Text(
                text = "系统音色名：multitts:<包 ID>:<引擎>:<音色>",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            OutlinedButton(onClick = onOpenSystemSpeechSettings) { Text("打开系统语音设置") }
            OutlinedButton(onClick = viewModel::clearRecentText) { Text("清除最近文本") }
        }

        Text(
            text = "首版不支持 JS 插件、自动人物识别、HTTP 转发服务和缓存系统。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}

// ---------------------------------------------------------------------------
// 通用组件
// ---------------------------------------------------------------------------

@Composable
private fun BottomPanel(state: MainUiState, viewModel: MainViewModel) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (state.section == AppSection.WORKBENCH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isSpeaking) {
                        OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
                            Text("停止朗读")
                        }
                    } else {
                        Button(onClick = viewModel::speak, modifier = Modifier.weight(1f)) {
                            Text("开始朗读")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun VoiceField(
    label: String,
    voiceId: String,
    voices: List<VoiceDescriptor>,
    onSelect: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.systemName == voiceId && it.usable }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
                Text(
                    text = selected?.displayName ?: "未选择",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                if (selected != null) {
                    Text(
                        text = voiceSubtitle(selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            }
            TextButton(
                onClick = { showPicker = true },
                enabled = voices.any { it.usable },
            ) { Text("选择") }
        }
    }

    if (showPicker) {
        VoicePickerDialog(
            title = label,
            voices = voices,
            selectedId = voiceId,
            onSelect = {
                onSelect(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun VoicePickerDialog(
    title: String,
    voices: List<VoiceDescriptor>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (voices.isEmpty()) {
                Text("还没有可用音色，请先在「语音包」导入。")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(voices, key = { it.systemName }) { voice ->
                        VoiceOptionRow(
                            voice = voice,
                            selected = voice.systemName == selectedId,
                            onClick = { if (voice.usable) onSelect(voice.systemName) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun VoiceOptionRow(voice: VoiceDescriptor, selected: Boolean, onClick: () -> Unit) {
    val background = when {
        selected -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = background,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = voice.usable, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = voice.displayName,
                    fontWeight = FontWeight.SemiBold,
                    color = if (voice.usable) Ink else Muted,
                    modifier = Modifier.weight(1f),
                )
                Badge(
                    text = voiceStatusLabel(voice),
                    highlight = voice.usable && voice.isOffline,
                )
            }
            Text(
                text = voiceSubtitle(voice),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            voice.unusableReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Text(text = text, fontWeight = FontWeight.SemiBold, color = Ink)
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Badge(text: String, highlight: Boolean = false) {
    val color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(
                color = if (highlight) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun voiceStatusLabel(voice: VoiceDescriptor): String = when {
    voice.usable && voice.isOnline -> "在线可用"
    voice.usable -> "离线可用"
    voice.licenseRequired -> "缺许可证"
    else -> "不可用"
}

private fun voiceSubtitle(voice: VoiceDescriptor): String = buildString {
    append(voice.packName)
    append(" · ").append(voice.engineCode)
    if (voice.localeTag.isNotBlank()) append(" · ").append(voice.localeTag)
    append(if (voice.isOnline) " · 在线" else " · 离线")
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val PACK_BUSY_IMPORT = "import"
