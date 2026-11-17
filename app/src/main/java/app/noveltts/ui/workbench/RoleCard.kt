package app.noveltts.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 倍率显示：一位小数能表示时用一位（1.2×），否则两位（1.25×）。 */
internal fun formatMultiplier(value: Float): String {
    val tenths = value * 10f
    val pattern = if (abs(tenths - tenths.roundToInt()) < 0.001f) "%.1f" else "%.2f"
    return String.format(Locale.ROOT, pattern, value) + "×"
}

internal fun formatVolumePercent(value: Float): String = "${(value * 100f).roundToInt()}%"

internal fun rolePlaybackSummary(rate: Float, pitch: Float, volume: Float): String =
    "语速 ${formatMultiplier(rate)} · 音调 ${formatMultiplier(pitch)} · 音量 ${formatVolumePercent(volume)}"

/**
 * 工作台的角色行：整行可点打开配置面板。自定义角色传入 [onToggle] 后行尾出现启用开关，
 * 开关是独立的触控区，不会连带触发整行点击。
 */
@Composable
internal fun RoleCard(
    name: String,
    voiceLabel: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    matchLabel: String? = null,
    roleEnabled: Boolean = true,
    editable: Boolean = true,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = editable, onClickLabel = "配置$name", role = Role.Button, onClick = onClick)
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp)
                    .alpha(if (roleEnabled) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = name, fontWeight = FontWeight.SemiBold, color = Ink)
                        if (required) {
                            Spacer(Modifier.width(6.dp))
                            Badge(text = "必选", highlight = true)
                        }
                        if (!roleEnabled) {
                            Spacer(Modifier.width(6.dp))
                            Badge(text = "已停用", highlight = true)
                        }
                    }
                    Text(
                        text = if (matchLabel == null) voiceLabel else "$voiceLabel · $matchLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                    )
                    Text(text = summary, style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                AppIconGlyph(AppIcon.CHEVRON_RIGHT, size = 18.dp, tint = Muted)
            }
            if (onToggle != null) {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .toggleable(
                            value = roleEnabled,
                            enabled = editable,
                            role = Role.Switch,
                            onValueChange = onToggle,
                        )
                        .padding(horizontal = 10.dp)
                        .semantics { contentDescription = "启用$name" },
                    contentAlignment = Alignment.Center,
                ) {
                    Switch(checked = roleEnabled, onCheckedChange = null, enabled = editable)
                }
            }
        }
    }
}
