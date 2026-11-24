package app.noveltts.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.noveltts.domain.BuiltInRole
import app.noveltts.domain.ReplacementScope
import app.noveltts.domain.RoleAliases
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
import app.noveltts.ui.common.VoiceField
import app.noveltts.ui.rules.RuleVoicePickerSheet
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import java.util.UUID
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


private const val STEP = 0.05f
private val RATE_RANGE = 0.25f..4f
private val VOLUME_RANGE = 0f..1f
// 面板占屏幕高度的比例：自定义角色字段多，旁白/对话只有音色与三个倍率。
private const val CUSTOM_SHEET_HEIGHT = 0.92f
private const val BUILT_IN_SHEET_HEIGHT = 0.7f

/** Show decoded aliases without exposing their regex storage format. */
internal fun roleMatchLabel(rule: RoleRule): String {
    val aliases = if (rule.matchMode == RoleMatchMode.LITERAL_EXACT) listOf(rule.pattern)
        else RoleAliases.decode(rule.pattern)
    return aliases?.joinToString("、", prefix = "匹配") { "「$it」" } ?: "正则「${rule.pattern}」"
}

// 统一吸附到 0.05 的整数倍，避免浮点累加出现 1.1500001 这类值。
private fun snap(value: Float): Float = (value * 20f).roundToInt() / 20f

private val RoleMatchScope.legacyLabel: String
    get() = when (this) {
        RoleMatchScope.PREVIOUS_NARRATION -> "前一句旁白"
        RoleMatchScope.NEXT_NARRATION -> "后一句旁白"
        RoleMatchScope.SEGMENT -> "当前片段"
        RoleMatchScope.AUTO -> "自动"
    }

/**
 * 角色配置底部弹窗：音色 + 语速/音调/音量倍率；自定义角色另有名称、称呼 / 别名、启用、
 * 高级规则与删除。内容可滚动，底部固定「取消/保存」；保存失败保留输入并显示原因。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // 称呼列表只对「称呼并列」的表达式开放；真正的正则与「文本相等」规则留给高级规则编辑。
    val aliasesInitial = remember {
        existing?.let { if (it.matchMode == RoleMatchMode.LITERAL_EXACT) null else RoleAliases.decode(it.pattern) }
    }
    val aliasEditable = existing == null || aliasesInitial != null

    val initialVoiceIds = remember {
        builtInInitial?.let { listOf(it.voiceId) }?.filter { it.isNotBlank() }
            ?: existing?.voiceIds?.filter { it.isNotBlank() }.orEmpty()
    }
    val initialRate = builtInInitial?.rate ?: existing?.speechRate ?: 1f
    val initialPitch = builtInInitial?.pitch ?: existing?.speechPitch ?: 1f
    val initialVolume = builtInInitial?.volume ?: existing?.speechVolume ?: 1f
    val initialScope = existing?.matchScope ?: RoleMatchScope.AUTO

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var aliases by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(aliasesInitial.orEmpty()) }
    var aliasInput by rememberSaveable { mutableStateOf("") }
    var voiceIds by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(initialVoiceIds) }
    var rate by rememberSaveable { mutableStateOf(initialRate) }
    var pitch by rememberSaveable { mutableStateOf(initialPitch) }
    var volume by rememberSaveable { mutableStateOf(initialVolume) }
    var matchScope by rememberSaveable { mutableStateOf(initialScope) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    // Stable before any write: retrying after rotation updates the same complete role.
    val draftId by rememberSaveable { mutableStateOf(existing?.id ?: UUID.randomUUID().toString()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var managingVoices by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Keep unsaved inputs when dismissing or rotating the sheet.
    val dirty = (name != existing?.name.orEmpty() ||
        aliases != aliasesInitial.orEmpty() || aliasInput.isNotBlank() ||
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
        // 输入框里还没点「添加」的称呼一并算上，避免保存时悄悄丢掉。
        val finalAliases = (aliases + aliasInput.trim()).filter { it.isNotBlank() }.distinct()
        val pattern = when {
            existing != null && (!aliasEditable || finalAliases == aliasesInitial) -> existing.pattern
            else -> RoleAliases.encode(finalAliases.ifEmpty { listOf(trimmedName) })
        }
        val base = existing ?: RoleRule(id = draftId, name = trimmedName, pattern = pattern, voiceIds = voiceIds)
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
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { RuleValidation.roleError(rule) }?.let { return it }
        return viewModel.saveConfiguredRole(rule, isNew = existing == null, expected = existing).exceptionOrNull()
            ?.let { it.message ?: "保存失败，输入已保留。请检查规则文件或可用空间。" }
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
    // M3 把 confirmValueChange 当作 rememberSaveable 的 key：lambda 换一次实例，SheetState 就重建一次、
    // 面板重播入场动画。所以 lambda 只建一次，dirty 通过 rememberUpdatedState 读最新值。
    val latestDirty by rememberUpdatedState(dirty)
    val confirmSheetChange = remember {
        { value: SheetValue ->
            when {
                value != SheetValue.Hidden -> true
                saving -> false
                latestDirty -> { confirmClose = true; false }
                else -> true
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = confirmSheetChange,
    )
    ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheetState) {
        // 高度钉死（同 RuleEditorSheet）：面板高度不再跟着内容走，否则内容高度与面板位置互相影响，
        // 面板会在两个高度之间来回抖。内容多少只影响中间滚动区。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (isCustom) CUSTOM_SHEET_HEIGHT else BUILT_IN_SHEET_HEIGHT)
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
                    .weight(1f)
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
                    if (aliasEditable) {
                        val addAlias = {
                            val alias = aliasInput.trim()
                            if (alias.isNotEmpty() && alias !in aliases) aliases = aliases + alias
                            aliasInput = ""
                        }
                        // 已有角色至少留一个称呼；新建时可以不填，保存时用角色名称。
                        val removable = existing == null || aliases.size > 1
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = "称呼 / 别名", style = MaterialTheme.typography.labelSmall, color = Muted)
                            if (aliases.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    aliases.forEach { alias ->
                                        AliasChip(
                                            alias = alias,
                                            onRemove = if (removable) ({ aliases = aliases - alias }) else null,
                                        )
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = aliasInput,
                                    onValueChange = { aliasInput = it },
                                    label = { Text("添加称呼") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = addAlias, enabled = aliasInput.isNotBlank()) {
                                    AppIconGlyph(AppIcon.ADD, size = 18.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加")
                                }
                            }
                            Text(
                                text = if (existing == null) {
                                    "旁白里用哪些叫法提到这个角色，如「许山」「师尊」；不填则使用角色名称"
                                } else {
                                    "旁白里用哪些叫法提到这个角色，如「许山」「师尊」；至少保留一个"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = existing?.pattern.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("匹配表达式（只读）") },
                            supportingText = {
                                Text(
                                    if (existing.matchMode == RoleMatchMode.LITERAL_EXACT) {
                                        "这条规则使用「文本相等」匹配，请在「高级规则」中修改。"
                                    } else {
                                        "这条规则使用了正则表达式，请在「高级规则」中修改。"
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (matchScope == RoleMatchScope.AUTO) {
                        Text(
                            text = "根据引号前后的旁白自动判断是不是这个角色在说话",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "旧式匹配：" + matchScope.legacyLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                                modifier = Modifier.weight(1f),
                            )
                            // 只改面板内的状态，随「保存」一起写入。
                            if (existing != null && RoleAliases.canSwitchToAuto(existing)) {
                                TextButton(onClick = { matchScope = RoleMatchScope.AUTO }) { Text("改用自动") }
                            }
                        }
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
                        previewError = state.previewError,
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

                val effectiveRate = app.noveltts.engine.api.SynthesisParameters.rate(app.noveltts.engine.api.SynthesisParameters.globalRate(state.speed) * rate * (previewVoice?.defaultSpeed ?: 1f))
                val effectivePitch = app.noveltts.engine.api.SynthesisParameters.pitch(app.noveltts.engine.api.SynthesisParameters.globalPitch(state.pitch) * pitch * (previewVoice?.defaultPitch ?: 1f))
                val effectiveVolume = app.noveltts.engine.api.SynthesisParameters.volume(app.noveltts.engine.api.SynthesisParameters.globalVolume(state.volume) * volume * (previewVoice?.defaultVolume ?: 1f))
                Text("当前有效值：语速 ${formatMultiplier(effectiveRate)} · 音调 ${formatMultiplier(effectivePitch)} · 音量 ${formatVolumePercent(effectiveVolume)}", style = MaterialTheme.typography.labelSmall, color = Muted)
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
                    range = 0.25f..2f,
                    valueText = formatMultiplier(pitch) + if (pitch > 2f) "（历史值；有效上限 2）" else "",
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
                // 固定占两行：试听失败的提示出现或消失时不挤动下面的内容。
                Text(
                    text = state.previewError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

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
                (if (linked > 0) "保留指定给该角色的 $linked 条替换规则，目标缺失期间不生效，可重新绑定。" else "没有指定给该角色的替换规则。") +
                "此操作不可撤销。",
            dangerous = true,
            onConfirm = {
                confirmDelete = false
                saving = true
                error = null
                scope.launch {
                    try {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { viewModel.removeRolePreservingReplacements(existing.id) }
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

/** 一个称呼：文字 + 48dp 的删除触控区；[onRemove] 为 null 时不可删除（最后一个称呼）。 */
@Composable
private fun AliasChip(alias: String, onRemove: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = alias,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = 12.dp, end = if (onRemove == null) 12.dp else 0.dp),
            )
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(role = Role.Button, onClick = onRemove)
                        .semantics { contentDescription = "删除称呼「$alias」" },
                    contentAlignment = Alignment.Center,
                ) {
                    AppIconGlyph(AppIcon.CLOSE, size = 16.dp, tint = Muted)
                }
            }
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
