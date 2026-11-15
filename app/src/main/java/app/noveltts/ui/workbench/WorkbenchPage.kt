package app.noveltts.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.common.SectionCard
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.common.VoiceField
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlin.math.roundToInt

private const val SAMPLE_TEXT =
    "你好，欢迎使用 NovelTTS。选择喜欢的声音，让每一段文字都变得生动。\n\n" +
        "他压低声音说：“今晚的月色真美。”"

@Composable
fun WorkbenchPage(state: MainUiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("文字 · 声音 · 表达", style = MaterialTheme.typography.labelMedium, color = Accent)
        Text(
            text = "让文字，开口说话。",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            text = "输入一段文字，为旁白和对话选择不同音色。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        SectionCard {
            SliderRow("语速", "慢", "快", state.speed, viewModel::updateSpeed)
            SliderRow("音量", "轻", "响", state.volume, viewModel::updateVolume)
            SliderRow("音调", "低", "高", state.pitch, viewModel::updatePitch)
        }

        SectionTitle("朗读音色")
        VoiceField("旁白音色", state.narratorVoiceId, state.voices, viewModel::selectNarrator)
        VoiceField("对话音色", state.dialogueVoiceId, state.voices, viewModel::selectDialogue)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("试听文本", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.updateText(SAMPLE_TEXT) }) { Text("填入示例") }
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::updateText,
            placeholder = { Text("在这里输入或粘贴要朗读的文字…") },
            minLines = 5,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("分段预览（${state.preview.size}）")
        if (state.preview.isEmpty()) {
            EmptyState("输入文本后，这里会显示旁白与对话的分段结果。")
        } else {
            state.preview.forEach { segment -> PreviewRow(segment) }
        }
        state.pipelineIssues.forEach { issue ->
            Text(
                text = "⚠ ${issue.field}：${issue.message}",
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    startLabel: String,
    endLabel: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.width(10.dp))
        Text(startLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        )
        Text(endLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.width(8.dp))
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
            modifier = Modifier.width(32.dp),
        )
    }
}
