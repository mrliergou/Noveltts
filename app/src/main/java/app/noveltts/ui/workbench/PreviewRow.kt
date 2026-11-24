package app.noveltts.ui.workbench

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.noveltts.ui.PreviewSegment
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/**
 * 单条分段预览：序号 + 说话人徽标 + 音色 + 「试听」按钮，正在播放的分段高亮。
 * [index] 始终是分段在完整预览里的原始下标，筛选后也不重排。
 */
@Composable
fun PreviewRow(
    segment: PreviewSegment,
    index: Int,
    isPlaying: Boolean,
    onPreview: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val speaker = segment.roleName ?: if (segment.isDialogue) "对话" else "旁白"
    val hasRole = segment.roleName != null
    // 读屏把整行念成一句；「试听」按钮自带点击语义，不会被合并进来。
    val spoken = listOf(
        "第 ${index + 1} 段",
        speaker,
        (if (segment.uncertain) "不确定，" else "") + segment.explanation,
        segment.text,
    ).filter { it.isNotBlank() }.joinToString("，")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isPlaying) {
            colors.primary.copy(alpha = 0.12f)
        } else {
            colors.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.width(18.dp),
                )
                // 说话人徽标：有角色时高亮显示角色名，大字号下单行省略，不挤掉右侧按钮。
                Text(
                    text = speaker,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasRole) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .background(
                            color = if (hasRole) colors.primary.copy(alpha = 0.12f) else colors.surface,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = segment.voiceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPreview) {
                    AppIconGlyph(if (isPlaying) AppIcon.STOP else AppIcon.PLAY, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(text = if (isPlaying) "播放中" else "试听")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(text = segment.text, style = MaterialTheme.typography.bodyMedium, color = Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (segment.uncertain) {
                    AppIconGlyph(AppIcon.INFO, size = 14.dp, tint = Accent)
                    Spacer(Modifier.width(4.dp))
                    Text(text = "不确定 · ", style = MaterialTheme.typography.labelSmall, color = Accent)
                }
                Text(text = segment.explanation, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}
