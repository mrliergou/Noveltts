package app.noveltts.ui.packs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.noveltts.data.voicepack.*
import app.noveltts.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
internal fun VoiceOverrideEditor(voice: VoiceDescriptor, original: VoiceOverride?, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var alias by rememberSaveable(voice.systemName) { mutableStateOf(original?.alias.orEmpty()) }
    var favorite by rememberSaveable(voice.systemName) { mutableStateOf(original?.favorite ?: false) }
    var enabled by rememberSaveable(voice.systemName) { mutableStateOf(original?.enabled ?: true) }
    var rate by rememberSaveable(voice.systemName) { mutableStateOf(original?.rate?.toString().orEmpty()) }
    var pitch by rememberSaveable(voice.systemName) { mutableStateOf(original?.pitch?.toString().orEmpty()) }
    var volume by rememberSaveable(voice.systemName) { mutableStateOf(original?.volume?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("音色配置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(voice.displayName)
            Text("留空继承包默认值。倍率还会叠加角色和全局参数，最终范围：语速 0.25–4，音调 0.25–2，音量 0–1。配置对下一份计划或继续朗读生效。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(alias, { alias = it.take(120) }, label = { Text("别名") }, singleLine = true)
            Row { Checkbox(favorite, { favorite = it }); Text("收藏") }
            Row { Checkbox(enabled, { enabled = it }); Text("启用（停用保留角色引用）") }
            OutlinedTextField(rate, { rate = it }, label = { Text("默认语速（继承 ${voice.defaultSpeed}）") }, singleLine = true)
            OutlinedTextField(pitch, { pitch = it }, label = { Text("默认音调（有效上限 2）") }, singleLine = true)
            OutlinedTextField(volume, { volume = it }, label = { Text("默认音量（0–1）") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = {
            fun parse(value: String): Float? = if (value.isBlank()) null else requireNotNull(value.toFloatOrNull()) { "请输入有效倍率，或留空继承" }
            val draft = runCatching { VoiceOverride(alias.trim().ifEmpty { null }, favorite, enabled, parse(rate), parse(pitch), parse(volume)).also { it.validate() } }
            draft.onFailure { error = it.message }.onSuccess { value ->
                saving = true
                scope.launch {
                    viewModel.saveVoiceOverride(voice.systemName, value, original).fold(
                        onSuccess = { onDismiss() }, onFailure = { error = it.message })
                    saving = false
                }
            }
        }) { Text(if (saving) "保存中…" else "保存") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}