package app.noveltts.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import app.noveltts.domain.ReplacementRule
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

@Composable
fun ReplacementsPage(state: MainUiState, viewModel: MainViewModel) {
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReplacementRule?>(null) }
    var pendingDelete by remember { mutableStateOf<ReplacementRule?>(null) }
    val editable = state.replacementsLoadError == null

    RulesPageScaffold(
        title = "替换规则",
        description = "按「顺序」从小到大执行；多音字可用普通替换或正则分组。",
        loadError = state.replacementsLoadError,
        onCreate = { creating = true },
    ) {
        if (state.replacements.isEmpty() && editable) {
            item(key = "empty") { EmptyState("还没有替换规则。") }
        }
        items(state.replacements, key = { "rule:${it.id}" }) { rule ->
            RuleCard(
                label = rule.name,
                enabled = rule.enabled,
                editable = editable,
                onToggle = { viewModel.toggleReplacement(rule, it) },
                onEdit = { editing = rule },
                onDelete = { pendingDelete = rule },
            ) {
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
                Text(text = "顺序 ${rule.order}", style = MaterialTheme.typography.labelSmall, color = Muted)
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

    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "删除替换规则",
            message = "确定删除「${rule.name}」？此操作不可撤销。",
            dangerous = true,
            onConfirm = {
                viewModel.deleteReplacement(rule)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
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
                LabeledSwitch("使用正则", isRegex) { isRegex = it }
                OutlinedTextField(
                    value = order,
                    onValueChange = { value -> order = value.filter { it.isDigit() }.take(4) },
                    label = { Text("顺序（越小越先执行）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSwitch("启用", enabled) { enabled = it }

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
                    Text(text = "结果：$testOutput", style = MaterialTheme.typography.bodySmall, color = Ink)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && find.isNotBlank(),
                onClick = {
                    val base = rule ?: ReplacementRule(name = "", find = "", replaceWith = "")
                    onSave(
                        base.copy(
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
