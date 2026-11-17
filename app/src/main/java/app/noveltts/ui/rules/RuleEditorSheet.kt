package app.noveltts.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SheetValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleMatchMode
import app.noveltts.domain.RoleMatchScope
import app.noveltts.domain.ReplacementScope
import app.noveltts.domain.RuleValidation
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.VoiceSelectionMode
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.FilterChipRow
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import java.util.UUID
import kotlinx.coroutines.launch

/** 编辑目标：新建（rule 为 null）或编辑既有规则；groupId 是新建时的默认分组。 */
internal sealed interface RuleEditorTarget {
    val groupId: String

    data class Role(
        val rule: RoleRule?,
        override val groupId: String = RuleGroup.DEFAULT_ID,
        val draftGroupName: String = "",
        val restoredDraft: Boolean = false,
        val fields: Map<String, String> = emptyMap(),
    ) : RuleEditorTarget

    data class Replacement(
        val rule: ReplacementRule?,
        override val groupId: String = RuleGroup.DEFAULT_ID,
        val draftGroupName: String = "",
        val restoredDraft: Boolean = false,
        val fields: Map<String, String> = emptyMap(),
    ) : RuleEditorTarget
}

internal val RoleMatchMode.label: String
    get() = when (this) {
        RoleMatchMode.REGEX_CONTAINS -> "包含"
        RoleMatchMode.REGEX_PREFIX -> "前缀"
        RoleMatchMode.REGEX_SUFFIX -> "后缀"
        RoleMatchMode.EXACT -> "正则整段"
        RoleMatchMode.LITERAL_EXACT -> "文本相等"
    }

internal val VoiceSelectionMode.label: String
    get() = when (this) {
        VoiceSelectionMode.FIXED -> "固定第一个"
        VoiceSelectionMode.ROUND_ROBIN -> "轮转"
    }

/**
 * 规则编辑底部弹窗：内容可滚动、底部固定保存/取消栏，避免旧 Dialog 在 460dp 高度内被截断。
 * 测试框使用当前草稿跑管线，保存失败时保留编辑内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditorSheet(
    target: RuleEditorTarget,
    groups: List<RuleGroup>,
    voices: List<VoiceDescriptor>,
    roles: List<RoleRule>,
    onDismiss: () -> Unit,
    onSaveRole: suspend (RoleRule, RuleGroup?) -> Boolean,
    onSaveReplacement: suspend (ReplacementRule, RuleGroup?) -> Boolean,
    onTestRole: suspend (String, RoleRule, Boolean) -> String,
    onTestReplacement: suspend (String, ReplacementRule, Boolean) -> String,
    onPreviewVoice: (VoiceDescriptor) -> Unit,
    onStopPreview: () -> Unit,
    onRoleDraftChange: (RoleRule, String, Map<String, String>) -> Unit,
    onReplacementDraftChange: (ReplacementRule, String, Map<String, String>) -> Unit,
    onDiscardDraft: () -> Unit,
) {
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onStopPreview() } }
    var confirmClose by remember { mutableStateOf(false) }
    val requestDismiss = { if (!saving) { if (dirty) confirmClose = true else { onStopPreview(); onDiscardDraft(); onDismiss() } } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && saving) false
            else if (value == SheetValue.Hidden && dirty) { confirmClose = true; false } else true
        })
    ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f).imePadding(),
        ) {
            when (target) {
                is RuleEditorTarget.Role -> RoleEditor(
                    target = target,
                    groups = groups,
                    voices = voices,
                    onDismiss = requestDismiss,
                    onSave = onSaveRole,
                    onTest = onTestRole,
                    onPreviewVoice = onPreviewVoice,
                    onStopPreview = onStopPreview,
                    onDraftChange = onRoleDraftChange,
                    onDirtyChange = { dirty = it },
                    onSavingChange = { saving = it },
                )
                is RuleEditorTarget.Replacement -> ReplacementEditor(
                    target = target,
                    groups = groups,
                    roles = roles,
                    onDismiss = requestDismiss,
                    onSave = onSaveReplacement,
                    onTest = onTestReplacement,
                    onDraftChange = onReplacementDraftChange,
                    onDirtyChange = { dirty = it },
                    onSavingChange = { saving = it },
                )
            }
        }
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false }, title = { Text("保留未保存的修改？") },
        text = { Text("草稿已保存，可以下次继续编辑。也可以放弃这次修改。") },
        confirmButton = { TextButton(onClick = { onStopPreview(); onDismiss() }) { Text("保留草稿并关闭") } },
        dismissButton = { TextButton(onClick = { onDiscardDraft(); onStopPreview(); onDismiss() }) { Text("放弃修改") } })
}

@Composable
private fun RoleEditor(
    target: RuleEditorTarget.Role,
    groups: List<RuleGroup>,
    voices: List<VoiceDescriptor>,
    onDismiss: () -> Unit,
    onSave: suspend (RoleRule, RuleGroup?) -> Boolean,
    onTest: suspend (String, RoleRule, Boolean) -> String,
    onPreviewVoice: (VoiceDescriptor) -> Unit,
    onStopPreview: () -> Unit,
    onDraftChange: (RoleRule, String, Map<String, String>) -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSavingChange: (Boolean) -> Unit,
) {
    val existing = target.rule
    var name by rememberSaveable { mutableStateOf(target.fields["name"] ?: existing?.name.orEmpty()) }
    var pattern by rememberSaveable { mutableStateOf(target.fields["pattern"] ?: existing?.pattern.orEmpty()) }
    var matchMode by rememberSaveable { mutableStateOf(existing?.matchMode ?: RoleMatchMode.REGEX_CONTAINS) }
    var groupId by rememberSaveable { mutableStateOf(existing?.groupId ?: target.groupId) }
    var newGroupName by rememberSaveable { mutableStateOf(target.draftGroupName) }
    var voiceIds by rememberSaveable { mutableStateOf(existing?.voiceIds?.filter { it.isNotBlank() }.orEmpty()) }
    var selectionMode by rememberSaveable {
        mutableStateOf(existing?.voiceSelectionMode ?: VoiceSelectionMode.FIXED)
    }
    var priority by rememberSaveable { mutableStateOf(target.fields["priority"] ?: (existing?.priority ?: 100).toString()) }
    var onlyDialogue by rememberSaveable { mutableStateOf(existing?.onlyDialogue ?: true) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var testInput by rememberSaveable { mutableStateOf("") }
    var testOutput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testAll by rememberSaveable { mutableStateOf(false) }
    var matchScope by rememberSaveable { mutableStateOf(existing?.matchScope ?: RoleMatchScope.SEGMENT) }
    var contextChars by rememberSaveable { mutableStateOf(target.fields["contextChars"] ?: (existing?.contextChars ?: 200).toString()) }
    var speechRate by rememberSaveable { mutableStateOf(target.fields["speechRate"] ?: (existing?.speechRate ?: 1f).toString()) }
    var speechPitch by rememberSaveable { mutableStateOf(target.fields["speechPitch"] ?: (existing?.speechPitch ?: 1f).toString()) }
    var speechVolume by rememberSaveable { mutableStateOf(target.fields["speechVolume"] ?: (existing?.speechVolume ?: 1f).toString()) }
    val fields = mapOf("name" to name, "pattern" to pattern, "priority" to priority, "contextChars" to contextChars,
        "speechRate" to speechRate, "speechPitch" to speechPitch, "speechVolume" to speechVolume)
    val initialFields = remember { fields }
    val base = remember { existing ?: RoleRule(name = "", pattern = "", voiceIds = emptyList(), groupId = target.groupId) }
    val draft = base.copy(name = name.trim(), pattern = pattern.trim(), voiceIds = voiceIds,
        priority = priority.toIntOrNull() ?: 100, onlyDialogue = onlyDialogue, enabled = enabled,
        groupId = groupId, matchMode = matchMode, voiceSelectionMode = selectionMode, matchScope = matchScope,
        contextChars = contextChars.toIntOrNull()?.coerceIn(1, 2000) ?: 200,
        speechRate = speechRate.toFloatOrNull() ?: 1f, speechPitch = speechPitch.toFloatOrNull() ?: 1f,
        speechVolume = speechVolume.toFloatOrNull() ?: 1f)
    LaunchedEffect(draft, newGroupName, fields) {
        onDraftChange(draft, newGroupName, fields)
        onDirtyChange(target.restoredDraft || draft != base || newGroupName != target.draftGroupName || fields != initialFields)
    }

    val voiceNames = remember(voices) { voices.associate { it.systemName to it.displayName } }
    val regexError = remember(pattern, matchMode) {
        if (matchMode == RoleMatchMode.LITERAL_EXACT || pattern.isBlank()) {
            null
        } else {
            runCatching { Regex(pattern) }.exceptionOrNull()?.let { "正则无效：" + it.message }
        }
    }
    val canSave = RuleValidation.roleError(draft) == null && !saving &&
        speechRate.toFloatOrNull()?.let { it.isFinite() && it in 0.25f..4f } == true &&
        speechPitch.toFloatOrNull()?.let { it.isFinite() && it in 0.25f..4f } == true &&
        speechVolume.toFloatOrNull()?.let { it.isFinite() && it in 0f..1f } == true

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (existing == null) "新增角色规则" else "编辑角色规则",
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            GroupSection(groups = groups, selectedId = groupId, onSelect = { groupId = it })
            OutlinedTextField(
                value = newGroupName,
                onValueChange = { newGroupName = it.take(40) },
                label = { Text("或新建分组（可选）") },
                leadingIcon = { AppIconGlyph(AppIcon.ADD, size = 18.dp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text(if (matchMode == RoleMatchMode.LITERAL_EXACT) "匹配文本" else "匹配表达式（正则）") },
                supportingText = {
                    Text(regexError ?: "例如 林黛玉|黛玉；匹配范围由下方模式决定", color = if (regexError != null) Accent else Muted)
                },
                isError = regexError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "匹配模式", style = MaterialTheme.typography.labelSmall, color = Muted)
                FilterChipRow(
                    options = RoleMatchMode.entries.map { it to it.label },
                    selected = matchMode,
                    onSelect = { matchMode = it },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "音色（多选，顺序即轮转顺序）",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { picking = true }) {
                        AppIconGlyph(AppIcon.ADD, size = 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("添加音色")
                    }
                }
                if (voiceIds.isEmpty()) {
                    Text(
                        text = "还没有选择音色，至少选一个才能保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent,
                    )
                }
                voiceIds.forEachIndexed { index, voiceId ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge(text = (index + 1).toString(), highlight = index == 0)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = voiceNames[voiceId] ?: voiceId,
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = index > 0,
                                onClick = {
                                    voiceIds = voiceIds.toMutableList().also {
                                        val item = it.removeAt(index)
                                        it.add(index - 1, item)
                                    }
                                },
                            ) { Text("上移") }
                            TextButton(
                                enabled = index < voiceIds.lastIndex,
                                onClick = {
                                    voiceIds = voiceIds.toMutableList().also {
                                        val item = it.removeAt(index)
                                        it.add(index + 1, item)
                                    }
                                },
                            ) { Text("下移") }
                            TextButton(onClick = { voiceIds = voiceIds.filterNot { it == voiceId } }) {
                                AppIconGlyph(AppIcon.CLOSE, size = 16.dp)
                                Spacer(Modifier.width(2.dp))
                                Text("移除")
                            }
                        }
                    }
                }
                FilterChipRow(
                    options = VoiceSelectionMode.entries.map { it to it.label },
                    selected = selectionMode,
                    onSelect = { selectionMode = it },
                )
                if (selectionMode == VoiceSelectionMode.ROUND_ROBIN && voiceIds.size < 2) {
                    Text(
                        text = "轮转至少需要 2 个音色，只有一个时会按固定音色播放。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            }
            LabeledSwitch("仅匹配对话", onlyDialogue) { onlyDialogue = it }
            ChoiceField("匹配范围", matchScope.name, listOf(
                RoleMatchScope.SEGMENT.name to "当前片段", RoleMatchScope.PREVIOUS_NARRATION.name to "前一句旁白",
                RoleMatchScope.NEXT_NARRATION.name to "后一句旁白")) { matchScope = RoleMatchScope.valueOf(it) }
            if (matchScope != RoleMatchScope.SEGMENT) OutlinedTextField(value = contextChars,
                onValueChange = { contextChars = it.filter(Char::isDigit).take(4) },
                label = { Text("上下文长度（1–2000 字）") }, modifier = Modifier.fillMaxWidth())
            LabeledSwitch("启用", enabled) { enabled = it }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级" else "高级") }
            if (advanced) {
                OutlinedTextField(value = speechRate, onValueChange = { speechRate = it },
                    label = { Text("角色语速倍率（0.25–4）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = speechPitch, onValueChange = { speechPitch = it },
                    label = { Text("角色音调倍率（0.25–4）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = speechVolume, onValueChange = { speechVolume = it },
                    label = { Text("角色音量倍率（0–1）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = priority,
                    onValueChange = { value -> priority = value.filter { it.isDigit() }.take(4) },
                    label = { Text("优先级（数字越大越先匹配）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            OutlinedTextField(
                value = testInput,
                onValueChange = { testInput = it },
                label = { Text("测试文本（当前草稿，可包含引号和旁白）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { scope.launch { testing = true
                    testOutput = runCatching { onTest(testInput, draft, testAll) }.getOrElse { it.message.orEmpty() }
                    testing = false } }, enabled = testInput.isNotBlank() && !testing) {
                    AppIconGlyph(AppIcon.PLAY, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(if (testing) "测试中…" else "测试草稿")
                }
            }
            if (testOutput.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = testOutput,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            LabeledSwitch("放入全部规则后测试", testAll) { testAll = it }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.heightIn(min = 4.dp))
        }
        EditorFooter(
            canSave = canSave,
            saving = saving,
            onCancel = onDismiss,
            onSave = {
                val trimmedGroup = newGroupName.trim()
                val newGroup = if (trimmedGroup.isEmpty()) null else RuleGroup(name = trimmedGroup)
                saving = true; onSavingChange(true); saveError = null
                scope.launch { try {
                    val result = runCatching { onSave(draft.copy(groupId = newGroup?.id ?: groupId), newGroup) }
                    if (result.getOrDefault(false).not()) saveError = result.exceptionOrNull()?.message ?: "保存失败，草稿已保留。请检查规则文件或可用空间。"
                } finally { saving = false; onSavingChange(false) } }
            },
        )
    }

    if (picking) {
        RuleVoicePickerSheet(
            title = "角色音色（多选）",
            voices = voices,
            selectedIds = voiceIds,
            onConfirm = { voiceIds = it },
            onDismiss = { onStopPreview(); picking = false },
            onPreview = onPreviewVoice,
            onStopPreview = onStopPreview,
        )
    }
}

@Composable
private fun ReplacementEditor(
    target: RuleEditorTarget.Replacement,
    groups: List<RuleGroup>,
    roles: List<RoleRule>,
    onDismiss: () -> Unit,
    onSave: suspend (ReplacementRule, RuleGroup?) -> Boolean,
    onTest: suspend (String, ReplacementRule, Boolean) -> String,
    onDraftChange: (ReplacementRule, String, Map<String, String>) -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onSavingChange: (Boolean) -> Unit,
) {
    val existing = target.rule
    var name by rememberSaveable { mutableStateOf(target.fields["name"] ?: existing?.name.orEmpty()) }
    var find by rememberSaveable { mutableStateOf(target.fields["find"] ?: existing?.find.orEmpty()) }
    var replaceWith by rememberSaveable { mutableStateOf(target.fields["replaceWith"] ?: existing?.replaceWith.orEmpty()) }
    var isRegex by rememberSaveable { mutableStateOf(existing?.isRegex ?: false) }
    var groupId by rememberSaveable { mutableStateOf(existing?.groupId ?: target.groupId) }
    var newGroupName by rememberSaveable { mutableStateOf(target.draftGroupName) }
    var sortIndex by rememberSaveable { mutableStateOf(target.fields["sortIndex"] ?: (existing?.sortIndex ?: 0).toString()) }
    val fields = mapOf("name" to name, "find" to find, "replaceWith" to replaceWith, "sortIndex" to sortIndex)
    val initialFields = remember { fields }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var testInput by rememberSaveable { mutableStateOf("") }
    var testOutput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testAll by rememberSaveable { mutableStateOf(false) }
    var replacementScope by rememberSaveable { mutableStateOf(existing?.scope ?: ReplacementScope.ALL) }
    var targetRoleId by rememberSaveable { mutableStateOf(existing?.targetRoleId.orEmpty()) }
    val base = remember { existing ?: ReplacementRule(name = "", find = "", replaceWith = "", groupId = target.groupId) }
    val draft = base.copy(name = name.trim(), find = find, replaceWith = replaceWith, isRegex = isRegex,
        enabled = enabled, groupId = groupId, sortIndex = sortIndex.toIntOrNull() ?: 0,
        scope = replacementScope, targetRoleId = targetRoleId)
    LaunchedEffect(draft, newGroupName, fields) {
        onDraftChange(draft, newGroupName, fields)
        onDirtyChange(target.restoredDraft || draft != base || newGroupName != target.draftGroupName || fields != initialFields)
    }

    val regexError = remember(find, isRegex, replaceWith) {
        if (!isRegex || find.isEmpty()) {
            null
        } else {
            RuleValidation.replacementError(draft)
        }
    }
    val canSave = name.isNotBlank() && find.isNotEmpty() && regexError == null && !saving &&
        (replacementScope != ReplacementScope.ROLE || roles.any { it.id == targetRoleId })

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (existing == null) "新增替换规则" else "编辑替换规则",
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            GroupSection(groups = groups, selectedId = groupId, onSelect = { groupId = it })
            OutlinedTextField(
                value = newGroupName,
                onValueChange = { newGroupName = it.take(40) },
                label = { Text("或新建分组（可选）") },
                leadingIcon = { AppIconGlyph(AppIcon.ADD, size = 18.dp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = find,
                onValueChange = { find = it },
                label = { Text(if (isRegex) "查找（正则）" else "查找") },
                supportingText = { Text(regexError ?: "与正文逐字匹配，可用正则分组引用", color = if (regexError != null) Accent else Muted) },
                isError = regexError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = replaceWith,
                onValueChange = { replaceWith = it },
                label = { Text("替换为") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledSwitch("使用正则", isRegex) { isRegex = it }
            ChoiceField("作用范围", replacementScope.name, listOf(ReplacementScope.ALL.name to "全文",
                ReplacementScope.NARRATION.name to "旁白", ReplacementScope.DIALOGUE.name to "对话",
                ReplacementScope.ROLE.name to "指定角色")) { replacementScope = ReplacementScope.valueOf(it) }
            if (replacementScope == ReplacementScope.ROLE) {
                ChoiceField("目标角色", targetRoleId, roles.map { it.id to (it.name + if (it.enabled) "" else "（已停用）") }) { targetRoleId = it }
                if (roles.isEmpty()) Text("请先在角色页建立角色规则，再选择作用对象。", style = MaterialTheme.typography.labelSmall, color = Muted)
                else if (roles.none { it.id == targetRoleId }) Text("请选择一个现有角色。", style = MaterialTheme.typography.labelSmall, color = Accent)
            }
            OutlinedTextField(
                value = sortIndex,
                onValueChange = { value -> sortIndex = value.filter { it.isDigit() }.take(4) },
                label = { Text("顺序（组内越小越先执行）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledSwitch("启用", enabled) { enabled = it }
            HorizontalDivider()
            OutlinedTextField(
                value = testInput,
                onValueChange = { testInput = it },
                label = { Text("测试文本（当前草稿）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { scope.launch { testing = true
                    testOutput = runCatching { onTest(testInput, draft, testAll) }.getOrElse { it.message.orEmpty() }
                    testing = false } }, enabled = testInput.isNotBlank() && !testing) {
                    AppIconGlyph(AppIcon.PLAY, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(if (testing) "测试中…" else "测试草稿")
                }
            }
            if (testOutput.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = testOutput,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            LabeledSwitch("放入全部规则后测试", testAll) { testAll = it }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.heightIn(min = 4.dp))
        }
        EditorFooter(
            canSave = canSave,
            saving = saving,
            onCancel = onDismiss,
            onSave = {
                val trimmedGroup = newGroupName.trim()
                val newGroup = if (trimmedGroup.isEmpty()) null else RuleGroup(name = trimmedGroup)
                saving = true; onSavingChange(true); saveError = null
                scope.launch { try {
                    val result = runCatching { onSave(draft.copy(groupId = newGroup?.id ?: groupId), newGroup) }
                    if (result.getOrDefault(false).not()) saveError = result.exceptionOrNull()?.message ?: "保存失败，草稿已保留。请检查规则文件或可用空间。"
                } finally { saving = false; onSavingChange(false) } }
            },
        )
    }
}

@Composable
private fun GroupSection(
    groups: List<RuleGroup>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = groups.firstOrNull { it.id == selectedId }?.name ?: RuleGroup.DEFAULT.name
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIconGlyph(AppIcon.FOLDER, size = 18.dp, tint = Muted)
        Spacer(Modifier.width(6.dp))
        Text(text = "分组", style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(current)
                Spacer(Modifier.width(4.dp))
                AppIconGlyph(AppIcon.CHEVRON_DOWN, size = 18.dp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                groups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(if (group.enabled) group.name else group.name + "（已停用）") },
                        onClick = {
                            expanded = false
                            onSelect(group.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceField(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Ink)
        Box {
            TextButton(onClick = { expanded = true }, enabled = options.isNotEmpty()) { Text(options.firstOrNull { it.first == selected }?.second ?: "请选择") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
            }
        }
    }
}

@Composable
private fun EditorFooter(
    canSave: Boolean,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, enabled = !saving) { Text("取消") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSave, enabled = canSave) {
            AppIconGlyph(AppIcon.CHECK, size = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(if (saving) "正在保存…" else "保存")
        }
    }
}
@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

