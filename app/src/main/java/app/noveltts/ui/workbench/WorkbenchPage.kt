package app.noveltts.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noveltts.data.config.RulesKind
import app.noveltts.domain.BuiltInRole
import app.noveltts.domain.RoleRule
import app.noveltts.ui.AppSection
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.EmptyState
import app.noveltts.ui.common.SectionCard
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.rules.RuleEditorSheet
import app.noveltts.ui.rules.RuleEditorTarget
import app.noveltts.ui.rules.sortedRuleGroups
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_CUSTOM_ROLE_ROWS = 6

@Composable
fun WorkbenchPage(state: MainUiState, viewModel: MainViewModel) {
    var confirmSample by remember { mutableStateOf(false) }
    if (confirmSample) app.noveltts.ui.common.ConfirmDialog(
        title = "替换为示例文本？", message = "当前工作台文本将被示例替换。",
        confirmLabel = "替换", dangerous = true,
        onConfirm = { confirmSample = false; viewModel.updateText(SampleTexts.random(exclude = state.text)) },
        onDismiss = { confirmSample = false },
    )
    val rolesEditable = state.rolesLoadError == null
    var sheetTarget by rememberSaveable(stateSaver = RoleSheetTarget.Saver) { mutableStateOf<RoleSheetTarget?>(null) }
    val disabledGroups = remember(state.roleGroups) { state.roleGroups.filterNot { it.enabled }.mapTo(HashSet()) { it.id } }
    var advancedRule by remember { mutableStateOf<RoleRule?>(null) }
    val voiceNames = remember(state.voices) { state.voices.associate { it.systemName to it.displayName } }
    val roleGroups = remember(state.roleGroups) { sortedRuleGroups(state.roleGroups) }
    // 与角色规则页同序：先按分组顺序，再按组内 sortIndex。
    val customRoles = remember(state.roles, roleGroups) {
        val groupOrder = roleGroups.withIndex().associate { it.value.id to it.index }
        state.roles.sortedWith(
            compareBy<RoleRule> { groupOrder[it.groupId] ?: Int.MAX_VALUE }.thenBy { it.sortIndex },
        )
    }
    var onlyUncertain by rememberSaveable { mutableStateOf(false) }
    val uncertainCount = state.preview.count { it.uncertain }
    // 不确定的片段清零后筛选自动关闭，避免列表空着又没有恢复入口，也避免下次悄悄只显示一部分。
    if (onlyUncertain && uncertainCount == 0 && !state.previewBusy) onlyUncertain = false
    val showOnlyUncertain = onlyUncertain && uncertainCount > 0
    val voiceLabel: (String?) -> String = { id ->
        if (id.isNullOrBlank()) "未选择" else voiceNames[id] ?: "音色不可用"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 全局语速/音量/音调置顶：先调全局再选角色，避免每次滚到底部。
        item(key = "global") {
            val expanded = state.globalControlsExpanded
            SectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            onClickLabel = if (expanded) "折叠全局调节" else "展开全局调节",
                            role = Role.Button,
                        ) { viewModel.setGlobalControlsExpanded(!expanded) }
                        .semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("全局调节", fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(
                            text = "语速 ${state.speed} · 音量 ${state.volume} · 音调 ${state.pitch}，对所有角色生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                    AppIconGlyph(if (expanded) AppIcon.CHEVRON_UP else AppIcon.CHEVRON_DOWN, size = 18.dp, tint = Muted)
                }
                if (expanded) {
                    SliderRow(AppIcon.SPEED, "语速", "慢", "快", state.speed, viewModel::updateSpeed)
                    SliderRow(AppIcon.VOLUME, "音量", "轻", "响", state.volume, viewModel::updateVolume)
                    SliderRow(AppIcon.TUNE, "音调", "低", "高", state.pitch, viewModel::updatePitch)
                    Text("最终倍率：全局 × 角色 × 音色默认值；语速 0.25–4，音调 0.25–2，音量 0–1。实际请求值见诊断日志。", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }

        item(key = "input") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("试听文本", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (state.text.isBlank()) viewModel.updateText(SampleTexts.random()) else confirmSample = true
            }) {
                AppIconGlyph(AppIcon.TEXT, size = 16.dp)
                Spacer(Modifier.width(4.dp))
                Text("填入示例")
            }
        }
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::updateText,
            placeholder = { Text("在这里输入或粘贴要朗读的文字…") },
            minLines = 5,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )
        } }

        item(key = "current-book") {
            SectionCard {
                Text("当前书籍：${state.bookLibrary.activeBook?.name ?: "未选择（全局角色模式）"}", fontWeight = FontWeight.SemiBold)
                Text(state.booksLoadError ?: "换书后请在此切换；系统 TTS 与 HTTP 使用同一选择。进行中的朗读保持原配置。",
                    style = MaterialTheme.typography.bodySmall, color = if (state.booksLoadError != null) Accent else Muted)
                TextButton(onClick = { viewModel.selectSection(AppSection.BOOKS) }) { Text("管理书籍与人物声音") }
            }
        }

        item(key = "roles-title") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.bookLibrary.activeBook != null) "全局朗读设置" else "朗读角色", fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sheetTarget = RoleSheetTarget.New }, enabled = rolesEditable) {
                        AppIconGlyph(AppIcon.ADD, size = 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(if (state.bookLibrary.activeBook != null) "添加全局角色" else "添加角色")
                    }
                }
                HorizontalDivider()
                if (!rolesEditable) {
                    Text(
                        text = "角色规则读取失败，自定义角色暂不可修改；旁白与对话仍可调整。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        item(key = "role-builtin:narrator") {
            val settings = state.builtInRoles.narrator
            RoleCard(
                name = "旁白",
                voiceLabel = voiceLabel(settings.voiceId),
                summary = rolePlaybackSummary(settings.rate, settings.pitch, settings.volume),
                required = true,
                onClick = { sheetTarget = RoleSheetTarget.BuiltIn(BuiltInRole.NARRATOR) },
            )
        }
        item(key = "role-builtin:dialogue") {
            val settings = state.builtInRoles.dialogue
            RoleCard(
                name = "对话",
                voiceLabel = voiceLabel(settings.voiceId),
                summary = rolePlaybackSummary(settings.rate, settings.pitch, settings.volume),
                required = true,
                onClick = { sheetTarget = RoleSheetTarget.BuiltIn(BuiltInRole.DIALOGUE) },
            )
        }
        items(customRoles.take(MAX_CUSTOM_ROLE_ROWS), key = { "role-custom:" + it.id }) { rule ->
            val voiceCount = rule.voiceIds.count { it.isNotBlank() }
            RoleCard(
                name = rule.name,
                voiceLabel = voiceLabel(rule.primaryVoiceId) + if (voiceCount > 1) " 等 $voiceCount 个" else "",
                summary = rolePlaybackSummary(rule.speechRate, rule.speechPitch, rule.speechVolume),
                matchLabel = roleMatchLabel(rule) +
                    if (rule.groupId in disabledGroups) " · 所在分组已停用，不会生效" else "",
                roleEnabled = rule.enabled,
                editable = rolesEditable,
                onClick = { sheetTarget = RoleSheetTarget.Custom(rule.id) },
                onToggle = { viewModel.toggleRole(rule, it) },
            )
        }
        if (customRoles.size > MAX_CUSTOM_ROLE_ROWS) {
            item(key = "roles-all") {
                TextButton(onClick = { viewModel.selectSection(AppSection.ROLES) }) {
                    Text("查看全部角色（${customRoles.size}）")
                    Spacer(Modifier.width(2.dp))
                    AppIconGlyph(AppIcon.CHEVRON_RIGHT, size = 16.dp)
                }
            }
        }

        item(key = "preview-title") {
        SectionTitle(if (state.previewBusy) "正在更新分段预览…" else "分段预览（${state.preview.size}）")
        if (uncertainCount > 0) {
            TextButton(onClick = { onlyUncertain = !onlyUncertain }) {
                AppIconGlyph(if (onlyUncertain) AppIcon.CLOSE else AppIcon.INFO, size = 16.dp, tint = Accent)
                Spacer(Modifier.width(4.dp))
                Text(if (onlyUncertain) "已筛出 $uncertainCount 段不确定 · 显示全部" else "$uncertainCount 段不确定 · 只看这些")
            }
        }
        // 暂停时也保留进度行：朗读中点试听会先暂停，进度行进出布局会让下面的预览列表整体错位。
        if ((state.isSpeaking || state.isPaused) && state.totalSegments > 0) SegmentProgress(state)
        }
        if (state.preview.isEmpty()) {
            item(key = "empty") { EmptyState("输入文本后，这里会显示旁白与对话的分段结果。") }
        } else {
            // 筛选只影响显示哪些行；序号、试听与播放高亮一律用完整预览里的原始下标。
            val shown = state.preview.withIndex().filter { !showOnlyUncertain || it.value.uncertain }
            items(shown, key = { it.value.id }) { (index, segment) ->
                PreviewRow(
                    segment = segment,
                    index = index,
                    isPlaying = state.playingSegmentIndex == index,
                    onPreview = { viewModel.previewSegment(index) },
                )
            }
        }
        state.pipelineIssues.forEach { issue -> item {
            Text(
                text = "⚠ ${issue.field}：${issue.message}",
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
            )
        } }
    }

    sheetTarget?.let { target ->
        RoleConfigSheet(
            target = target,
            state = state,
            viewModel = viewModel,
            onDismiss = { sheetTarget = null },
            onOpenAdvanced = { rule ->
                sheetTarget = null
                // 角色规则页留有未处理的草稿时先去处理它，否则这里打开编辑器会把那份草稿覆盖掉。
                if (viewModel.roleDraft() != null) viewModel.selectSection(AppSection.ROLES) else advancedRule = rule
            },
        )
    }

    // 「高级规则…」复用角色规则页的编辑器，接线与 RolesPage 一致（含草稿保存与丢弃）。
    advancedRule?.let { rule ->
        RuleEditorSheet(
            target = RuleEditorTarget.Role(rule, rule.groupId),
            groups = roleGroups,
            voices = state.voices,
            roles = state.roles,
            onDismiss = { viewModel.stopPreview(); advancedRule = null },
            onSaveRole = { edited, newGroup ->
                val saved = withContext(Dispatchers.IO) { viewModel.saveRole(edited, newGroup, rule) }
                if (saved) { viewModel.clearRuleDraft(RulesKind.ROLE); viewModel.stopPreview(); advancedRule = null }
                saved
            },
            onSaveReplacement = { _, _ -> false },
            onTestRole = { text, draft, all -> withContext(Dispatchers.Default) { viewModel.testRoleMatch(text, draft, all) } },
            onTestReplacement = { text, draft, all -> withContext(Dispatchers.Default) { viewModel.testReplacements(text, draft, all) } },
            onPreviewVoice = { voice -> viewModel.previewVoice(voice) },
            onStopPreview = viewModel::stopPreview,
            onRoleDraftChange = viewModel::rememberRoleDraft,
            onReplacementDraftChange = { _, _, _, _ -> },
            onDiscardDraft = { viewModel.clearRuleDraft(RulesKind.ROLE) },
        )
    }
}

/** 朗读进度：进度条 + 「第 N/M 段 · 旁白 · 音色名」状态行。 */
@Composable
private fun SegmentProgress(state: MainUiState) {
    val total = state.totalSegments
    val index = state.currentSegmentIndex
    LinearProgressIndicator(
        progress = {
            if (total <= 0) 0f else (state.completedSegments.toFloat() / total).coerceIn(0f, 1f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = segmentProgressLabel(state),
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

private fun segmentProgressLabel(state: MainUiState): String {
    val total = state.totalSegments
    if (total <= 0) return ""
    val index = state.currentSegmentIndex
    if (index < 0) return "准备朗读 · 共 $total 段"
    val kind = if (state.playbackIsDialogue) "对话" else "旁白"
    val voice = state.playbackVoiceName
    val suffix = if (voice.isBlank()) "" else " · $voice"
    return (if (state.isPreview) "单段试听 · $kind$suffix" else "第 ${index + 1}/$total 段 · $kind$suffix") +
        (state.playbackFallbackReason?.let { " · $it" } ?: "")
}

@Composable
private fun SliderRow(
    icon: AppIcon,
    label: String,
    startLabel: String,
    endLabel: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIconGlyph(icon, size = 18.dp, tint = Muted)
        Spacer(Modifier.width(6.dp))
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
