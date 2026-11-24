package app.noveltts.ui.packs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.data.voicepack.VoicePack
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.common.FilterChipRow
import app.noveltts.ui.common.PackHeader
import app.noveltts.ui.common.SearchField
import app.noveltts.ui.common.VoiceFilter
import app.noveltts.ui.common.VoiceOptionCard
import app.noveltts.ui.common.matchesQuery
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 语音包的全部音色浏览弹窗：可搜索、按在线/离线筛选、按引擎分组（吸顶组头）。
 * 只浏览与试听，不做选择；不可用音色保留在列表里并显示原因。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PackVoicesSheet(
    pack: VoicePack,
    state: MainUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var localeFilter by rememberSaveable { mutableStateOf("") }
    var genderFilter by rememberSaveable { mutableStateOf(-2) }
    var filter by rememberSaveable { mutableStateOf(VoiceFilter.ALL) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    val voices = remember(state.voices, pack.id) { state.voices.filter { it.packId == pack.id } }
    val visible = remember(voices, query, filter, localeFilter, genderFilter) {
        voices.filter { voice ->
            val matchesFilter = when (filter) {
                VoiceFilter.ALL -> true
                VoiceFilter.ONLINE -> voice.isOnline
                VoiceFilter.OFFLINE -> voice.isOffline
                VoiceFilter.FAVORITES -> voice.favorite
            }
            matchesFilter && voice.matchesQuery(query) &&
                (localeFilter.isEmpty() || voice.localeTag == localeFilter) &&
                (genderFilter == -2 || if (genderFilter == -1) voice.gender !in 1..2 else voice.gender == genderFilter)
        }
    }
    // 按引擎分组：组顺序取音色原始出现顺序，组内可用优先、再按名称。
    val groups: List<Pair<String, List<VoiceDescriptor>>> = remember(visible) {
        visible.map { it.engineCode }.distinct().map { engine ->
            engine to visible
                .filter { it.engineCode == engine }
                .sortedWith(compareByDescending<VoiceDescriptor> { it.usable }.thenBy { it.displayName })
        }
    }
    val unlisted = pack.voices.size - voices.size
    val hideThenDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    voices.firstOrNull { it.systemName == editingId }?.let { voice ->
        VoiceOverrideEditor(voice, state.voiceOverrides[voice.systemName], viewModel) { editingId = null }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${pack.name} 的音色",
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
                SearchField(value = query, onValueChange = { query = it }, placeholder = "搜索音色或引擎")
            }
            item(key = "filter") {
                FilterChipRow(
                    options = VoiceFilter.entries.map { it to it.label },
                    selected = filter,
                    onSelect = { filter = it },
                )
            }
            item(key = "locale-filter") {
                FilterChipRow(options = listOf("" to "全部语言") + voices.map { it.localeTag }.distinct().sorted().map { it to it }, selected = localeFilter, onSelect = { localeFilter = it })
            }
            item(key = "gender-filter") {
                FilterChipRow(options = listOf(-2 to "全部性别", 1 to "男声", 2 to "女声", -1 to "未知"), selected = genderFilter, onSelect = { genderFilter = it })
            }
            item(key = "count") {
                // 试听状态固定占一行：出现或消失时不让整个列表上下错位。
                Text(
                    text = state.previewError ?: state.statusMessage.takeIf { state.previewVoiceId != null }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    minLines = 1,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = "${visible.size} / ${voices.size} 个音色" +
                        if (unlisted > 0) "，另有 $unlisted 个所属引擎未支持" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        if (voices.isEmpty()) {
                            "这个语音包里没有可列出的音色。"
                        } else {
                            "没有匹配的音色，换个关键词或筛选条件试试。"
                        },
                    )
                }
            } else {
                groups.forEach { (engine, engineVoices) ->
                    stickyHeader(key = "engine:$engine") {
                        PackHeader(name = engine, count = engineVoices.size)
                    }
                    items(engineVoices, key = { "voice:${it.systemName}" }) { voice ->
                        val playing = state.previewVoiceId == voice.systemName
                        val togglePreview: () -> Unit = {
                            if (playing) viewModel.stopPreview() else viewModel.previewVoice(voice)
                        }
                        // 仅浏览：点整行与点「试听」等效，不产生选中状态；不可用音色整行不可点。
                        VoiceOptionCard(
                            voice = voice,
                            selected = false,
                            onClick = togglePreview,
                            onPreview = togglePreview.takeIf { voice.usable },
                            playing = playing,
                        )
                        TextButton(onClick = { editingId = voice.systemName }, enabled = state.voiceOverridesError == null) {
                            Text((if (voice.favorite) "已收藏 · " else "") + "配置别名、启停和默认倍率")
                        }
                    }
                }
            }
        }
    }
}
