package app.noveltts.ui.packs

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.VoicePack
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

private const val VOICE_PREVIEW_LIMIT = 6

@Composable
fun PacksPage(state: MainUiState, viewModel: MainViewModel, onImport: () -> Unit) {
    var pendingRemoval by remember { mutableStateOf<VoicePack?>(null) }

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
            text = "支持原版包（config.yaml + engines.yaml）与旧版 pack.json 包。包只声明音色，安装后即可在系统 TTS 中选择。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, enabled = state.packBusyId == null) { Text("导入本地 zip") }
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
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.refreshCatalog() }, enabled = state.packBusyId == null) { Text("刷新") }
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

        SectionTitle("已安装（${state.packs.size}）")
        if (state.packs.isEmpty()) {
            EmptyState("还没有语音包。可导入本地 zip，或填写在线目录后刷新下载。")
        }
        state.packs.forEach { pack ->
            PackCard(
                pack = pack,
                busy = state.packBusyId != null,
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
private fun PackCard(pack: VoicePack, busy: Boolean, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    text = "引擎：" + pack.engines.joinToString { "${it.code}${if (it.supported) "" else "（未支持）"}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            pack.voices.take(VOICE_PREVIEW_LIMIT).forEach { voice ->
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
            if (pack.voices.size > VOICE_PREVIEW_LIMIT) {
                Text(
                    text = "… 还有 ${pack.voices.size - VOICE_PREVIEW_LIMIT} 个音色",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove, enabled = !busy) { Text("删除") }
            }
        }
    }
}
