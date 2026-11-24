package app.noveltts.ui.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import app.noveltts.domain.RoleAliases
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.VoiceSelectionMode
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.SectionCard
import app.noveltts.ui.theme.Accent
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
private data class RoleGroupDialogState(val group: RuleGroup?)

private const val DEFAULT_GROUP_FALLBACK = "default"

@Composable
fun RolesPage(state: MainUiState, viewModel: MainViewModel) {
    val editable = state.rolesLoadError == null
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(RulesFilter.ALL) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var editor by remember { mutableStateOf<RuleEditorTarget?>(viewModel.roleDraft()?.let { RuleEditorTarget.Role(it.rule, it.rule.groupId, it.newGroupName, true, it.fields, it.saveBaseline) }) }
    var groupDialog by remember { mutableStateOf<RoleGroupDialogState?>(null) }
    var pendingGroupDelete by remember { mutableStateOf<RuleGroup?>(null) }
    var pendingDelete by remember { mutableStateOf<RoleRule?>(null) }
    var pendingImport by remember { mutableStateOf<RulesImportPreview<RoleRule>?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var confirmMigrate by remember { mutableStateOf(false) }
    // 还能直接改用自动判断的旧式角色数量；为 0 时不显示迁移横幅。
    val legacyCount = state.roles.count(RoleAliases::canSwitchToAuto)
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.prepareRoleImport(uri).fold(
                    onSuccess = { pendingImport = it },
                    onFailure = { importError = it.message ?: "导入失败，文件可能不是有效的规则 JSON" },
                )
            }
        }
    }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch { viewModel.exportRulesTo(uri, RulesKind.ROLE) }
            }
        }

    val groups = remember(state.roleGroups) { sortedRuleGroups(state.roleGroups) }
    val voiceNames = remember(state.voices) { state.voices.associate { it.systemName to it.displayName } }
    val controller = remember(viewModel) {
        RuleDragController { ruleId, groupId, index -> viewModel.moveRole(ruleId, groupId, index) }
    }
    val listState = rememberLazyListState()
    val groupNames = remember(groups) { groups.associate { it.id to it.name } }
    val needle = query.trim()
    val visibleRoles = state.roles.filter { rule ->
        val byFilter = when (filter) {
            RulesFilter.ALL -> true
            RulesFilter.ENABLED -> rule.enabled
            RulesFilter.DISABLED -> !rule.enabled
        }
        val byQuery = needle.isEmpty() ||
            rule.name.contains(needle, ignoreCase = true) ||
            rule.pattern.contains(needle, ignoreCase = true) ||
            rule.voiceIds.any { (voiceNames[it] ?: "").contains(needle, ignoreCase = true) } ||
            (groupNames[rule.groupId] ?: "").contains(needle, ignoreCase = true)
        byFilter && byQuery
    }

    RulesPageScaffold(
        title = "角色规则",
        description = "长按条目拖拽排序；组内按优先级从大到小匹配引号内的对话。",
        listState = listState,
        dragController = controller,
        loadError = state.rolesLoadError,
        query = query,
        onQueryChange = { query = it },
        filter = filter,
        onFilterChange = { filter = it },
        onCreate = {
            editor = RuleEditorTarget.Role(null, groups.firstOrNull()?.id ?: RuleGroup.DEFAULT_ID)
        },
        onCreateGroup = { groupDialog = RoleGroupDialogState(null) },
        onImport = {
            importLauncher.launch(
                arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"),
            )
        },
        onExport = { exportLauncher.launch(viewModel.exportFileName(RulesKind.ROLE)) },
    ) {
        if (editable && legacyCount > 0) {
            item(key = "migrate-auto") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIconGlyph(AppIcon.INFO, size = 18.dp, tint = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$legacyCount 个角色仍用旧式匹配，长段落里容易读错人",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(onClick = { confirmMigrate = true }) { Text("全部改用自动") }
                }
            }
        }
        if (state.roles.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    message = "还没有角色规则。未命中的对话会使用「对话音色」。",
                    actionLabel = "新建角色规则",
                    onAction = {
                        editor = RuleEditorTarget.Role(null, groups.firstOrNull()?.id ?: RuleGroup.DEFAULT_ID)
                    },
                )
            }
        } else if (visibleRoles.isEmpty()) {
            item(key = "no-match") { EmptyState("没有匹配的规则，换个关键词或筛选条件试试。") }
        }
        groups.forEach { group ->
            val allGroupRules = state.roles.filter { it.groupId == group.id }.sortedBy { it.sortIndex }
            val groupRules = visibleRoles.filter { it.groupId == group.id }.sortedBy { it.sortIndex }
            val filtering = needle.isNotEmpty() || filter != RulesFilter.ALL
            if (groupRules.isEmpty() && filtering) return@forEach
            val expanded = group.id !in collapsed
            item(key = "group:" + group.id) {
                RuleGroupHeader(
                    group = group,
                    count = state.roles.count { it.groupId == group.id },
                    expanded = expanded,
                    highlighted = controller.hoveredGroupId == group.id,
                    editable = editable,
                    canDelete = group.id != RuleGroup.DEFAULT_ID,
                    onToggleExpand = {
                        collapsed = if (expanded) collapsed + group.id else collapsed - group.id
                    },
                    onToggleEnabled = { viewModel.saveRoleGroup(group.copy(enabled = it)) },
                    onRename = { groupDialog = RoleGroupDialogState(group) },
                    onDelete = { pendingGroupDelete = group },
                    onBounds = { bounds -> controller.registerHeader(group.id, bounds) },
                    onBoundsRemoved = { controller.unregisterHeader(group.id) },
                )
            }
            if (expanded) {
                itemsIndexed(groupRules, key = { _, rule -> "rule:" + rule.id }) { _, rule ->
                    val index = allGroupRules.indexOfFirst { it.id == rule.id }
                    DraggableRuleRow(
                        rowKey = "role:" + rule.id,
                        groupId = group.id,
                        index = index,
                        enabled = rule.enabled,
                        editable = editable,
                        controller = controller,
                        draggable = editable && !filtering,
                        groups = groups,
                        onMoveUp = if (index > 0) ({ viewModel.moveRole(rule.id, group.id, index - 1) }) else null,
                        onMoveDown = if (index < allGroupRules.lastIndex) ({ viewModel.moveRole(rule.id, group.id, index + 2) }) else null,
                        onMoveGroup = { viewModel.moveRole(rule.id, it, Int.MAX_VALUE) },
                        onToggle = { viewModel.toggleRole(rule, it) },
                        onEdit = { editor = RuleEditorTarget.Role(rule, group.id) },
                        onDelete = { pendingDelete = rule },
                        modifier = Modifier.animateItem(),
                    ) {
                        RoleRowContent(rule = rule, voiceNames = voiceNames)
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
            onSaveRole = { rule, newGroup ->
                val saved = withContext(Dispatchers.IO) { viewModel.saveRole(rule, newGroup, (target as? RuleEditorTarget.Role)?.expected) }
                if (saved) { viewModel.clearRuleDraft(RulesKind.ROLE); viewModel.stopPreview(); editor = null }
                saved
            },
            onSaveReplacement = { _, _ -> false },
            onTestRole = { text, rule, all -> withContext(Dispatchers.Default) { viewModel.testRoleMatch(text, rule, all) } },
            onTestReplacement = { text, rule, all -> withContext(Dispatchers.Default) { viewModel.testReplacements(text, rule, all) } },
            onPreviewVoice = viewModel::previewVoice,
            onStopPreview = viewModel::stopPreview,
            onRoleDraftChange = viewModel::rememberRoleDraft,
            onReplacementDraftChange = { _, _, _, _ -> },
            onDiscardDraft = { viewModel.clearRuleDraft(RulesKind.ROLE) },
        )
    }

    groupDialog?.let { dialog ->
        val existing = dialog.group
        RuleGroupDialog(
            title = if (existing == null) "新建角色分组" else "重命名分组",
            initialName = existing?.name.orEmpty(),
            onDismiss = { groupDialog = null },
            onConfirm = { name ->
                viewModel.saveRoleGroup(existing?.copy(name = name) ?: RuleGroup(name = name))
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
                viewModel.deleteRoleGroup(group)
                pendingGroupDelete = null
            },
            onDismiss = { pendingGroupDelete = null },
        )
    }

    if (confirmMigrate) {
        ConfirmDialog(
            title = "全部改用自动判断？",
            message = "会把 $legacyCount 个角色的匹配方式改成「自动（看引号前后是谁在说）」。" +
                "音色与称呼不变。改后只看引号外的旁白里是谁在说话，引号里出现的称呼不再算数；" +
                "靠「老夫」「本座」这类自称识别的角色请先改回「当前片段」。之后可以在规则编辑器里逐个改回。",
            confirmLabel = "改用自动",
            dangerous = false,
            onConfirm = {
                confirmMigrate = false
                viewModel.migrateRolesToAuto()
            },
            onDismiss = { confirmMigrate = false },
        )
    }

    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "删除角色规则",
            message = "确定删除「" + rule.name + "」？关联的 " + state.replacements.count { it.scope == app.noveltts.domain.ReplacementScope.ROLE && it.targetRoleId == rule.id } + " 条定向替换规则将保留，目标缺失期间不生效，可重新绑定。",
            dangerous = true,
            onConfirm = {
                viewModel.deleteRole(rule)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingImport?.let { preview ->
        ImportConflictSheet(
            kindLabel = RulesKind.ROLE.label,
            preview = preview,
            nameOf = { rule: RoleRule -> rule.name },
            onApply = { strategy ->
                viewModel.applyRoleImport(preview, strategy).fold(
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
private fun RoleRowContent(rule: RoleRule, voiceNames: Map<String, String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = rule.name, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.width(6.dp))
        Badge(text = rule.matchMode.label, highlight = true)
        if (rule.voiceSelectionMode == VoiceSelectionMode.ROUND_ROBIN) {
            Spacer(Modifier.width(6.dp))
            Badge(text = "轮转")
        }
    }
    Text(
        text = "匹配：" + rule.pattern,
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
    Text(
        text = "音色：" + rule.voiceIds.joinToString("、") { voiceNames[it] ?: it }.ifEmpty { "未选择" },
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
    Text(
        text = "优先级 " + rule.priority + if (rule.onlyDialogue) " · 仅对话" else "",
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
}
