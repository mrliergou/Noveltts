package app.noveltts.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.SearchField
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 规则编辑用的音色多选弹窗：勾选顺序即轮转顺序，已选音色排在列表最前并标出序号。
 * 不可用音色灰显、不可勾选，但保留在列表里说明它存在、缺什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleVoicePickerSheet(
    title: String,
    voices: List<VoiceDescriptor>,
    selectedIds: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onPreview: ((VoiceDescriptor) -> Unit)? = null,
    onStopPreview: (() -> Unit)? = null,
) {
    DisposableEffect(Unit) { onDispose { onStopPreview?.invoke() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var draft by remember { mutableStateOf(selectedIds) }
    val hideThenDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val ordered = remember(voices, draft) {
        val pickedIndex = draft.withIndex().associate { it.value to it.index }
        voices.sortedWith(
            compareBy<VoiceDescriptor> { pickedIndex[it.systemName] ?: Int.MAX_VALUE }
                .thenByDescending { it.usable }
                .thenBy { it.displayName },
        )
    }
    val visible = remember(ordered, query) { ordered.filter { it.matchesRuleQuery(query) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = hideThenDismiss) {
                        AppIconGlyph(AppIcon.CLOSE, size = 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("关闭")
                    }
                }
            }
            item(key = "search") {
                SearchField(value = query, onValueChange = { query = it }, placeholder = "搜索音色、语音包或引擎")
            }
            item(key = "count") {
                Text(
                    text = "已选 " + draft.size + " / " + voices.size + " 个音色，勾选顺序即轮转顺序",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (voices.isEmpty()) "还没有可用音色，请先在「语音包」导入。" else "没有匹配的音色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
            items(visible, key = { "pick:" + it.systemName }) { voice ->
                val order = draft.indexOf(voice.systemName)
                val selectable = voice.usable || order >= 0
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (order >= 0) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = selectable) {
                            draft = if (order >= 0) draft - voice.systemName else draft + voice.systemName
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .alpha(if (voice.usable) 1f else 0.6f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = voice.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (voice.usable) Ink else Muted,
                                )
                                if (order >= 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Badge(text = "第 " + (order + 1) + " 个", highlight = true)
                                }
                            }
                            Text(
                                text = voice.packName + " · " + if (voice.isOnline) "在线" else "离线",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                            voice.unusableReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                Spacer(Modifier.height(2.dp))
                                Text(text = reason, style = MaterialTheme.typography.labelSmall, color = Accent)
                            }
                        }
                        if (onPreview != null && voice.usable) {
                            TextButton(onClick = { onPreview(voice) }) {
                                AppIconGlyph(AppIcon.PLAY, size = 16.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("试听")
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = hideThenDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(draft)
                            hideThenDismiss()
                        },
                        enabled = draft.isNotEmpty(),
                    ) {
                        AppIconGlyph(AppIcon.CHECK, size = 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("确定")
                    }
                }
            }
        }
    }
}

private fun VoiceDescriptor.matchesRuleQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return listOf(displayName, packName, engineCode, voiceCode, systemName)
        .any { it.contains(needle, ignoreCase = true) }
}
