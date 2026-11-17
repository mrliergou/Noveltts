package app.noveltts.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.noveltts.domain.RuleGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.theme.Muted
import kotlin.math.absoluteValue

/** 拖拽落点：目标分组 + 组内位置（含被拖动行自身，交给 ViewModel 做同组位移修正）。 */
internal data class RuleDropTarget(val groupId: String, val index: Int)

/**
 * 拖拽协调器：登记每行与每个组头的窗口坐标，把手指位置换算成落点。
 * 不依赖第三方库，只做坐标判定；落盘由调用方的 onDrop 走 ViewModel.moveXxx。
 */
internal class RuleDragController(
    private val onDrop: (ruleId: String, targetGroupId: String, targetIndex: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var dragOffset by mutableStateOf(0f)
        private set
    var hoveredKey by mutableStateOf<String?>(null)
        private set
    var hoveredGroupId by mutableStateOf<String?>(null)
        private set

    private var pointer = Offset.Zero
    private var target: RuleDropTarget? = null
    private val rowBounds = mutableMapOf<String, Rect>()
    private val rowPlacement = mutableMapOf<String, Pair<String, Int>>()
    private val headerBounds = mutableMapOf<String, Rect>()
    private val draggableRows = mutableSetOf<String>()

    fun registerRow(key: String, groupId: String, index: Int, bounds: Rect, draggable: Boolean) {
        rowBounds[key] = bounds
        rowPlacement[key] = groupId to index
        if (draggable) draggableRows.add(key) else draggableRows.remove(key)
        if (draggingKey != null) updateTarget()
    }

    fun unregisterRow(key: String) {
        rowBounds.remove(key)
        rowPlacement.remove(key)
        draggableRows.remove(key)
        if (draggingKey != null) updateTarget()
    }

    fun registerHeader(groupId: String, bounds: Rect) {
        headerBounds[groupId] = bounds
        if (draggingKey != null) updateTarget()
    }

    fun unregisterHeader(groupId: String) { headerBounds.remove(groupId); if (draggingKey != null) updateTarget() }

    fun startAt(pointerInWindow: Offset): Boolean {
        val key = rowBounds.entries.firstOrNull { it.key in draggableRows && it.value.contains(pointerInWindow) }?.key ?: return false
        start(key, pointerInWindow)
        return true
    }
    fun canDragAt(pointerInWindow: Offset): Boolean = rowBounds.any { it.key in draggableRows && it.value.contains(pointerInWindow) }

    fun didScroll(pixels: Float) { if (draggingKey != null) { dragOffset += pixels; updateTarget() } }

    fun start(key: String, pointerInWindow: Offset) {
        draggingKey = key
        pointer = pointerInWindow
        dragOffset = 0f
        updateTarget()
    }

    fun drag(amount: Offset) {
        if (draggingKey == null) return
        pointer += amount
        dragOffset += amount.y
        updateTarget()
    }

    fun end() {
        val id = draggingKey ?: return
        val drop = target
        reset()
        if (drop != null) onDrop(id.substringAfter(':'), drop.groupId, drop.index)
    }

    fun cancel() {
        reset()
    }

    private fun reset() {
        draggingKey = null
        dragOffset = 0f
        hoveredKey = null
        hoveredGroupId = null
        target = null
    }

    /** 组头优先：落在组头上表示移动到该组末尾；否则取指针所在行，按中线上下决定前后。 */
    private fun updateTarget() {
        val dragged = draggingKey ?: return
        val header = headerBounds.entries.firstOrNull { it.value.contains(pointer) }
        if (header != null) {
            hoveredGroupId = header.key
            hoveredKey = null
            target = RuleDropTarget(header.key, Int.MAX_VALUE)
            return
        }
        hoveredGroupId = null
        val hit = rowBounds.entries
            .filter { it.key != dragged && it.value.height > 0 && pointer.x in it.value.left..it.value.right }
            .minByOrNull { (it.value.center.y - pointer.y).absoluteValue }
        if (hit == null) {
            hoveredKey = null
            target = null
            return
        }
        hoveredKey = hit.key
        val placement = rowPlacement[hit.key] ?: return
        val above = pointer.y < hit.value.center.y
        target = RuleDropTarget(placement.first, if (above) placement.second else placement.second + 1)
    }

    fun scrollDelta(top: Float, bottom: Float, edge: Float = 100f, step: Float = 18f): Float = when {
        draggingKey == null -> 0f
        pointer.y < top + edge -> -step * ((top + edge - pointer.y) / edge).coerceIn(0f, 1f)
        pointer.y > bottom - edge -> step * ((pointer.y - bottom + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

/**
 * 可长按拖拽的规则行：长按后整行跟随手指（graphicsLayer 位移），经过其他行或组头时高亮，
 * 松手时把落点交给拖拽协调器。调用方传入 animateItem 修饰符可让其余行补位有动画。
 */
@Composable
internal fun DraggableRuleRow(
    rowKey: String,
    groupId: String,
    index: Int,
    enabled: Boolean,
    editable: Boolean,
    controller: RuleDragController,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    draggable: Boolean = editable,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    groups: List<RuleGroup> = emptyList(),
    onMoveGroup: ((String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val dragging = controller.draggingKey == rowKey
    val hovered = controller.hoveredKey == rowKey
    val colors = MaterialTheme.colorScheme
    var showOrderMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = when {
            dragging -> colors.primary.copy(alpha = 0.16f)
            hovered -> colors.primary.copy(alpha = 0.06f)
            else -> colors.surfaceVariant
        },
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (dragging) {
                    translationY = controller.dragOffset
                    scaleX = 1.01f
                    scaleY = 1.01f
                }
            }
            .onGloballyPositioned { coordinates ->
                controller.registerRow(rowKey, groupId, index, coordinates.boundsInWindow(), draggable)
            },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (draggable) {
                    AppIconGlyph(AppIcon.DRAG, size = 20.dp, tint = Muted)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    content()
                }
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = editable)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { showOrderMenu = true }, enabled = editable) { Text("排序") }
                    DropdownMenu(expanded = showOrderMenu, onDismissRequest = { showOrderMenu = false }) {
                        DropdownMenuItem(text = { Text("上移") }, enabled = onMoveUp != null,
                            onClick = { showOrderMenu = false; onMoveUp?.invoke() })
                        DropdownMenuItem(text = { Text("下移") }, enabled = onMoveDown != null,
                            onClick = { showOrderMenu = false; onMoveDown?.invoke() })
                        groups.filter { it.id != groupId }.forEach { group ->
                            DropdownMenuItem(text = { Text("移到「${group.name}」") },
                                onClick = { showOrderMenu = false; onMoveGroup?.invoke(group.id) })
                        }
                    }
                }
                TextButton(onClick = onEdit, enabled = editable) {
                    AppIconGlyph(AppIcon.EDIT, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                TextButton(onClick = onDelete, enabled = editable) {
                    AppIconGlyph(AppIcon.DELETE, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
    DisposableEffect(rowKey) {
        onDispose { controller.unregisterRow(rowKey) }
    }
}
