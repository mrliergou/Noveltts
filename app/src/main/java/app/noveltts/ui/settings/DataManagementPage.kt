package app.noveltts.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.backup.BackupRestorePreview
import app.noveltts.data.config.RuleImportConflict
import app.noveltts.data.config.RuleImportStrategy
import app.noveltts.data.config.RulesImportPreview
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleRule
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.common.SectionCard
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.common.formatSize
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.noveltts.data.backup.BackupManager
import app.noveltts.data.config.RuleRecoveryCandidate

/** 数据管理：备份导出 / 还原导入 / 缓存统计与清理。 */
@Composable
internal fun DataManagementSettings(state: MainUiState, viewModel: MainViewModel) {
    var includeSensitive by rememberSaveable { mutableStateOf(false) }
    var confirmSensitive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupRestorePreview?>(null) }
    var pendingRecovery by remember { mutableStateOf<RuleRecoveryCandidate?>(null) }
    var recoveryError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.refreshCacheUsage(); viewModel.refreshRecoveryCandidates() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val result = viewModel.exportBackupTo(uri, includeSensitive)
                busy = false
                message = result.getOrElse { "导出失败：" + (it.message ?: "未知错误") }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val result = viewModel.prepareBackupRestore(uri)
                busy = false
                result.fold(
                    onSuccess = {
                        preview = it
                        message = null
                    },
                    onFailure = { message = "无法读取备份：" + (it.message ?: "未知错误") },
                )
            }
        }
    }

    SectionTitle("备份导出")
    SectionCard {
        Text(
            text = "导出角色、替换规则与设置。语音包与最近文本不在备份范围内。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "包含敏感凭据", color = Ink)
                Text(
                    text = "许可证与访问令牌会以明文写入备份文件，请妥善保管。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            Checkbox(
                checked = includeSensitive,
                onCheckedChange = { includeSensitive = it },
            )
        }
        Button(
            onClick = {
                if (includeSensitive) confirmSensitive = true else exportLauncher.launch(viewModel.backupFileName())
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppIconGlyph(
                icon = AppIcon.UPLOAD,
                size = 18.dp,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text("导出备份…")
        }
        Text(
            text = "文件名：" + viewModel.backupFileName(),
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }

    SectionTitle("还原导入")
    SectionCard {
        Text(
            text = "先预览冲突再决定如何合并；写入前自动生成 .bak，失败会回滚原有规则。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppIconGlyph(
                icon = AppIcon.DOWNLOAD,
                size = 18.dp,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text("选择备份文件…")
        }
        Text(
            text = "只接受 schema 为 1 的 NovelTTS 备份；更高版本会被拒绝。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }

    SectionTitle("本机恢复来源")
    SectionCard {
        Text("先校验备份，再选择恢复。损坏副本会显示原因；恢复前保留当前文件。", style = MaterialTheme.typography.labelSmall, color = Muted)
        TextButton({ viewModel.refreshRecoveryCandidates() }) { Text("重新检查") }
        if (state.recoveryCandidates.isEmpty()) Text("目前没有本机规则备份", color = Muted)
        state.recoveryCandidates.forEach { candidate ->
            val kind = if (candidate.kind == app.noveltts.data.config.RulesKind.ROLE) "角色" else "替换"
            Text("$kind · ${candidate.fileName} · ${candidate.ruleCount} 条", style = MaterialTheme.typography.labelMedium)
            candidate.error?.let { Text("无法恢复：$it", color = Accent, style = MaterialTheme.typography.labelSmall) }
            TextButton({ pendingRecovery = candidate; recoveryError = null }, enabled = candidate.valid && !busy) { Text("预览恢复") }
        }
    }
    pendingRecovery?.let { candidate ->
        ConfirmDialog(title = "从本机备份恢复？", message = "${candidate.fileName} 中有 ${candidate.ruleCount} 条规则。" +
            (recoveryError?.let { "\n恢复失败：$it" } ?: ""), confirmLabel = "确认恢复", dangerous = true,
            onConfirm = { if (!busy) scope.launch {
                busy = true
                val result = withContext(Dispatchers.IO) { viewModel.restoreRuleCandidate(candidate) }
                busy = false
                result.onSuccess { message = it; pendingRecovery = null }.onFailure { recoveryError = it.message }
            } }, onDismiss = { if (!busy) pendingRecovery = null })
    }

    SectionTitle("缓存")
    SectionCard {
        Text(
            text = formatSize(state.cacheBytes) + " / " + state.cacheFileCount + " 个文件",
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
        )
        Text(
            text = "统计并清理临时音频；正在读写的音频会保留。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        val total = state.cacheHits + state.cacheMisses
        Text(if (total == 0L) "本次启动尚无缓存请求" else "本次启动命中率 ${state.cacheHits * 100 / total}% · 命中 ${state.cacheHits} / 未命中 ${state.cacheMisses}",
            style = MaterialTheme.typography.labelSmall, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::refreshCacheUsage) {
                AppIconGlyph(
                    icon = AppIcon.REFRESH,
                    size = 18.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text("刷新")
            }
            OutlinedButton(
                onClick = viewModel::clearCache,
                enabled = state.cacheFileCount > 0,
            ) {
                AppIconGlyph(
                    icon = AppIcon.DELETE,
                    size = 18.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text("清理缓存")
            }
        }
    }

    message?.let { text ->
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = Accent)
    }

    if (confirmSensitive) {
        ConfirmDialog(
            title = "包含敏感凭据？",
            message = "备份文件里的许可证与访问令牌是不加密的明文，任何拿到文件的人都能读取。确认继续导出？",
            confirmLabel = "仍然导出",
            dangerous = true,
            onConfirm = {
                confirmSensitive = false
                exportLauncher.launch(viewModel.backupFileName())
            },
            onDismiss = { confirmSensitive = false },
        )
    }

    preview?.let { current ->
        BackupRestoreSheet(
            preview = current, state = state, viewModel = viewModel,
            onApply = { strategy, applySettings, applyCredentials, mapping, keepMissing, repair ->
                busy = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        viewModel.applyBackupRestore(current, strategy, applySettings, applyCredentials, mapping, keepMissing, repair)
                    }
                    message = result.getOrElse { "还原失败：" + (it.message ?: "未知错误") }
                    if (result.isSuccess) preview = null
                    result
                } finally {
                    busy = false
                }
            },
            onDismiss = { preview = null },
        )
    }
}

/** 还原前的冲突预览与策略选择，复用阶段 2 的 data 层冲突计算。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupRestoreSheet(
    preview: BackupRestorePreview,
    state: MainUiState,
    viewModel: MainViewModel,
    onApply: suspend (RuleImportStrategy, Boolean, Boolean, Map<String, String>, Boolean, Boolean) -> Result<String>,
    onDismiss: () -> Unit,
) {
    var applying by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !applying })
    val scope = rememberCoroutineScope()
    var mapping by remember { mutableStateOf(emptyMap<String, String>()) }
    var keepMissing by remember { mutableStateOf(false) }
    var repair by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val damaged = state.rolesLoadError != null || state.replacementsLoadError != null || state.profilesLoadError != null
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
    var strategy by remember { mutableStateOf(RuleImportStrategy.SKIP) }
    var applySettings by remember { mutableStateOf(preview.backup.settingsCount > 0) }
    val missing = BackupManager.missingVoiceIds(preview, state.usableVoices.map { it.systemName }.toSet(), applySettings)
    var applyCredentials by remember { mutableStateOf(false) }
    val credentials = preview.backup.credentials
    ModalBottomSheet(onDismissRequest = { if (!applying) onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "还原备份",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Text(
                text = "导出时间：" + preview.backup.exportedAt.ifBlank { "未知" } +
                    " · 版本 " + preview.backup.appVersion.ifBlank { "未知" },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            if (preview.isEmpty) {
                Text(
                    text = "备份里没有规则条目，仍然可以恢复设置。",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
            }
            RulePreviewBlock("角色规则", preview.roles, RoleRule::name)
            RulePreviewBlock("替换规则", preview.replacements, ReplacementRule::name)
            SectionTitle("冲突处理")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleImportStrategy.entries.forEach { option ->
                    FilterChip(
                        selected = strategy == option,
                        enabled = !applying,
                        onClick = { strategy = option },
                        label = { Text(option.label) },
                    )
                }
            }
            if (strategy == RuleImportStrategy.REPLACE_ALL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppIconGlyph(icon = AppIcon.WARNING, size = 16.dp, tint = Accent)
                    Text(
                        text = "全部覆盖会先清空当前所有规则，只保留备份里的内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                    )
                }
            }
            Text("阅读方案 ${preview.backup.profiles.size} 个", style = MaterialTheme.typography.labelMedium)
            VoiceRemappingFields(missing, preview.backup.voiceHints, state, viewModel, mapping, enabled = !applying) { id, voice -> mapping = mapping + (id to voice) }
            if (missing.isNotEmpty()) ToggleSettingRow("保留未安装音色的引用，稍后安装语音包", keepMissing, { keepMissing = it }, enabled = !applying)
            if (damaged) ToggleSettingRow("使用备份修复当前不可读的配置", repair, { repair = it }, enabled = !applying)
            applyError?.let { Text("还原失败：$it", color = Accent) }
            SectionTitle("可选内容")
            ToggleSettingRow(
                title = "恢复设置",
                checked = applySettings,
                onCheckedChange = { applySettings = it },
                enabled = !applying,
                subtitle = "白名单内 " + preview.backup.settingsCount + " 项，不含音频文件授权与访问令牌。",
            )
            if (credentials != null) {
                ToggleSettingRow(
                    title = "恢复敏感凭据",
                    checked = applyCredentials,
                    onCheckedChange = { applyCredentials = it },
                    enabled = !applying,
                    subtitle = "备份包含许可证或访问令牌，勾选后写入本机加密存储。",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss, enabled = !applying, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        if (applying) return@Button
                        val selectedStrategy = strategy
                        val restoreSettings = applySettings
                        val restoreCredentials = applyCredentials
                        val selectedMapping = mapping
                        val keepReferences = keepMissing
                        val repairUnreadable = repair
                        applying = true
                        scope.launch {
                            applyError = null
                            try {
                                onApply(selectedStrategy, restoreSettings, restoreCredentials, selectedMapping, keepReferences, repairUnreadable)
                                    .onFailure { applyError = it.message ?: "未知错误" }
                            } finally {
                                applying = false
                            }
                        }
                    },
                    enabled = !applying && (!damaged || repair) &&
                        (keepMissing || missing.all { mapping[it] != null }),
                    modifier = Modifier.weight(1f),
                ) {
                    AppIconGlyph(
                        icon = AppIcon.CHECK,
                        size = 18.dp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(if (applying) "正在还原…" else "开始还原")
                }
            }
        }
    }
}

@Composable
private fun <T> RulePreviewBlock(
    kindLabel: String,
    preview: RulesImportPreview<T>,
    nameOf: (T) -> String,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = kindLabel, color = Ink, modifier = Modifier.weight(1f))
            Badge(text = "新增 " + preview.newCount)
            if (preview.conflictCount > 0) {
                Badge(text = "冲突 " + preview.conflictCount, highlight = true)
            }
        }
        val conflicts = preview.entries.filter { it.conflict != RuleImportConflict.NONE }
        if (conflicts.isEmpty()) {
            Text(
                text = "没有冲突，将新增 " + preview.entries.size + " 条。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        } else {
            conflicts.take(5).forEach { entry ->
                Text(
                    text = "· " + nameOf(entry.rule) + "（" + entry.conflict.label + "）",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (conflicts.size > 5) {
                Text(
                    text = "… 还有 " + (conflicts.size - 5) + " 条冲突",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
        if (preview.newGroups.isNotEmpty()) {
            Text(
                text = "新分组：" + preview.newGroups.joinToString("、") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}
