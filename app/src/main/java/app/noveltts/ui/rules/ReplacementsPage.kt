package app.noveltts.ui.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.config.RulesImportApplyResult
import app.noveltts.data.config.RulesImportPreview
import app.noveltts.data.config.RulesKind
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RuleGroup
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect

/** 分组弹窗状态：group 为 null 表示新建。 */
private data class ReplacementGroupDialogState(val group: RuleGroup?)

@Composable
fun ReplacementsPage(state: MainUiState, viewModel: MainViewModel) {
    val editable = state.replacementsLoadError == null
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(RulesFilter.ALL) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var editor by remember { mutableStateOf<RuleEditorTarget?>(viewModel.replacementDraft()?.let { RuleEditorTarget.Replacement(it.rule, it.rule.groupId, it.newGroupName, true, it.fields, it.saveBaseline) }) }
    var groupDialog by remember { mutableStateOf<ReplacementGroupDialogState?>(null) }
    var pendingGroupDelete by remember { mutableStateOf<RuleGroup?>(null) }
    var pendingDelete by remember { mutableStateOf<ReplacementRule?>(null) }
    var pendingImport by remember { mutableStateOf<RulesImportPreview<ReplacementRule>?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.prepareReplacementImport(uri).fold(
                    onSuccess = { pendingImport = it },
                    onFailure = { importError = it.message ?: "导入失败，文件可能不是有效的规则 JSON" },
                )
            }
        }
    }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch { viewModel.exportRulesTo(uri, RulesKind.REPLACEMENT) }
            }
        }

    val groups = remember(state.replacementGroups) { sortedRuleGroups(state.replacementGroups) }
    val controller = remember(viewModel) {
        RuleDragController { ruleId, groupId, index -> viewModel.moveReplacement(ruleId, groupId, index) }
    }
    val listState = rememberLazyListState()
    val groupNames = remember(groups) { groups.associate { it.id to it.name } }
    val needle = query.trim()
    val visibleReplacements = state.replacements.filter { rule ->
        val byFilter = when (filter) {
            RulesFilter.ALL -> true
            RulesFilter.ENABLED -> rule.enabled
            RulesFilter.DISABLED -> !rule.enabled
        }
        val byQuery = needle.isEmpty() ||
            rule.name.contains(needle, ignoreCase = true) ||
            rule.find.contains(needle, ignoreCase = true) ||
            rule.replaceWith.contains(needle, ignoreCase = true) ||
            (groupNames[rule.groupId] ?: "").contains(needle, ignoreCase = true)
        byFilter && byQuery
    }

    RulesPageScaffold(
        title = "替换规则",
        description = "长按条目拖拽排序；规则按分组顺序与组内顺序依次应用。",
        listState = listState,
        dragController = controller,
        loadError = state.replacementsLoadError,
        query = query,
        onQueryChange = { query = it },
        filter = filter,
        onFilterChange = { filter = it },
        onCreate = {
            editor = RuleEditorTarget.Replacement(
                rule = null,
                groupId = groups.firstOrNull()?.id ?: RuleGroup.DEFAULT_ID,
            )
        },
        onCreateGroup = { groupDialog = ReplacementGroupDialogState(null) },
        onImport = {
            importLauncher.launch(
                arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"),
            )
        },
        onExport = { exportLauncher.launch(viewModel.exportFileName(RulesKind.REPLACEMENT)) },
    ) {
        if (state.replacements.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    message = "还没有替换规则。朗读前可自动替换人名、称呼或标点。",
                    actionLabel = "新建替换规则",
                    onAction = {
                        editor = RuleEditorTarget.Replacement(
                            rule = null,
                            groupId = groups.firstOrNull()?.id ?: RuleGroup.DEFAULT_ID,
                        )
                    },
                )
            }
        } else if (visibleReplacements.isEmpty()) {
            item(key = "no-match") { EmptyState("没有匹配的规则，换个关键词或筛选条件试试。") }
        }
        groups.forEach { group ->
            val allGroupRules = state.replacements.filter { it.groupId == group.id }.sortedBy { it.sortIndex }
            val groupRules = visibleReplacements.filter { it.groupId == group.id }.sortedBy { it.sortIndex }
            val filtering = needle.isNotEmpty() || filter != RulesFilter.ALL
            if (groupRules.isEmpty() && filtering) return@forEach
            val expanded = group.id !in collapsed
            item(key = "group:" + group.id) {
                RuleGroupHeader(
                    group = group,
                    count = state.replacements.count { it.groupId == group.id },
                    expanded = expanded,
                    highlighted = controller.hoveredGroupId == group.id,
                    editable = editable,
                    canDelete = group.id != RuleGroup.DEFAULT_ID,
                    onToggleExpand = {
                        collapsed = if (expanded) collapsed + group.id else collapsed - group.id
                    },
                    onToggleEnabled = { viewModel.saveReplacementGroup(group.copy(enabled = it)) },
                    onRename = { groupDialog = ReplacementGroupDialogState(group) },
                    onDelete = { pendingGroupDelete = group },
                    onBounds = { bounds -> controller.registerHeader(group.id, bounds) },
                    onBoundsRemoved = { controller.unregisterHeader(group.id) },
                )
            }
            if (expanded) {
                itemsIndexed(groupRules, key = { _, rule -> "rule:" + rule.id }) { _, rule ->
                    val index = allGroupRules.indexOfFirst { it.id == rule.id }
                    DraggableRuleRow(
                        rowKey = "replacement:" + rule.id,
                        groupId = group.id,
                        index = index,
                        enabled = rule.enabled,
                        editable = editable,
                        controller = controller,
                        draggable = editable && !filtering,
                        groups = groups,
                        onMoveUp = if (index > 0) ({ viewModel.moveReplacement(rule.id, group.id, index - 1) }) else null,
                        onMoveDown = if (index < allGroupRules.lastIndex) ({ viewModel.moveReplacement(rule.id, group.id, index + 2) }) else null,
                        onMoveGroup = { viewModel.moveReplacement(rule.id, it, Int.MAX_VALUE) },
                        onToggle = { viewModel.toggleReplacement(rule, it) },
                        onEdit = { editor = RuleEditorTarget.Replacement(rule, group.id) },
                        onDelete = { pendingDelete = rule },
                        modifier = Modifier.animateItem(),
                    ) {
                        ReplacementRowContent(rule = rule, targetMissing = rule.scope == app.noveltts.domain.ReplacementScope.ROLE && state.roles.none { it.id == rule.targetRoleId })
                    }
                }
            }
        }
    }

    editor?.let { target ->
        RuleEditorSheet(
            target = target,
            groups = groups,
            voices = state.voices,
            roles = state.roles,
            onDismiss = { viewModel.stopPreview(); editor = null },
            onSaveRole = { _, _ -> false },
            onSaveReplacement = { rule, newGroup ->
                val saved = withContext(Dispatchers.IO) { viewModel.saveReplacement(rule, newGroup, (target as? RuleEditorTarget.Replacement)?.expected) }
                if (saved) { viewModel.clearRuleDraft(RulesKind.REPLACEMENT); viewModel.stopPreview(); editor = null }
                saved
            },
            onTestRole = { text, rule, all -> withContext(Dispatchers.Default) { viewModel.testRoleMatch(text, rule, all) } },
            onTestReplacement = { text, rule, all -> withContext(Dispatchers.Default) { viewModel.testReplacements(text, rule, all) } },
            onPreviewVoice = viewModel::previewVoice,
            onStopPreview = viewModel::stopPreview,
            onRoleDraftChange = { _, _, _, _ -> },
            onReplacementDraftChange = viewModel::rememberReplacementDraft,
            onDiscardDraft = { viewModel.clearRuleDraft(RulesKind.REPLACEMENT) },
        )
    }

    groupDialog?.let { dialog ->
        val existing = dialog.group
        RuleGroupDialog(
            title = if (existing == null) "新建分组" else "重命名分组",
            initialName = existing?.name.orEmpty(),
            onDismiss = { groupDialog = null },
            onConfirm = { name ->
                viewModel.saveReplacementGroup(existing?.copy(name = name) ?: RuleGroup(name = name))
                groupDialog = null
            },
        )
    }

    pendingGroupDelete?.let { group ->
        ConfirmDialog(
            title = "删除分组",
            message = "删除分组「" + group.name + "」？组内规则会移到默认分组，不会被删除。",
            dangerous = false,
            onConfirm = {
                viewModel.deleteReplacementGroup(group)
                pendingGroupDelete = null
            },
            onDismiss = { pendingGroupDelete = null },
        )
    }

    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "删除替换规则",
            message = "确定删除「" + rule.name + "」？此操作不可撤销。",
            dangerous = true,
            onConfirm = {
                viewModel.deleteReplacement(rule)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingImport?.let { preview ->
        ImportConflictSheet(
            kindLabel = RulesKind.REPLACEMENT.label,
            preview = preview,
            nameOf = { rule: ReplacementRule -> rule.name },
            onApply = { strategy ->
                viewModel.applyReplacementImport(preview, strategy).fold(
                    onSuccess = { result ->
                        pendingImport = when (result) {
                            is RulesImportApplyResult.Applied -> null
                            is RulesImportApplyResult.PreviewChanged -> result.preview
                        }
                    },
                    onFailure = {
                        importError = it.message ?: "导入失败"
                    },
                )
            },
            onDismiss = { pendingImport = null },
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("导入失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun ReplacementRowContent(rule: ReplacementRule, targetMissing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = rule.name, fontWeight = FontWeight.SemiBold, color = Ink)
        if (rule.isRegex) {
            Spacer(Modifier.width(6.dp))
            Badge(text = "正则", highlight = true)
        }
    }
    if (targetMissing) {
        Badge(text = "目标角色缺失 · 已跳过", highlight = true)
        Text("原角色引用已保留，请编辑并重新绑定角色。", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
    Text(
        text = "查找：" + rule.find,
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
    Text(
        text = "替换为：" + rule.replaceWith.ifEmpty { "（删除匹配内容）" },
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
    Text(
        text = "顺序 " + rule.sortIndex,
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
}
