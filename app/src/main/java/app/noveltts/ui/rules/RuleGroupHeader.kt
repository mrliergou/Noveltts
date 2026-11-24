package app.noveltts.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.domain.RuleGroup
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/**
 * 分组头：折叠箭头、组名 + 条数、启用开关、溢出菜单（重命名 / 删除）。
 * 拖拽经过时整行高亮，onBounds 把窗口坐标交给拖拽协调器用来判定落点。
 */
@Composable
internal fun RuleGroupHeader(
    group: RuleGroup,
    count: Int,
    expanded: Boolean,
    highlighted: Boolean,
    editable: Boolean,
    canDelete: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBounds: ((Rect) -> Unit)? = null,
    onBoundsRemoved: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    DisposableEffect(group.id) { onDispose { onBoundsRemoved() } }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (highlighted) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates -> onBounds?.invoke(coordinates.boundsInWindow()) }
            .clickable(onClick = onToggleExpand),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconGlyph(
                icon = if (expanded) AppIcon.CHEVRON_DOWN else AppIcon.CHEVRON_UP,
                size = 20.dp,
                tint = Muted,
            )
            Spacer(Modifier.width(8.dp))
            AppIconGlyph(AppIcon.FOLDER, size = 18.dp, tint = Muted)
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    Spacer(Modifier.width(6.dp))
                    Badge(text = count.toString())
                    if (!group.enabled) {
                        Spacer(Modifier.width(6.dp))
                        Badge(text = "已停用")
                    }
                }
            }
            Switch(
                checked = group.enabled,
                onCheckedChange = onToggleEnabled,
                enabled = editable,
            )
            GroupOverflowMenu(editable = editable, canDelete = canDelete, onRename = onRename, onDelete = onDelete)
        }
    }
}

@Composable
private fun GroupOverflowMenu(
    editable: Boolean,
    canDelete: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = editable) {
            AppIconGlyph(AppIcon.MORE, size = 20.dp, contentDescription = "分组菜单")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                leadingIcon = { AppIconGlyph(AppIcon.EDIT, size = 18.dp) },
                text = { Text("重命名") },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                leadingIcon = { AppIconGlyph(AppIcon.DELETE, size = 18.dp) },
                text = { Text(if (canDelete) "删除分组" else "默认分组不可删除") },
                enabled = canDelete,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/** 新建 / 重命名分组的单输入弹窗。 */
@Composable
internal fun RuleGroupDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.SemiBold, color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "同名分组仍独立保存；只有导入规则时会按名称合并分组。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
