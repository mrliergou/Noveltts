package app.noveltts.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.noveltts.data.config.ProfileSwitchPreview
import app.noveltts.data.config.ReadingProfile
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.SectionCard
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.common.VoiceField
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingProfilesSection(state: MainUiState, viewModel: MainViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ProfileSwitchPreview?>(null) }
    var pendingDeletion by remember { mutableStateOf<ReadingProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SectionTitle("阅读方案")
    SectionCard {
        Text("保存音色、朗读参数与启用的规则分组。方案引用现有规则，切换前会检查缺失引用。", style = MaterialTheme.typography.labelSmall, color = Muted)
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("方案名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (busy) return@Button
            val draftName = name
            busy = true
            scope.launch { try {
                val result = withContext(Dispatchers.IO) { viewModel.saveReadingProfile(draftName) }
                message = result.getOrElse { "保存失败：${it.message}" }
                if (result.isSuccess) name = ""
            } finally { busy = false } }
        }, enabled = !busy && name.isNotBlank() && state.profilesLoadError == null) { Text(if (busy) "正在处理…" else "保存当前配置为方案") }
        state.profilesLoadError?.let { Text(it, color = Accent) }
        state.readingProfiles.forEach { profile ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name, modifier = Modifier.weight(1f))
                if (profile.id == state.activeProfileId) Badge("已应用")
            }
            Row {
                TextButton({ preview = viewModel.previewReadingProfile(profile) }, enabled = !busy) { Text("预览并切换") }
                TextButton({ pendingDeletion = profile }, enabled = !busy) { Text("删除方案") }
            }
        }
        message?.let { Text(it, color = Accent) }
    }
    pendingDeletion?.let { profile ->
        ConfirmDialog(title = "删除阅读方案", message = "删除「${profile.name}」？当前朗读配置与规则会保留。", dangerous = true,
            onDismiss = { pendingDeletion = null }, onConfirm = {
                if (!busy) {
                    pendingDeletion = null
                    busy = true
                    scope.launch { try {
                        message = withContext(Dispatchers.IO) { viewModel.deleteReadingProfile(profile) }.getOrElse { "删除失败：${it.message}" }
                    } finally { busy = false } }
                }
            })
    }
    preview?.let { current ->
        var mapping by remember(current.profile.id) { mutableStateOf(emptyMap<String, String>()) }
        var dropMissing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val missingGroups = current.missingRoleGroups + current.missingReplacementGroups
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })
        DisposableEffect(current.profile.id) { onDispose { viewModel.stopPreview() } }
        ModalBottomSheet(onDismissRequest = { if (!busy) preview = null }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("切换方案「${current.profile.name}」", style = MaterialTheme.typography.titleLarge)
                Text("语速 ${current.profile.speed} · 音调 ${current.profile.pitch} · 音量 ${current.profile.volume}")
                Text("角色分组 ${current.profile.roleGroups.size} 个 · 替换分组 ${current.profile.replacementGroups.size} 个")
                VoiceRemappingFields(current.missingVoices, current.profile.voiceHints, state, viewModel, mapping, enabled = !busy) { id, voice -> mapping = mapping + (id to voice) }
                if (missingGroups.isNotEmpty()) {
                    Text("已删除的分组：${missingGroups.joinToString { current.profile.groupHints[it] ?: "未知分组" }}", color = Accent)
                    ToggleSettingRow("忽略已删除的分组", dropMissing, { dropMissing = it }, enabled = !busy)
                }
                error?.let { Text(it, color = Accent) }
                Button(onClick = {
                    if (busy) return@Button
                    val selectedMapping = mapping
                    val ignoreMissingGroups = dropMissing
                    busy = true
                    scope.launch { try {
                        val result = withContext(Dispatchers.IO) { viewModel.applyReadingProfile(current, selectedMapping, ignoreMissingGroups) }
                        result.onSuccess { message = it; preview = null }.onFailure { error = it.message }
                    } finally { busy = false } }
                }, enabled = !busy && current.missingVoices.all { it in mapping } && (missingGroups.isEmpty() || dropMissing), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在应用…" else "确认切换")
                }
            }
        }
    }
}

@Composable
internal fun VoiceRemappingFields(missing: List<String>, hints: Map<String, String>, state: MainUiState,
    viewModel: MainViewModel, mapping: Map<String, String>, enabled: Boolean = true, onSelect: (String, String) -> Unit) {
    if (missing.isEmpty()) return
    SectionTitle("缺失音色")
    missing.forEach { id ->
        VoiceField(label = hints[id] ?: "${id.substringAfterLast(':')}（未安装或不可用）", voiceId = mapping[id].orEmpty(),
            voices = state.usableVoices, onSelect = { onSelect(id, it) }, onPreview = viewModel::previewVoice,
            onStopPreview = viewModel::stopPreview, previewVoiceId = state.previewVoiceId, previewStatus = state.statusMessage, previewError = state.previewError, enabled = enabled)
    }
}
