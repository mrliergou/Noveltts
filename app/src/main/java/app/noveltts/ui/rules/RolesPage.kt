package app.noveltts.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.domain.RoleRule
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.common.VoicePickerSheet
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

@Composable
fun RolesPage(state: MainUiState, viewModel: MainViewModel) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RoleRule?>(null) }
    var pendingDelete by remember { mutableStateOf<RoleRule?>(null) }
    val editable = state.rolesLoadError == null
    val voiceNames = remember(state.voices) { state.voices.associate { it.systemName to it.displayName } }

    RulesPageScaffold(
        title = "角色规则",
        description = "用正则匹配引号内的对话，命中后改用指定音色；优先级大的先匹配。",
        loadError = state.rolesLoadError,
        onCreate = { creating = true },
    ) {
        if (state.roles.isEmpty() && editable) {
            item(key = "empty") { EmptyState("还没有角色规则。未命中的对话会使用「对话音色」。") }
        }
        items(state.roles, key = { "rule:${it.id}" }) { rule ->
            RuleCard(
                label = rule.name,
                enabled = rule.enabled,
                editable = editable,
                onToggle = { viewModel.toggleRole(rule, it) },
                onEdit = { editing = rule },
                onDelete = { pendingDelete = rule },
            ) {
                Text(text = rule.name, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(text = "匹配：${rule.pattern}", style = MaterialTheme.typography.labelSmall, color = Muted)
                val meta = buildString {
                    append("音色：")
                    append(rule.voiceIds.joinToString("、") { voiceNames[it] ?: "未选择" }.ifEmpty { "未选择" })
                    append(" · 优先级 ").append(rule.priority)
                    if (rule.onlyDialogue) append(" · 仅对话")
                }
                Text(text = meta, style = MaterialTheme.typography.labelSmall, color = Muted)
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

    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "删除角色规则",
            message = "确定删除「${rule.name}」？此操作不可撤销。",
            dangerous = true,
            onConfirm = {
                viewModel.deleteRole(rule)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 角色编辑对话框。当前仅编辑首个音色；编辑既有规则时以 `copy` 保留
 * 分组、匹配模式、轮转音色等 v2 字段，避免被旧界面静默抹掉。
 */
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
        mutableStateOf(rule?.primaryVoiceId ?: voices.firstOrNull { it.usable }?.systemName.orEmpty())
    }
    var priority by remember { mutableStateOf((rule?.priority ?: 100).toString()) }
    var onlyDialogue by remember { mutableStateOf(rule?.onlyDialogue ?: true) }
    var enabled by remember { mutableStateOf(rule?.enabled ?: true) }
    var pickingVoice by remember { mutableStateOf(false) }
    val extraVoices = rule?.voiceIds?.filter { it.isNotBlank() }?.drop(1).orEmpty()

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
                        if (extraVoices.isNotEmpty()) {
                            Text(
                                text = "另有 ${extraVoices.size} 个轮转音色保持不变",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
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
                LabeledSwitch("仅匹配对话", onlyDialogue) { onlyDialogue = it }
                LabeledSwitch("启用", enabled) { enabled = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && pattern.isNotBlank() && voiceId.isNotBlank(),
                onClick = {
                    val base = rule ?: RoleRule(name = "", pattern = "", voiceIds = emptyList())
                    onSave(
                        base.copy(
                            name = name.trim(),
                            pattern = pattern.trim(),
                            voiceIds = listOf(voiceId) + extraVoices,
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
        VoicePickerSheet(
            title = "角色音色",
            voices = voices,
            selectedId = voiceId,
            onSelect = { voiceId = it },
            onDismiss = { pickingVoice = false },
        )
    }
}

@Composable
internal fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // 整行承载点击与语义，读屏会把标签与开关状态一起播报。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
