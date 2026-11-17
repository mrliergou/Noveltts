package app.noveltts.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import app.noveltts.data.config.normalizeRuleGroups
import app.noveltts.domain.RuleGroup
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.FilterChipRow
import app.noveltts.ui.common.SearchField
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/** 列表的启用状态筛选。 */
internal enum class RulesFilter(val label: String) {
    ALL("全部"),
    ENABLED("已启用"),
    DISABLED("已停用"),
}

/** 分组按 order、名称排序，并保证「默认」分组存在。 */
internal fun sortedRuleGroups(groups: List<RuleGroup>): List<RuleGroup> =
    normalizeRuleGroups(groups).sortedWith(compareBy<RuleGroup> { it.order }.thenBy { it.name })

/**
 * 角色/替换页共用的列表骨架：页头（标题 + 新增 + 搜索/筛选工具栏）、加载错误横幅与条目
 * 全部放在同一个 LazyColumn 中，为分组与拖拽排序提供单一滚动容器。
 */
@Composable
internal fun RulesPageScaffold(
    title: String,
    description: String,
    loadError: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: RulesFilter,
    onFilterChange: (RulesFilter) -> Unit,
    onCreate: () -> Unit,
    onCreateGroup: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    dragController: RuleDragController? = null,
    content: LazyListScope.() -> Unit,
) {
    val editable = loadError == null
    val dragEnabled = editable && query.isBlank() && filter == RulesFilter.ALL
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    DisposableEffect(dragController) { onDispose { dragController?.cancel() } }
    LaunchedEffect(dragController?.draggingKey) {
        var previousFrame = 0L
        while (dragController?.draggingKey != null) {
            val frame = withFrameNanos { it }
            val frameScale = if (previousFrame == 0L) 1f else ((frame - previousFrame) / 16_666_667f).coerceIn(0f, 3f)
            previousFrame = frame
            val delta = dragController.scrollDelta(listBounds.top, listBounds.bottom,
                with(density) { 56.dp.toPx() }, with(density) { 6.dp.toPx() } * frameScale)
            if (delta != 0f) dragController.didScroll(listState.scrollBy(delta))
        }
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        // 拖拽接管手势，避免列表先消费移动事件；边缘自动滚动仍可用。
        userScrollEnabled = dragController?.draggingKey == null,
        modifier = Modifier.fillMaxSize().onGloballyPositioned { listBounds = it.boundsInWindow() }
            .pointerInput(dragController, dragEnabled) {
                val controller = dragController ?: return@pointerInput
                if (!dragEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val origin = Offset(listBounds.left, listBounds.top)
                    if (!controller.canDragAt(origin + down.position)) return@awaitEachGesture
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    if (!controller.startAt(origin + held.position)) return@awaitEachGesture
                    held.consume()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        val completed = drag(held.id) { change -> controller.drag(change.positionChange()); change.consume() }
                        if (completed) { currentEvent.changes.forEach { if (!it.pressed) it.consume() }; controller.end() }
                        else controller.cancel()
                    } finally { if (controller.draggingKey != null) controller.cancel() }
                }
            },
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Button(onClick = onCreate, enabled = editable) { Text("新增") }
                }
                RulesToolbar(
                    query = query,
                    onQueryChange = onQueryChange,
                    filter = filter,
                    onFilterChange = onFilterChange,
                    editable = editable,
                    onCreateGroup = onCreateGroup,
                    onImport = onImport,
                    onExport = onExport,
                )
                if (editable && !dragEnabled) Text("筛选时可用每条规则的「排序」菜单调整位置。",
                    style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        if (loadError != null) {
            item(key = "load-error") { LoadErrorBanner(loadError) }
        }
        content()
    }
    if (dragController?.draggingKey != null) Surface(
        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer,
    ) { Text("正在移动规则，滚动到目标后松手", modifier = Modifier.padding(12.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

/** 搜索框 + 启用筛选 chips + 溢出菜单（新建分组 / 导入 / 导出）。 */
@Composable
private fun RulesToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: RulesFilter,
    onFilterChange: (RulesFilter) -> Unit,
    editable: Boolean,
    onCreateGroup: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "搜索名称、表达式或音色",
                modifier = Modifier.weight(1f),
            )
            RulesOverflowMenu(
                editable = editable,
                onCreateGroup = onCreateGroup,
                onImport = onImport,
                onExport = onExport,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconGlyph(AppIcon.FILTER, size = 18.dp, tint = Muted)
            Spacer(Modifier.width(8.dp))
            FilterChipRow(
                options = RulesFilter.entries.map { it to it.label },
                selected = filter,
                onSelect = onFilterChange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RulesOverflowMenu(
    editable: Boolean,
    onCreateGroup: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            AppIconGlyph(AppIcon.MORE, size = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text("更多")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                leadingIcon = { AppIconGlyph(AppIcon.ADD, size = 18.dp) },
                text = { Text("新建分组") },
                enabled = editable,
                onClick = {
                    expanded = false
                    onCreateGroup()
                },
            )
            DropdownMenuItem(
                leadingIcon = { AppIconGlyph(AppIcon.DOWNLOAD, size = 18.dp) },
                text = { Text("导入 JSON") },
                enabled = editable,
                onClick = {
                    expanded = false
                    onImport()
                },
            )
            DropdownMenuItem(
                leadingIcon = { AppIconGlyph(AppIcon.UPLOAD, size = 18.dp) },
                text = { Text("导出 JSON") },
                onClick = {
                    expanded = false
                    onExport()
                },
            )
        }
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
