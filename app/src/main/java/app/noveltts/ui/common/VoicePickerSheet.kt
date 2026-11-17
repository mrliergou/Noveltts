package app.noveltts.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
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
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlinx.coroutines.launch

internal enum class VoiceFilter(val label: String) {
    ALL("全部"),
    ONLINE("在线"),
    OFFLINE("离线"),
}

/** 记住上次的筛选条件：弹窗关闭后重开仍是同一筛选，避免每次都回到「全部」。 */
internal object VoicePickerMemory {
    var filter: VoiceFilter = VoiceFilter.ALL
}

/**
 * 音色选择底部弹窗：可搜索、可按在线/离线筛选，按语音包分组（吸顶组头）。
 * 不可用音色灰显并给出原因但仍保留在列表里，让用户知道它存在、缺什么。
 * 当前选中的音色排在所在分组最前；传入 [onPreview] 时每个音色带「试听」按钮。
 *
 * 选中后先回调 [onSelect]，再播完收起动画后回调 [onDismiss]；调用方只需在 [onDismiss] 里关闭。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VoicePickerSheet(
    title: String,
    voices: List<VoiceDescriptor>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onPreview: ((VoiceDescriptor) -> Unit)? = null,
    onStopPreview: (() -> Unit)? = null,
    previewVoiceId: String? = null,
    previewStatus: String = "",
) {
    DisposableEffect(Unit) { onDispose { onStopPreview?.invoke() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(VoicePickerMemory.filter) }

    val visible = remember(voices, query, filter) {
        voices.filter { voice ->
            val matchesFilter = when (filter) {
                VoiceFilter.ALL -> true
                VoiceFilter.ONLINE -> voice.isOnline
                VoiceFilter.OFFLINE -> voice.isOffline
            }
            matchesFilter && voice.matchesQuery(query)
        }
    }
    // 按语音包分组：组顺序取音色原始出现顺序，组内「当前」置顶、可用优先、再按名称。
    val groups: List<Pair<String, List<VoiceDescriptor>>> = remember(visible, selectedId) {
        visible.map { it.packName }.distinct().map { pack ->
            pack to visible
                .filter { it.packName == pack }
                .sortedWith(
                    compareByDescending<VoiceDescriptor> { it.systemName == selectedId }
                        .thenByDescending { it.usable }
                        .thenBy { it.displayName },
                )
        }
    }
    val hideThenDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // 整个弹窗只用一个滚动容器：小屏/横屏时头部 + 列表总高超出可用空间也能滚动。
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
                        AppIconGlyph(icon = AppIcon.CLOSE, size = 18.dp, modifier = Modifier.padding(end = 4.dp))
                        Text("关闭")
                    }
                }
            }
            item(key = "search") {
                SearchField(value = query, onValueChange = { query = it }, placeholder = "搜索音色、语音包或引擎")
            }
            item(key = "filter") {
                FilterChipRow(
                    options = VoiceFilter.entries.map { it to it.label },
                    selected = filter,
                    onSelect = {
                        filter = it
                        VoicePickerMemory.filter = it
                    },
                )
            }
            item(key = "count") {
                if (previewVoiceId != null) Text(previewStatus, style = MaterialTheme.typography.bodySmall, color = Ink)
                Text(
                    text = "${visible.size} / ${voices.size} 个音色",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        if (voices.isEmpty()) {
                            "还没有可用音色，请先在「语音包」导入。"
                        } else {
                            "没有匹配的音色，换个关键词或筛选条件试试。"
                        },
                    )
                }
            } else {
                groups.forEach { (pack, packVoices) ->
                    stickyHeader(key = "pack:$pack") {
                        PackHeader(name = pack, count = packVoices.size)
                    }
                    items(packVoices, key = { "voice:${it.systemName}" }) { voice ->
                        VoiceOptionCard(
                            voice = voice,
                            selected = voice.systemName == selectedId,
                            onClick = {
                                onSelect(voice.systemName)
                                hideThenDismiss()
                            },
                            onPreview = onPreview?.takeIf { voice.usable }?.let { preview ->
                                { if (previewVoiceId == voice.systemName) onStopPreview?.invoke() else preview(voice) }
                            },
                            playing = previewVoiceId == voice.systemName,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PackHeader(name: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$count 个音色",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

@Composable
internal fun VoiceOptionCard(
    voice: VoiceDescriptor,
    selected: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
    playing: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = voice.usable, onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .alpha(if (voice.usable) 1f else 0.6f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = voice.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (voice.usable) Ink else Muted,
                    modifier = Modifier.weight(1f),
                )
                if (onPreview != null) {
                    TextButton(onClick = onPreview) { Text(if (playing) "停止试听" else "试听") }
                }
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Badge(text = "当前", highlight = true)
                }
                Spacer(Modifier.width(6.dp))
                Badge(text = voiceStatusLabel(voice), highlight = voice.usable && voice.isOffline)
            }
            Spacer(Modifier.height(2.dp))
            Text(text = voiceSubtitle(voice), style = MaterialTheme.typography.labelSmall, color = Muted)
            voice.unusableReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(Modifier.height(2.dp))
                Text(text = reason, style = MaterialTheme.typography.labelSmall, color = Accent)
            }
        }
    }
}

internal fun VoiceDescriptor.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return listOf(displayName, packName, engineCode, voiceCode, systemName)
        .any { it.contains(needle, ignoreCase = true) }
}
