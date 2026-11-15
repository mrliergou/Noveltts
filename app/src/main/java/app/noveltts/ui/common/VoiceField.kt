package app.noveltts.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
) {
    var showPicker by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.systemName == voiceId && it.usable }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
                Text(
                    text = selected?.displayName ?: "未选择",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                if (selected != null) {
                    Text(
                        text = voiceSubtitle(selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            }
            TextButton(
                onClick = { showPicker = true },
                enabled = voices.any { it.usable },
            ) { Text("选择") }
        }
    }

    if (showPicker) {
        VoicePickerSheet(
            title = label,
            voices = voices,
            selectedId = voiceId,
            onSelect = onSelect,
            onDismiss = { showPicker = false },
        )
    }
}
