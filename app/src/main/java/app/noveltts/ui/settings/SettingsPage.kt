package app.noveltts.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.SectionCard
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.common.VoiceField
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted

@Composable
fun SettingsPage(
    state: MainUiState,
    viewModel: MainViewModel,
    onOpenSystemSpeechSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )

        SectionTitle("朗读行为")
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("过滤纯标点片段", color = Ink)
                    Text(
                        text = "像「……」这类没有文字的片段不送去合成。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                Switch(
                    checked = state.punctuationFilter,
                    onCheckedChange = viewModel::setPunctuationFilter,
                )
            }
        }
        VoiceField("系统默认音色", state.systemVoiceId, state.voices, viewModel::selectSystemVoice)

        SectionTitle("在线音源")
        EdgeEndpointCard(state.edgeEndpoint, viewModel::setEdgeEndpoint)

        SectionTitle("微软离线许可证")
        LicenseCard(state.licenseConfigured, state.licenseMask, viewModel::setLicense)

        SectionTitle("系统集成")
        SectionCard {
            Text(
                text = "系统音色名：multitts:<包 ID>:<引擎>:<音色>",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            OutlinedButton(onClick = onOpenSystemSpeechSettings) { Text("打开系统语音设置") }
            OutlinedButton(onClick = viewModel::clearRecentText) { Text("清除最近文本") }
        }

        Text(
            text = "首版不支持 JS 插件、自动人物识别、HTTP 转发服务和缓存系统。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}

@Composable
private fun EdgeEndpointCard(savedEndpoint: String, onSave: (String) -> Unit) {
    var endpoint by remember(savedEndpoint) { mutableStateOf(savedEndpoint) }
    SectionCard {
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("Edge 端点") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { onSave(endpoint) },
                enabled = endpoint.isNotBlank() && endpoint != savedEndpoint,
            ) { Text("保存端点") }
        }
    }
}

@Composable
private fun LicenseCard(configured: Boolean, mask: String, onSave: (String) -> Unit) {
    var license by remember { mutableStateOf("") }
    SectionCard {
        Text(
            text = if (configured) {
                "已配置：$mask"
            } else {
                "尚未配置。离线音色会显示在应用内，但不可用，也不会暴露给系统 TTS。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (configured) Ink else Muted,
        )
        OutlinedTextField(
            value = license,
            onValueChange = { license = it },
            label = { Text("Embedded Speech 许可证") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (configured) {
                TextButton(onClick = { onSave("") }) { Text("清除") }
            }
            TextButton(
                onClick = {
                    onSave(license)
                    license = ""
                },
                enabled = license.isNotBlank(),
            ) { Text("保存许可证") }
        }
    }
}
