package app.noveltts.ui.packs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.VoicePack
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

private const val VOICE_COLLAPSED_LIMIT = 2
private const val VOICE_EXPANDED_LIMIT = 10

@Composable
fun PacksPage(state: MainUiState, viewModel: MainViewModel, onImport: () -> Unit) {
    var resetOverrides by rememberSaveable { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<VoicePack?>(null) }
    var browsingPackId by rememberSaveable { mutableStateOf<String?>(null) }

    if (resetOverrides) ConfirmDialog(title = "重置音色配置？", message = "将恢复语音包默认设置。原配置文件会另存保留，角色和语音包不受影响。",
        confirmLabel = "重置", dangerous = true, onConfirm = { resetOverrides = false; viewModel.resetVoiceOverrides() },
        onDismiss = { resetOverrides = false })
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "语音包",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            text = "支持原版包（config.yaml + engines.yaml）与旧版 pack.json 包。原版包中的可用音色会注册到系统 TTS；离线音色另需模型与合法许可证。旧版包仅保留导入兼容，不注册系统音色。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, enabled = state.packBusyId == null) {
                AppIconGlyph(AppIcon.DOWNLOAD, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("导入本地 zip")
            }
            if (state.isImportingPack) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.catalogUrl,
                onValueChange = viewModel::setCatalogUrl,
                label = { Text("在线目录地址") },
                placeholder = { Text("https://…/catalog.json") },
                leadingIcon = { AppIconGlyph(AppIcon.GLOBE, size = 18.dp, tint = Muted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.refreshCatalog() }, enabled = state.packBusyId == null) {
                AppIconGlyph(AppIcon.REFRESH, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("刷新")
            }
        }

        if (state.packProgress in 0..100) {
            LinearProgressIndicator(
                progress = { state.packProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.packStatus?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = Ink)
        }

        state.voiceOverridesError?.let { error ->
            Text("音色配置无法读取：$error", color = MaterialTheme.colorScheme.error)
            Text("暂按语音包默认配置朗读；修复前无法保存音色设置。")
            TextButton(onClick = viewModel::recoverVoiceOverrides) { Text("恢复音色配置备份") }
            TextButton(onClick = { resetOverrides = true }) { Text("重置音色配置") }
        }
        state.packProblems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        val unavailable = state.voices.filter { !it.usable }.groupingBy { it.unusableReason ?: "未知原因" }.eachCount()
        unavailable.forEach { (reason, count) -> Text("$count 个音色不可用：$reason", style = MaterialTheme.typography.bodySmall) }
        SectionTitle("已安装（${state.packs.size}）")
        if (state.packs.isEmpty()) {
            EmptyState("还没有语音包。可导入本地 zip，或填写在线目录后刷新下载。", icon = AppIcon.FOLDER)
        }
        state.packs.forEach { pack ->
            PackCard(
                pack = pack,
                busy = state.packBusyId != null,
                browsableCount = state.voices.count { it.packId == pack.id },
                onBrowse = { browsingPackId = pack.id },
                onRemove = { pendingRemoval = pack },
            )
        }

        SectionTitle("在线语音包（${state.catalog.size}）")
        if (state.catalog.isEmpty()) {
            EmptyState("填写目录地址后点「刷新」，这里会列出可下载的语音包。")
        }
        state.catalog.forEach { entry ->
            CatalogRow(
                entry = entry,
                installed = state.packs.any { it.id == entry.id },
                busy = state.packBusyId == entry.id,
                enabled = state.packBusyId == null,
                onDownload = { viewModel.downloadPack(entry) },
            )
        }
    }

    // 按 id 取当前包：包被删除或重新导入后，弹窗随状态关闭或刷新。
    state.packs.firstOrNull { it.id == browsingPackId }?.let { pack ->
        PackVoicesSheet(
            pack = pack,
            state = state,
            viewModel = viewModel,
            onDismiss = { browsingPackId = null },
        )
    }

    pendingRemoval?.let { pack ->
        ConfirmDialog(
            title = "删除语音包",
            message = "确定删除「${pack.name}」？其中 ${pack.voices.size} 个音色将不可用，引用它们的规则会回落到默认音色。",
            dangerous = true,
            onConfirm = {
                viewModel.removePack(pack)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@Composable
private fun PackCard(pack: VoicePack, busy: Boolean, browsableCount: Int, onBrowse: () -> Unit, onRemove: () -> Unit) {
    // 展开状态只属于这张卡片；全量列表放在 PackVoicesSheet 的 LazyColumn 里，卡片内最多铺 10 行。
    var expanded by remember(pack.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconGlyph(AppIcon.FOLDER, size = 18.dp, tint = Muted)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${pack.name}  v${pack.version}",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                Badge(text = if (pack.isOriginal) "原版包" else "兼容包", highlight = pack.isOriginal)
            }
            val meta = buildString {
                append("${pack.voices.size} 个音色")
                if (pack.author.isNotBlank()) append(" · ${pack.author}")
            }
            Text(text = meta, style = MaterialTheme.typography.labelSmall, color = Muted)
            if (pack.description.isNotBlank()) {
                Text(text = pack.description, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (pack.engines.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pack.engines.forEach { engine ->
                        val count = pack.voices.count { it.engineCode == engine.code }
                        Badge(
                            text = "${engine.code} $count" + if (engine.supported) "" else " · 未支持",
                            highlight = engine.supported,
                        )
                    }
                }
            }
            val shown = if (expanded) VOICE_EXPANDED_LIMIT else VOICE_COLLAPSED_LIMIT
            pack.voices.take(shown).forEach { voice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = voice.name, style = MaterialTheme.typography.bodySmall, color = Ink)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = voice.engineCode ?: voice.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.weight(1f),
                    )
                    Badge(text = if (voice.isOnline) "在线" else "离线")
                }
            }
            if (expanded && pack.voices.size > VOICE_EXPANDED_LIMIT) {
                Text(
                    text = "… 还有 ${pack.voices.size - VOICE_EXPANDED_LIMIT} 个音色，可在「查看全部」中浏览",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (pack.voices.size > VOICE_COLLAPSED_LIMIT) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开更多")
                    Spacer(Modifier.width(4.dp))
                    AppIconGlyph(if (expanded) AppIcon.CHEVRON_UP else AppIcon.CHEVRON_DOWN, size = 16.dp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 只有引擎受支持的音色才进入音色列表，数量以实际可浏览的为准。
                if (browsableCount > 0) {
                    TextButton(onClick = onBrowse) { Text("查看全部 $browsableCount 个音色") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove, enabled = !busy) {
                    AppIconGlyph(AppIcon.DELETE, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("删除")
                }
            }
        }
    }
}
