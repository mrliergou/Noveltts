package app.noveltts.ui.workbench

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.domain.BuiltInRole
import app.noveltts.domain.ReplacementScope
import app.noveltts.domain.RoleMatchMode
import app.noveltts.domain.RoleMatchScope
import app.noveltts.domain.RolePlaybackSettings
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleValidation
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.FilterChipRow
import app.noveltts.ui.common.VoiceField
import app.noveltts.ui.rules.RuleVoicePickerSheet
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 配置面板的对象：内置角色、既有自定义角色，或新增角色。 */
internal sealed interface RoleSheetTarget {
    data class BuiltIn(val role: BuiltInRole) : RoleSheetTarget
    data class Custom(val roleId: String) : RoleSheetTarget
    object New : RoleSheetTarget

    companion object {
        /** 旋转屏幕等重建后恢复打开的面板。 */
        val Saver = Saver<RoleSheetTarget?, String>(
            save = { target ->
                when (target) {
                    is BuiltIn -> "builtin:" + target.role.name
                    is Custom -> "custom:" + target.roleId
                    New -> "new"
                    null -> null
                }
            },
            restore = { saved ->
                when {
                    saved == "new" -> New
                    saved.startsWith("builtin:") -> BuiltIn(BuiltInRole.valueOf(saved.removePrefix("builtin:")))
                    else -> Custom(saved.removePrefix("custom:"))
                }
            },
        )
    }
}

private val StringListSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

/** 与 MainViewModel.addQuickRole 相同的规则：纯文本原样保存，含正则符号才转义。 */
private fun quickPattern(word: String): String =
    if (word.any { it in REGEX_META }) Regex.escape(word) else word

private const val REGEX_META = "\\^\$.|?*+()[]{}"
private const val STEP = 0.05f
private val RATE_RANGE = 0.5f..2f
private val VOLUME_RANGE = 0f..1f

/**
 * 把角色规则的匹配表达式还原成普通匹配词；表达式不是纯文本或 Regex.escape（\Q…\E）形式时返回 null，
 * 这类规则只能在高级规则里改，避免简化面板把正则改坏。
 */
internal fun literalKeyword(rule: RoleRule): String? {
    val pattern = rule.pattern
    if (rule.matchMode == RoleMatchMode.LITERAL_EXACT) return pattern
    if (pattern.length >= 4 && pattern.startsWith("\\Q") && pattern.endsWith("\\E")) {
        return pattern.substring(2, pattern.length - 2).takeIf { !it.contains("\\E") }
    }
    return pattern.takeIf { text -> text.none { it in REGEX_META } }
}

// 统一吸附到 0.05 的整数倍，避免浮点累加出现 1.1500001 这类值。
private fun snap(value: Float): Float = (value * 20f).roundToInt() / 20f

/**
 * 角色配置底部弹窗：音色 + 语速/音调/音量倍率；自定义角色另有名称、匹配词、匹配位置、启用、
 * 高级规则与删除。内容可滚动，底部固定「取消/保存」；保存失败保留输入并显示原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoleConfigSheet(
    target: RoleSheetTarget,
    state: MainUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onOpenAdvanced: (RoleRule) -> Unit,
) {
    // 打开时取一次快照作为编辑基线，之后的界面状态刷新不覆盖正在编辑的内容。
    val existing = remember {
        (target as? RoleSheetTarget.Custom)?.let { custom -> state.roles.firstOrNull { it.id == custom.roleId } }
    }
    if (target is RoleSheetTarget.Custom && existing == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val builtIn = (target as? RoleSheetTarget.BuiltIn)?.role
    val builtInInitial = remember {
        when (builtIn) {
            BuiltInRole.NARRATOR -> state.builtInRoles.narrator
            BuiltInRole.DIALOGUE -> state.builtInRoles.dialogue
            null -> null
        }
    }
    val isCustom = builtIn == null
    val keywordInitial = remember { existing?.let(::literalKeyword) }
    val keywordEditable = existing == null || keywordInitial != null

    val initialVoiceIds = remember {
        builtInInitial?.let { listOf(it.voiceId) }?.filter { it.isNotBlank() }
            ?: existing?.voiceIds?.filter { it.isNotBlank() }.orEmpty()
    }
    val initialRate = builtInInitial?.rate ?: existing?.speechRate ?: 1f
    val initialPitch = builtInInitial?.pitch ?: existing?.speechPitch ?: 1f
    val initialVolume = builtInInitial?.volume ?: existing?.speechVolume ?: 1f
    val initialScope = existing?.matchScope ?: RoleMatchScope.PREVIOUS_NARRATION

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var keyword by rememberSaveable { mutableStateOf(keywordInitial.orEmpty()) }
    var voiceIds by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(initialVoiceIds) }
    var rate by rememberSaveable { mutableStateOf(initialRate) }
    var pitch by rememberSaveable { mutableStateOf(initialPitch) }
    var volume by rememberSaveable { mutableStateOf(initialVolume) }
    var matchScope by rememberSaveable { mutableStateOf(initialScope) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    // 新增流程分两步（先建角色、再写倍率等），第一步成功后记住结果，重试时不再重复新建。
    var createdId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var managingVoices by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = createdId == null && (name != existing?.name.orEmpty() || keyword != keywordInitial.orEmpty() ||
        voiceIds != initialVoiceIds || rate != initialRate || pitch != initialPitch || volume != initialVolume ||
        matchScope != initialScope || enabled != (existing?.enabled ?: true))

    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }

    val voiceNames = remember(state.voices) { state.voices.associate { it.systemName to it.displayName } }
    val previewVoice = state.voices.firstOrNull { it.systemName == voiceIds.firstOrNull() && it.usable }
    val previewing = previewVoice != null && state.previewVoiceId == previewVoice.systemName
    val rulesEditable = state.rolesLoadError == null
    val canSave = !saving && (!isCustom || (rulesEditable && name.isNotBlank() && voiceIds.isNotEmpty()))

    suspend fun saveCustom(): String? {
        val trimmedName = name.trim()
        val word = keyword.trim().ifBlank { trimmedName }
        val created = createdId?.let { id -> viewModel.uiState.value.roles.firstOrNull { it.id == id } }
        val base = existing ?: created ?: runCatching {
            withContext(Dispatchers.IO) { viewModel.addQuickRole(trimmedName, keyword.trim(), voiceIds.first()) }
        }.getOrElse { Result.failure<RoleRule>(it) }
            .getOrElse { return it.message ?: "添加角色失败，请重试。" }
            .also { createdId = it.id }
        val pattern = when {
            existing != null && (!keywordEditable || keyword.trim() == keywordInitial) -> existing.pattern
            word == literalKeyword(base) -> base.pattern
            base.matchMode == RoleMatchMode.LITERAL_EXACT -> word
            else -> quickPattern(word)
        }
        val rule = base.copy(
            name = trimmedName,
            pattern = pattern,
            voiceIds = voiceIds,
            matchScope = matchScope,
            enabled = enabled,
            speechRate = rate,
            speechPitch = pitch,
            speechVolume = volume,
        )
        if (existing == null && rule == base) return null
        RuleValidation.roleError(rule)?.let { return it }
        val saved = runCatching { withContext(Dispatchers.IO) { viewModel.saveRole(rule) } }.getOrDefault(false)
        return when {
            saved -> null
            existing == null -> "角色已添加，但倍率或匹配位置未能保存。可再点「保存」重试。"
            else -> "保存失败，输入已保留。请检查规则文件或可用空间。"
        }
    }

    val save: () -> Unit = {
        saving = true
        error = null
        scope.launch {
            try {
                val failure = if (builtIn != null) {
                    // 音色没被改动时只写倍率：界面显示的可能是原音色不可用时的回落值。
                    runCatching {
                        if (voiceIds == initialVoiceIds) viewModel.updateBuiltInMultipliers(builtIn, rate, pitch, volume)
                        else viewModel.updateBuiltInRole(
                            builtIn,
                            RolePlaybackSettings(voiceIds.firstOrNull().orEmpty(), rate, pitch, volume),
                        )
                    }.exceptionOrNull()?.let { it.message ?: "保存失败，请重试。" }
                } else {
                    saveCustom()
                }
                if (failure == null) onDismiss() else error = failure
            } finally {
                saving = false
            }
        }
    }

    val requestDismiss: () -> Unit = { if (!saving) { if (dirty) confirmClose = true else onDismiss() } }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            when {
                value != SheetValue.Hidden -> true
                saving -> false
                dirty -> { confirmClose = true; false }
                else -> true
            }
        },
    )
    ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Text(
                text = when {
                    builtIn == BuiltInRole.NARRATOR -> "旁白设置"
                    builtIn == BuiltInRole.DIALOGUE -> "对话设置"
                    existing == null -> "添加角色"
                    else -> "角色设置"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isCustom) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        label = { Text("角色名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (keywordEditable) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = { Text("匹配词") },
                            supportingText = { Text("在匹配位置出现这个词时使用该角色；留空则使用角色名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = existing?.pattern.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("匹配表达式（只读）") },
                            supportingText = { Text("这条规则使用了正则表达式，请在「高级规则」中修改。") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "匹配位置", style = MaterialTheme.typography.labelSmall, color = Muted)
                        FilterChipRow(
                            options = listOf(
                                RoleMatchScope.PREVIOUS_NARRATION to "前一句旁白",
                                RoleMatchScope.NEXT_NARRATION to "后一句旁白",
                                RoleMatchScope.SEGMENT to "当前片段",
                            ),
                            selected = matchScope,
                            onSelect = { matchScope = it },
                        )
                    }
                }

                if (voiceIds.size <= 1) {
                    VoiceField(
                        label = "音色",
                        voiceId = voiceIds.firstOrNull().orEmpty(),
                        voices = state.voices,
                        onSelect = { voiceIds = listOf(it) },
                        onPreview = { voice -> viewModel.previewVoice(voice, rate, pitch, volume) },
                        onStopPreview = viewModel::stopPreview,
                        previewVoiceId = state.previewVoiceId,
                        previewStatus = state.statusMessage,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "轮转音色（${voiceIds.size} 个，按顺序）",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            voiceIds.forEachIndexed { index, id ->
                                Badge(text = voiceNames[id] ?: "音色不可用", highlight = index == 0)
                            }
                        }
                        TextButton(onClick = { managingVoices = true }) {
                            AppIconGlyph(AppIcon.VOICE, size = 18.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("管理轮转音色")
                        }
                    }
                }

                MultiplierRow(
                    icon = AppIcon.SPEED,
                    label = "语速",
                    value = rate,
                    range = RATE_RANGE,
                    valueText = formatMultiplier(rate),
                    stateText = "语速 ${formatMultiplier(rate).removeSuffix("×")} 倍",
                    stepText = "0.05",
                    onChange = { rate = it },
                )
                MultiplierRow(
                    icon = AppIcon.TUNE,
                    label = "音调",
                    value = pitch,
                    range = RATE_RANGE,
                    valueText = formatMultiplier(pitch),
                    stateText = "音调 ${formatMultiplier(pitch).removeSuffix("×")} 倍",
                    stepText = "0.05",
                    onChange = { pitch = it },
                )
                MultiplierRow(
                    icon = AppIcon.VOLUME,
                    label = "音量",
                    value = volume,
                    range = VOLUME_RANGE,
                    valueText = formatVolumePercent(volume),
                    stateText = "音量 ${formatVolumePercent(volume)}",
                    stepText = "5%",
                    onChange = { volume = it },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { rate = 1f; pitch = 1f; volume = 1f },
                        enabled = rate != 1f || pitch != 1f || volume != 1f,
                    ) {
                        AppIconGlyph(AppIcon.REFRESH, size = 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (previewing) {
                                viewModel.stopPreview()
                            } else {
                                previewVoice?.let { viewModel.previewVoice(it, rate, pitch, volume) }
                            }
                        },
                        enabled = previewVoice != null,
                    ) {
                        AppIconGlyph(if (previewing) AppIcon.STOP else AppIcon.PLAY, size = 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(if (previewing) "停止试听" else "试听")
                    }
                }
                if (state.previewVoiceId == null && state.statusMessage.startsWith("试听失败")) {
                    Text(text = state.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (isCustom) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = enabled, role = Role.Switch, onValueChange = { enabled = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "启用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                }
                if (existing != null) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpenAdvanced(existing) }, enabled = !saving && rulesEditable) {
                            AppIconGlyph(AppIcon.EDIT, size = 18.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("高级规则…")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = true }, enabled = !saving && rulesEditable) {
                            AppIconGlyph(AppIcon.DELETE, size = 18.dp, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("删除角色", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        text = "打开高级规则会放弃本面板中尚未保存的修改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                error?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.heightIn(min = 4.dp))
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = requestDismiss, enabled = !saving) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = save, enabled = canSave) {
                    AppIconGlyph(AppIcon.CHECK, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(if (saving) "正在保存…" else "保存")
                }
            }
        }
    }

    if (managingVoices) {
        RuleVoicePickerSheet(
            title = "轮转音色（多选）",
            voices = state.voices,
            selectedIds = voiceIds,
            onConfirm = { voiceIds = it },
            onDismiss = { viewModel.stopPreview(); managingVoices = false },
            onPreview = { voice -> viewModel.previewVoice(voice, rate, pitch, volume) },
            onStopPreview = viewModel::stopPreview,
        )
    }

    if (confirmClose) {
        ConfirmDialog(
            title = "放弃修改？",
            message = "尚未保存的修改将丢失。",
            confirmLabel = "放弃",
            dangerous = true,
            onConfirm = { confirmClose = false; onDismiss() },
            onDismiss = { confirmClose = false },
        )
    }

    if (confirmDelete && existing != null) {
        val linked = state.replacements.count {
            it.scope == ReplacementScope.ROLE && it.targetRoleId == existing.id
        }
        ConfirmDialog(
            title = "删除角色",
            message = "确定删除「${existing.name}」？" +
                (if (linked > 0) "指定给该角色的 $linked 条替换规则会一并删除。" else "没有指定给该角色的替换规则。") +
                "此操作不可撤销。",
            dangerous = true,
            onConfirm = {
                confirmDelete = false
                saving = true
                error = null
                scope.launch {
                    try {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { viewModel.removeRoleWithReplacements(existing.id) }
                        }.getOrElse { Result.failure<String>(it) }
                        result.fold(
                            onSuccess = { onDismiss() },
                            onFailure = { error = it.message ?: "删除失败，角色与替换规则均未改动。" },
                        )
                    } finally {
                        saving = false
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * 倍率调节行：滑块 + 两侧 −/+ 微调。已保存的值超出滑块范围时滑块停在端点、数值原样显示，
 * 只有用户实际拖动或点按才会改写。
 */
@Composable
private fun MultiplierRow(
    icon: AppIcon,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    stateText: String,
    stepText: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconGlyph(icon, size = 18.dp, tint = Muted)
            Spacer(Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            Text(text = valueText, style = MaterialTheme.typography.labelLarge, color = Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(
                symbol = "−",
                description = "${label}减少 $stepText",
                enabled = value > range.start,
                onClick = { onChange(snap(value - STEP).coerceIn(range)) },
            )
            Slider(
                value = value.coerceIn(range),
                onValueChange = { onChange(snap(it).coerceIn(range)) },
                valueRange = range,
                modifier = Modifier
                    .weight(1f)
                    .semantics { stateDescription = stateText },
            )
            StepButton(
                symbol = "+",
                description = "${label}增加 $stepText",
                enabled = value < range.endInclusive,
                onClick = { onChange(snap(value + STEP).coerceIn(range)) },
            )
        }
        if (value !in range) {
            Text(
                text = "当前值超出滑块范围，不调整则保持原值。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description },
    ) {
        // 符号只是视觉标记，读屏只念上面的中文描述。
        Text(text = symbol, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
    }
}
