package app.noveltts.ui.packs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.CatalogEntry
import app.noveltts.ui.common.formatSize
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

@Composable
fun CatalogRow(
    entry: CatalogEntry,
    installed: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${entry.name}  v${entry.version}", fontWeight = FontWeight.SemiBold, color = Ink)
                val meta = buildString {
                    if (entry.voiceCount > 0) append("${entry.voiceCount} 个音色")
                    if (entry.sizeBytes > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(formatSize(entry.sizeBytes))
                    }
                    if (entry.sha256.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("无校验和")
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(text = meta, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                if (entry.description.isNotBlank()) {
                    Text(text = entry.description, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                installed -> Text(text = "已安装", style = MaterialTheme.typography.labelMedium, color = Muted)
                else -> TextButton(onClick = onDownload, enabled = enabled) { Text("下载") }
            }
        }
    }
}
