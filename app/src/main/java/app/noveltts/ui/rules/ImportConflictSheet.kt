package app.noveltts.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.config.RuleImportConflict
import app.noveltts.data.config.RuleImportEntry
import app.noveltts.data.config.RuleImportStrategy
import app.noveltts.data.config.RulesImportPreview
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/**
 * 导入前的冲突预览：先看清哪些条目会同 ID / 同名，再决定跳过、覆盖还是整体替换。
 * 只在用户确认后才写盘，取消不会改动现有规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ImportConflictSheet(
    kindLabel: String,
    preview: RulesImportPreview<T>,
    nameOf: (T) -> String,
    onApply: suspend (RuleImportStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    var applying by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val currentApplying = rememberUpdatedState(applying)
    val confirmChange: (androidx.compose.material3.SheetValue) -> Boolean = remember { { !currentApplying.value } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmChange)
    val scope = rememberCoroutineScope()
    fun apply(strategy: RuleImportStrategy) {
        if (applying) return
        applying = true
        scope.launch { try { onApply(strategy) } finally { applying = false } }
    }
    if (confirmReplace) app.noveltts.ui.common.ConfirmDialog(
        title = "全部覆盖当前规则？", message = "当前全部${kindLabel}和分组将被替换，只保留导入文件里的内容。",
        confirmLabel = "全部覆盖", dangerous = true,
        onConfirm = { confirmReplace = false; apply(RuleImportStrategy.REPLACE_ALL) },
        onDismiss = { confirmReplace = false },
    )
    val conflicts = preview.entries.filter { it.conflict != RuleImportConflict.NONE }
    ModalBottomSheet(onDismissRequest = { if (!applying) onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "导入" + kindLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Text(
                text = "共 " + preview.entries.size + " 条：新增 " + preview.newCount +
                    "，同 ID " + preview.sameIdCount + "，同名 " + preview.sameNameCount,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Text("规则受输入/展开预算及执行超时限制；预览最多 2 秒，朗读规划最多 10 秒。超限会保留规则并停止本次处理。", style = MaterialTheme.typography.labelSmall, color = Muted)
            if (preview.newGroups.isNotEmpty()) {
                Text(
                    text = "将新建分组：" + preview.newGroups.joinToString("、") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            preview.warnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (conflicts.isEmpty()) {
                Text(
                    text = "没有冲突，可以直接导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Spacer(Modifier.weight(1f))
            } else {
                Text(
                    text = "冲突条目（" + conflicts.size + "）",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(conflicts) { _, entry ->
                        ConflictRow(entry = entry, nameOf = nameOf)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !applying) { Text("取消") }
            }
            if (conflicts.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = { apply(RuleImportStrategy.SKIP) }, enabled = !applying) {
                        AppIconGlyph(AppIcon.DOWNLOAD, size = 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("导入")
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { apply(RuleImportStrategy.SKIP) }, enabled = !applying,
                        modifier = Modifier.weight(1f),
                    ) { Text("跳过冲突") }
                    TextButton(
                        onClick = { apply(RuleImportStrategy.OVERWRITE) }, enabled = !applying,
                        modifier = Modifier.weight(1f),
                    ) { Text("覆盖冲突") }
                    TextButton(
                        onClick = { confirmReplace = true }, enabled = !applying,
                        modifier = Modifier.weight(1f),
                    ) { Text("全部覆盖") }
                }
            }
        }
    }
}

@Composable
private fun <T> ConflictRow(entry: RuleImportEntry<T>, nameOf: (T) -> String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nameOf(entry.rule),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                val detail = buildString {
                    append("分组：").append(entry.groupName)
                    entry.existingName?.takeIf { it.isNotBlank() }?.let { append(" · 将替换「").append(it).append("」") }
                }
                Text(text = detail, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Spacer(Modifier.width(8.dp))
            Badge(text = entry.conflict.label, highlight = true)
        }
    }
}
