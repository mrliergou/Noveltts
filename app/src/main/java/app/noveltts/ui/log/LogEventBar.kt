package app.noveltts.ui.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.engine.Level
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink

/**
 * 紧凑的底部事件条：只显示最近一条 WARN / ERROR，没有异常时完全不占空间。
 *
 * 用法（可选接入，不接入也不影响编译）：
 * ```kotlin
 * LogEventBar(onClick = { showLogs = true })
 * ```
 * 放在 WorkbenchPage 的底部面板上方即可。
 */
@Composable
fun LogEventBar(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val entries by DiagnosticsLog.entriesFlow.collectAsStateWithLifecycle()
    val latest = entries.lastOrNull { it.level == Level.WARN || it.level == Level.ERROR } ?: return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconGlyph(
                icon = if (latest.level == Level.ERROR) AppIcon.ERROR else AppIcon.WARNING,
                size = 18.dp,
                tint = if (latest.level == Level.ERROR) MaterialTheme.colorScheme.error else Accent,
            )
            Text(
                text = "${latest.level.label()} · ${latest.category.label()}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Accent,
                maxLines = 1,
            )
            Text(
                text = latest.message,
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Text(
                    text = "查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    maxLines = 1,
                )
                AppIconGlyph(icon = AppIcon.CHEVRON_RIGHT, size = 14.dp, tint = Accent)
            }
        }
    }
}
