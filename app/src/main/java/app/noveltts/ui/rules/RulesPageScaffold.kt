package app.noveltts.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/**
 * 角色/替换页共用的列表骨架：页头、加载错误横幅与条目全部放在同一个 [LazyColumn] 中，
 * 为后续分组与拖拽排序提供单一滚动容器。
 */
@Composable
internal fun RulesPageScaffold(
    title: String,
    description: String,
    loadError: String?,
    onCreate: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(text = description, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Button(onClick = onCreate, enabled = loadError == null) { Text("新增") }
            }
        }
        if (loadError != null) {
            item(key = "load-error") { LoadErrorBanner(loadError) }
        }
        content()
    }
}

@Composable
private fun LoadErrorBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "规则文件无法读取，编辑已暂停",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** 规则卡片：标题区 + 启用开关 + 「编辑 / 删除」操作行。 */
@Composable
internal fun RuleCard(
    label: String,
    enabled: Boolean,
    editable: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { content() }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = editable,
                    modifier = Modifier.semantics { contentDescription = "启用「$label」" },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, enabled = editable) { Text("编辑") }
                TextButton(onClick = onDelete, enabled = editable) { Text("删除") }
            }
        }
    }
}
