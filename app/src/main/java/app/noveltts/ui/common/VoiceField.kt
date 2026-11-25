package app.noveltts.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

/** 显示当前选中音色，点击「选择」弹出 [VoicePickerSheet]。 */
@Composable
fun VoiceField(
    label: String,
    voiceId: String,
    voices: List<VoiceDescriptor>,
    onSelect: (String) -> Unit,
    onPreview: ((VoiceDescriptor) -> Unit)? = null,
    onStopPreview: (() -> Unit)? = null,
    previewVoiceId: String? = null,
    previewStatus: String = "",
    previewError: String? = null,
    enabled: Boolean = true,
) {
    var showPicker by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.systemName == voiceId }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconGlyph(AppIcon.VOICE, size = 20.dp, tint = Muted)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
                Text(
                    text = selected?.displayName ?: if (voiceId.isBlank()) "未选择" else "原音色暂不可用",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                if (selected != null) {
                    Text(
                        text = if (selected.usable) voiceSubtitle(selected) else selected.unusableReason ?: "当前不可用，原选择已保留",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                if (selected == null && voiceId.isNotBlank()) Text("原选择已保留，可重新导入语音包或更换音色",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                if (voices.none { it.usable }) Text("暂无可用音色，请先检查语音包或许可证",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            TextButton(
                onClick = { showPicker = true },
                enabled = enabled && voices.any { it.usable },
            ) {
                Text("选择")
                Spacer(Modifier.width(2.dp))
                AppIconGlyph(AppIcon.CHEVRON_DOWN, size = 16.dp)
            }
        }
    }

    if (showPicker) {
        VoicePickerSheet(
            title = label,
            voices = voices,
            selectedId = voiceId,
            onSelect = onSelect,
            onDismiss = { onStopPreview?.invoke(); showPicker = false },
            onPreview = onPreview,
            onStopPreview = onStopPreview,
            previewVoiceId = previewVoiceId,
            previewStatus = previewStatus,
            previewError = previewError,
        )
    }
}
