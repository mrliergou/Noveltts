package app.noveltts.ui.workbench

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.noveltts.ui.PreviewSegment
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/** 单条分段预览：序号 + 类型 + 音色 + 角色 + 「试听」按钮，正在播放的分段高亮。 */
@Composable
fun PreviewRow(
    segment: PreviewSegment,
    index: Int,
    isPlaying: Boolean,
    onPreview: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isPlaying) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.width(18.dp),
                )
                Badge(text = if (segment.isDialogue) "对话" else "旁白", highlight = segment.isDialogue)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = segment.voiceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
                segment.roleName?.let { role ->
                    Text(
                        text = "角色：$role",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
                TextButton(onClick = onPreview) {
                    AppIconGlyph(if (isPlaying) AppIcon.STOP else AppIcon.PLAY, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(text = if (isPlaying) "播放中" else "试听")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(text = segment.text, style = MaterialTheme.typography.bodyMedium, color = Ink)
            Text(text = segment.explanation, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}
