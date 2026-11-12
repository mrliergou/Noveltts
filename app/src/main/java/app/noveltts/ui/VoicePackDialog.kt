package app.noveltts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.noveltts.data.voicepack.CatalogEntry
import app.noveltts.data.voicepack.PackVoice
import app.noveltts.data.voicepack.VoicePack
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/**
 * 语音包管理界面：导入本地 zip、从在线目录下载、查看/应用/删除已安装语音包。
 */
@Composable
fun VoicePackDialog(
    packs: List<VoicePack>,
    catalog: List<CatalogEntry>,
    catalogUrl: String,
    busyId: String?,
    progress: Int,
    status: String?,
    onCatalogUrlChange: (String) -> Unit,
    onRefreshCatalog: () -> Unit,
    onImport: () -> Unit,
    onDownload: (CatalogEntry) -> Unit,
    onApplyVoice: (VoicePack, PackVoice) -> Unit,
    onRemove: (VoicePack) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("语音包", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                        Text(
                            text = "语音包声明可用音色，应用后会自动切换到对应的引擎与语音。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onImport, enabled = busyId == null) { Text("导入本地 zip") }
                    Text(
                        text = "zip 根目录需要包含 pack.json",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = catalogUrl,
                        onValueChange = onCatalogUrlChange,
                        label = { Text("在线目录地址") },
                        placeholder = { Text("https://…/catalog.json") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onRefreshCatalog, enabled = busyId == null) { Text("刷新") }
                }

                status?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (progress in 0..100) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SectionTitle("已安装（${packs.size}）") }
                    if (packs.isEmpty()) {
                        item {
                            Text("还没有语音包，可导入本地 zip 或从在线目录下载。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    items(packs, key = { "pack-${it.id}" }) { pack ->
                        InstalledPackCard(
                            pack = pack,
                            onApplyVoice = { voice -> onApplyVoice(pack, voice) },
                            onRemove = { onRemove(pack) },
                        )
                    }

                    item { SectionTitle("在线语音包（${catalog.size}）") }
                    if (catalog.isEmpty()) {
                        item {
                            Text("填写目录地址后点「刷新」即可列出可下载的语音包。", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    items(catalog, key = { "catalog-${it.id}" }) { entry ->
                        CatalogRow(
                            entry = entry,
                            installed = packs.any { it.id == entry.id },
                            busy = busyId == entry.id,
                            enabled = busyId == null,
                            onDownload = { onDownload(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink)
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun InstalledPackCard(
    pack: VoicePack,
    onApplyVoice: (PackVoice) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${pack.name}  v${pack.version}", fontWeight = FontWeight.SemiBold, color = Ink)
                val subtitle = buildString {
                    append("${pack.voices.size} 个音色")
                    if (pack.author.isNotBlank()) append(" · ${pack.author}")
                }
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
                if (pack.description.isNotBlank()) {
                    Text(pack.description, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            TextButton(onClick = onRemove) { Text("删除") }
        }
        pack.voices.forEach { voice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(voice.name, style = MaterialTheme.typography.bodyMedium, color = Ink)
                    val binding = voice.engine?.let { engine ->
                        if (voice.voiceName.isNullOrBlank()) engine else "$engine · ${voice.voiceName}"
                    } ?: "未绑定引擎"
                    Text(binding, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                TextButton(onClick = { onApplyVoice(voice) }) { Text("使用") }
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${entry.name}  v${entry.version}", fontWeight = FontWeight.SemiBold, color = Ink)
            val meta = buildString {
                if (entry.voiceCount > 0) append("${entry.voiceCount} 个音色")
                if (entry.sizeBytes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatSize(entry.sizeBytes))
                }
                if (installed) {
                    if (isNotEmpty()) append(" · ")
                    append("已安装")
                }
            }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = Muted)
            if (entry.description.isNotBlank()) {
                Text(entry.description, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        when {
            busy -> Text("下载中…", style = MaterialTheme.typography.labelMedium, color = Muted)
            installed -> Text("已安装", style = MaterialTheme.typography.labelMedium, color = Muted)
            else -> Button(onClick = onDownload, enabled = enabled) { Text("下载") }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
