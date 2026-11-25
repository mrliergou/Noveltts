package app.noveltts.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.noveltts.data.settings.SettingsStore
import app.noveltts.domain.UnattributedPolicy
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.BackgroundNotificationPermission
import app.noveltts.ui.common.Badge
import app.noveltts.ui.common.SectionCard
import app.noveltts.ui.common.SectionTitle
import app.noveltts.ui.common.FilterChipRow
import app.noveltts.ui.common.VoiceField
import app.noveltts.ui.common.formatSize
import app.noveltts.ui.theme.Accent
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import app.noveltts.ui.theme.ThemeMode
import app.noveltts.ui.theme.ThemeModeSelector
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 一级列表里的一个二级页入口。 */
private data class SettingsEntry(
    val page: SettingsDetailPage,
    val icon: AppIcon,
    val title: String,
    val subtitle: String,
)

/** 设置首页直接切换主题，其他选项按入口进入详情。 */
@Composable
internal fun SettingsIndexPage(state: MainUiState, onThemeMode: (ThemeMode) -> Unit, onOpen: (SettingsDetailPage) -> Unit) {
    val entries = listOf(
        SettingsEntry(
            page = SettingsDetailPage.READING,
            icon = AppIcon.VOICE,
            title = "朗读与分段",
            subtitle = readingSummary(state),
        ),
        SettingsEntry(
            page = SettingsDetailPage.SYNTHESIS,
            icon = AppIcon.WIFI,
            title = "合成参数与 Edge 端点",
            subtitle = synthesisSummary(state),
        ),
        SettingsEntry(
            page = SettingsDetailPage.BACKGROUND_MUSIC,
            icon = AppIcon.MUSIC,
            title = "背景音乐与试听",
            subtitle = backgroundMusicSummary(state),
        ),
        SettingsEntry(
            page = SettingsDetailPage.EXTERNAL_CALL,
            icon = AppIcon.EXTERNAL,
            title = "Legado HTTP 转发",
            subtitle = externalCallSummary(state),
        ),
        SettingsEntry(
            page = SettingsDetailPage.DATA,
            icon = AppIcon.UPLOAD,
            title = "备份、还原与缓存",
            subtitle = "备份导出 / 还原导入 · 缓存 " + formatSize(state.cacheBytes) + " / " + state.cacheFileCount + " 个文件",
        ),
        SettingsEntry(
            page = SettingsDetailPage.INTEGRATION,
            icon = AppIcon.GEAR,
            title = "许可证与系统语音",
            subtitle = integrationSummary(state),
        ),
    )
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
        SectionCard {
            SectionTitle("主题模式")
            ThemeModeSelector(state.themeMode, onThemeMode)
            Text("立即生效 · 跟随系统会自动切换明暗", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        entries.forEach { entry ->
            SectionCard {
                SettingsEntryRow(
                    icon = entry.icon,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    onClick = { onOpen(entry.page) },
                )
            }
        }
    }
}

/** 外观：主题模式三选一，切换立即生效并持久化。 */
@Composable
internal fun AppearanceSettings(state: MainUiState, viewModel: MainViewModel) {
    SectionTitle("主题模式")
    SectionCard {
        Text(
            text = "白天固定浅色；夜间固定深色；跟随系统按系统的深色开关自动切换。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        ThemeModeSelector(state.themeMode, viewModel::setThemeMode)
    }
    Text(
        text = "切换立即生效，无需重启；主题设置会随备份一起迁移。",
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
}

private fun readingSummary(state: MainUiState): String {
    val voice = state.voices.firstOrNull { it.systemName == state.systemVoiceId }?.displayName
    return "标点过滤 " + (if (state.punctuationFilter) "开" else "关") +
        " · " + (voice ?: "跟随系统") +
        " · 静音 " + state.silenceBetweenSegmentsMs + " ms"
}

private fun synthesisSummary(state: MainUiState): String =
    "重试 " + state.maxRetryCount + " 次 · 超时 " + state.requestTimeoutSec + " 秒 · 单段 " + state.maxSegmentChars + " 字"

/** 背景音乐在界面上的真实状态：朗读开始时自动播放，停止朗读后停止。 */
private fun backgroundMusicStatus(state: MainUiState): String = when {
    !state.bgmEnabled -> "未启用：打开开关后，朗读时自动播放所选音乐。"
    state.bgmUri.isBlank() -> "未选择文件：请在下方选取一个音频文件。"
    state.isSpeaking -> "朗读中：背景音乐正在播放。"
    else -> "朗读开始时自动播放，停止朗读后停止。"
}

private fun backgroundMusicSummary(state: MainUiState): String = if (!state.bgmEnabled) {
    "已关闭 · 朗读时不会播放"
} else {
    "音量 " + state.bgmVolume + "%" +
        (if (state.bgmLoop) " · 循环" else "") +
        (if (state.bgmDuck) " · 朗读时压低" else "")
}

private fun externalCallSummary(state: MainUiState): String = when {
    !state.forwardEnabled -> "未启用 · 打开后会监听本机端口"
    state.forwardRunning -> "运行中 · " + state.forwardBind + ":" + state.forwardPort
    else -> "已启用但未运行 · 请检查端口占用或访问令牌"
}

private fun integrationSummary(state: MainUiState): String =
    (if (state.licenseConfigured) "许可证已配置" else "许可证未配置") + " · 系统语音设置"

/** 二级页顶部：返回 + 标题。 */
@Composable
internal fun SettingsDetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            AppIconGlyph(
                icon = AppIcon.ARROW_BACK,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(text = "返回", color = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
    }
}

/** 一级列表的入口行：标题 / 副标题 / chevron。 */
@Composable
internal fun SettingsEntryRow(icon: AppIcon, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIconGlyph(icon = icon, size = 22.dp, tint = Muted)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, color = Ink)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
        AppIconGlyph(icon = AppIcon.CHEVRON_RIGHT, size = 18.dp, tint = Muted)
    }
}

/** 标题 + 开关，整行可切换；[enabled] 为 false 时灰显。 */
@Composable
internal fun ToggleSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, color = if (enabled) Ink else Muted)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) Muted else Muted.copy(alpha = 0.7f),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 标题 + 当前值 + 滑杆；值按整数生效。 */
@Composable
internal fun SliderSettingRow(
    title: String,
    value: Int,
    range: IntRange,
    unit: String,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
) {
    var draft by remember(value) { mutableStateOf(value.coerceIn(range).toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, color = Ink, modifier = Modifier.weight(1f))
            Text(
                text = draft.roundToInt().toString() + unit,
                style = MaterialTheme.typography.labelLarge,
                color = Accent,
            )
        }
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onValueChange(draft.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** 朗读行为：标点过滤 / 系统默认音色 / 段间静音。 */
@Composable
internal fun ReadingBehaviorSettings(state: MainUiState, viewModel: MainViewModel) {
    val experimentReport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFirstSegmentEvidence) }
    BackgroundNotificationPermission(showSetting = true)
    ReadingProfilesSection(state, viewModel)
    SectionCard {
        ToggleSettingRow("锁屏持续朗读", state.keepAwake, viewModel::setKeepAwake,
            subtitle = "仅活动朗读期间每 10 分钟续期唤醒锁；暂停、结束或停止立即释放。默认关闭。")
    }
    SectionCard {
        ToggleSettingRow("首段 ≤96 字实验", state.firstSegmentEnabled, viewModel::setFirstSegmentExperiment,
            subtitle = "默认关闭；仅匹配报告里的设备、音色 ID、后端/内容版本和最终参数时生效。需至少 30 组配对，中位数改善 10%，P95 不变差，取消续读通过。", enabled = state.firstSegmentReady)
        TextButton(onClick = { experimentReport.launch(arrayOf("application/json", "text/plain")) }) { Text("导入首音配对测量报告") }
    }
    SectionTitle("朗读行为")
    SectionCard {
        ToggleSettingRow(
            title = "纯标点过滤",
            checked = state.punctuationFilter,
            onCheckedChange = viewModel::setPunctuationFilter,
            subtitle = "整段只有标点时不送去合成。",
        )
        VoiceField(
            label = "系统默认音色",
            voiceId = state.systemVoiceId,
            voices = state.usableVoices,
            onSelect = viewModel::selectSystemVoice,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "没写是谁说的对话", color = Ink)
            FilterChipRow(
                options = listOf(
                    UnattributedPolicy.INHERIT to "沿用上一个说话人",
                    UnattributedPolicy.DEFAULT_VOICE to "用默认对话音色",
                ),
                selected = state.unattributedPolicy,
                onSelect = viewModel::setUnattributedPolicy,
            )
            Text(
                text = "引号前后的旁白都没交代是谁在说时怎么读；只影响自动判断说话人的角色。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
    SectionTitle("分段")
    SectionCard {
        SliderSettingRow(
            title = "段间静音时长",
            value = state.silenceBetweenSegmentsMs,
            range = SettingsStore.SILENCE_RANGE,
            unit = " ms",
            onValueChange = viewModel::setSilenceBetweenSegmentsMs,
            subtitle = "应用内、系统 TTS 与 HTTP 多段合成均生效。",
        )
    }
}

/** 合成与网络：重试 / 超时 / 单段字符 / Edge 端点。 */
@Composable
internal fun SynthesisNetworkSettings(state: MainUiState, viewModel: MainViewModel) {
    SectionTitle("合成参数")
    SectionCard {
        SliderSettingRow(
            title = "重试次数",
            value = state.maxRetryCount,
            range = SettingsStore.RETRY_COUNT_RANGE,
            unit = " 次",
            onValueChange = viewModel::setMaxRetryCount,
            subtitle = "单段合成失败后的重试次数，0 表示不重试。",
        )
        SliderSettingRow(
            title = "请求超时",
            value = state.requestTimeoutSec,
            range = SettingsStore.REQUEST_TIMEOUT_RANGE,
            unit = " 秒",
            onValueChange = viewModel::setRequestTimeoutSec,
        )
        SliderSettingRow(
            title = "单段最大字符",
            value = state.maxSegmentChars,
            range = SettingsStore.SEGMENT_CHARS_RANGE,
            unit = " 字",
            onValueChange = viewModel::setMaxSegmentChars,
            subtitle = "超过后会在句末切分，避免单次请求过长。",
        )
    }
    SectionTitle("缓存")
    SectionCard {
        ToggleSettingRow(
            title = "复用已合成的音频",
            checked = state.cacheEnabled,
            onCheckedChange = viewModel::setCacheEnabled,
            subtitle = "同一段文字与音色参数重复朗读时直接播放缓存，不再请求引擎。",
        )
        SliderSettingRow(
            title = "缓存上限",
            value = state.cacheMaxMb,
            range = SettingsStore.CACHE_MAX_MB_RANGE,
            unit = " MB",
            onValueChange = viewModel::setCacheMaxMb,
            subtitle = "超出后自动删除最早使用的段落音频；关闭缓存不会删除已有文件。",
        )
    }
    SectionTitle("后端状态")
    SectionCard {
        listOf("edgetts" to "Edge 在线", "microsoft" to "Microsoft Embedded").forEach { (code, name) ->
            val voices = state.voices.filter { it.engineCode == code }
            val result = state.engineHealth[code]
            Text("$name：${voices.count { it.usable }}/${voices.size} 个音色可用", color = Ink)
            Text(when { result == null -> "尚无合成结果，配置可用不代表服务已连通"; result.code == 0 -> "最近一次合成成功"; else -> "最近一次合成失败 [${result.code}]，详情见诊断日志" }, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
    SectionTitle("在线音源")
    EdgeEndpointCard(
        savedEndpoint = state.edgeEndpoint,
        onSave = viewModel::setEdgeEndpoint,
    )

    SectionTitle("本地引擎")
    SectionCard {
        Text("本地 TTS 桥接暂未开放", color = Ink)
        Text("普通语音包的注册链路尚未闭合，历史包名配置已保留。当前可用后端为 Edge 在线和 Microsoft Embedded。", color = Muted)
    }
}

/** 背景音乐：跟随朗读开始/结束播放，只在本应用内朗读时生效（系统 TTS 调用不播放）。 */
@Composable
internal fun BackgroundMusicSettings(state: MainUiState, viewModel: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setBgmUri(uri)
    }
    SectionTitle("背景音乐")
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIconGlyph(icon = AppIcon.WARNING, size = 16.dp, tint = Accent)
            Text(
                text = "仅在本应用内朗读时播放，系统 TTS 调用不播放",
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
            )
        }
        ToggleSettingRow(
            title = "启用背景音乐",
            checked = state.bgmEnabled,
            onCheckedChange = viewModel::setBgmEnabled,
        )
        Text(
            text = backgroundMusicStatus(state),
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
    SectionTitle("音源")
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = "音乐文件", color = Ink)
                Text(
                    text = if (state.bgmUri.isBlank()) "未选择文件" else viewModel.bgmFileName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                Text(if (state.bgmUri.isBlank()) "选择文件" else "更换")
            }
        }
        if (state.bgmUri.isNotBlank()) {
            OutlinedButton(
                onClick = viewModel::clearBgmUri,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppIconGlyph(
                    icon = AppIcon.CLOSE,
                    size = 18.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text("清除已选文件")
            }
        }
        Text(
            text = "通过系统文件选择器授权，重启后仍可读取同一文件。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
    SectionTitle("播放")
    SectionCard {
        SliderSettingRow(
            title = "背景音乐音量",
            value = state.bgmVolume,
            range = SettingsStore.BGM_VOLUME_RANGE,
            unit = "%",
            onValueChange = viewModel::setBgmVolume,
        )
        ToggleSettingRow(
            title = "循环播放",
            checked = state.bgmLoop,
            onCheckedChange = viewModel::setBgmLoop,
        )
        ToggleSettingRow(
            title = "朗读时压低",
            checked = state.bgmDuck,
            onCheckedChange = viewModel::setBgmDuck,
            subtitle = "有语音播放时把背景音乐降到 35% 音量。",
        )
    }
}

/** Apply a complete configuration draft; retain it after a failed bind or commit. */
@Composable
internal fun ExternalCallSettings(state: MainUiState, viewModel: MainViewModel) {
    BackgroundNotificationPermission(showSetting = true)
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var enabled by androidx.compose.runtime.saveable.rememberSaveable(state.forwardEnabled) { mutableStateOf(state.forwardEnabled) }
    var portText by androidx.compose.runtime.saveable.rememberSaveable(state.forwardPort) { mutableStateOf(state.forwardPort.toString()) }
    var bind by androidx.compose.runtime.saveable.rememberSaveable(state.forwardBind) { mutableStateOf(state.forwardBind) }
    var token by androidx.compose.runtime.saveable.rememberSaveable(state.forwardToken) {
        mutableStateOf(state.forwardToken.ifBlank { java.util.UUID.randomUUID().toString() }) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }
    val port = portText.toIntOrNull()
    val config = app.noveltts.forward.ForwardConfig(enabled, bind, port ?: 0, token)
    val error = config.validationError()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { resultMessage = viewModel.exportLegadoTo(uri).getOrElse { "导出失败：${it.message}" } }
    }
    SectionTitle("监听配置")
    SectionCard {
        Text("保存后统一应用。转发运行期间可退出本应用，通知中可以停止监听。", style = MaterialTheme.typography.labelSmall, color = Muted)
        ToggleSettingRow("启用 HTTP 转发", enabled, { enabled = it })
        OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, label = { Text("端口") },
            singleLine = true, isError = port == null || port !in SettingsStore.FORWARD_PORT_RANGE,
            supportingText = { Text("范围 1024–65535") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(bind == SettingsStore.FORWARD_BIND_LOOPBACK, { bind = SettingsStore.FORWARD_BIND_LOOPBACK }, label = { Text("仅本机") })
            FilterChip(bind == SettingsStore.FORWARD_BIND_ALL, { bind = SettingsStore.FORWARD_BIND_ALL }, label = { Text("所有网卡") })
        }
        OutlinedTextField(token, { token = it.take(512) }, label = { Text("访问令牌") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        TextButton({ token = java.util.UUID.randomUUID().toString() }) { Text("生成新令牌") }
        error?.let { Text(it, color = Accent) }
        Button(onClick = { scope.launch {
            applying = true
            resultMessage = viewModel.applyForwardConfig(config).getOrElse { "应用失败：${it.message}" }
            applying = false
        } }, enabled = error == null && !applying && !state.forwardApplying, modifier = Modifier.fillMaxWidth()) {
            Text(if (applying || state.forwardApplying) "正在应用…" else "保存并应用")
        }
        TextButton({ enabled = state.forwardEnabled; portText = state.forwardPort.toString(); bind = state.forwardBind; token = state.forwardToken }) {
            Text("恢复已保存配置")
        }
        resultMessage?.let { Text(it, color = Accent) }
    }
    SectionTitle("实际运行状态")
    SectionCard {
        Badge(if (state.forwardRunning) "运行中" else if (state.forwardApplying) "启动中" else "未运行", state.forwardRunning)
        Text(if (state.forwardRunning) "http://${state.forwardBind}:${state.forwardPort}/tts" else "当前没有成功建立监听", color = Muted)
        Text("处理中 ${state.forwardActiveRequests} · 排队 ${state.forwardQueuedRequests}（最多 9 条）", style = MaterialTheme.typography.labelSmall)
        state.forwardError?.let { Text(it, color = Accent) }
    }
    SectionTitle("阅读配置")
    SectionCard {
        Text("在阅读的朗读引擎管理中导入。配置使用本机地址、当前令牌及同一套角色/替换规则。", style = MaterialTheme.typography.labelSmall, color = Muted)
        Text("配置包含访问令牌；更换令牌后需重新导入。", style = MaterialTheme.typography.labelSmall, color = Accent)
        OutlinedButton({ copyToClipboard(context, viewModel.legadoJson(), "阅读配置已复制") }, enabled = state.forwardRunning) { Text("复制配置") }
        OutlinedButton({ exporter.launch("noveltts-legado.json") }, enabled = state.forwardRunning) { Text("导出配置文件…") }
    }
}
/** 系统集成：离线许可证 + 系统语音设置 + 清空最近文本。 */
@Composable
internal fun SystemIntegrationSettings(
    state: MainUiState,
    viewModel: MainViewModel,
    onOpenSystemSpeechSettings: () -> Unit,
) {
    var confirmClearText by remember { mutableStateOf(false) }
    if (confirmClearText) app.noveltts.ui.common.ConfirmDialog(
        title = "清除最近文本？", message = "将清空工作台文本及其本机保存内容。",
        confirmLabel = "清除", dangerous = true,
        onConfirm = { confirmClearText = false; viewModel.clearRecentText() },
        onDismiss = { confirmClearText = false },
    )
    SectionTitle("微软离线许可证")
    LicenseCard(
        configured = state.licenseConfigured,
        mask = state.licenseMask,
        onSave = viewModel::setLicense,
    )
    SectionTitle("系统集成")
    SectionCard {
        Text(
            text = "系统音色名格式：multitts:<包 ID>:<引擎>:<音色>，在系统语音设置里选择 NovelTTS 后生效。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        OutlinedButton(
            onClick = onOpenSystemSpeechSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppIconGlyph(
                icon = AppIcon.EXTERNAL,
                size = 18.dp,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text("打开系统语音设置")
        }
        OutlinedButton(
            onClick = { confirmClearText = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppIconGlyph(
                icon = AppIcon.DELETE,
                size = 18.dp,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text("清除最近文本")
        }
        Text(
            text = "最近文本只保存在本机，用于下次打开时恢复输入框。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}

/** Edge 在线音源端点。 */
@Composable
private fun EdgeEndpointCard(savedEndpoint: String, onSave: (String) -> Unit) {
    var draft by remember(savedEndpoint) { mutableStateOf(savedEndpoint) }
    SectionCard {
        Text(text = "Edge 端点", color = Ink)
        Text(
            text = "默认使用微软 Edge 在线朗读端点；代理或镜像可在此替换。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("WebSocket 地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(draft.trim()) },
                enabled = draft.isNotBlank(),
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = {
                    draft = SettingsStore.DEFAULT_EDGE_ENDPOINT
                    onSave(SettingsStore.DEFAULT_EDGE_ENDPOINT)
                },
            ) {
                AppIconGlyph(
                    icon = AppIcon.REFRESH,
                    size = 18.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text("恢复默认")
            }
        }
    }
}

/** 本地系统 TTS 引擎（语音包里的 "android-<包名>" 引擎代码委托给它）。 */
@Composable
private fun LocalEngineCard(savedPackage: String, onSave: (String) -> Unit) {
    var draft by remember(savedPackage) { mutableStateOf(savedPackage) }
    val context = LocalContext.current
    val selfPackage = context.packageName
    val trimmed = draft.trim()
    val error = when {
        trimmed.isEmpty() -> null
        trimmed == selfPackage -> "不能填本应用自己的包名，否则会递归调用。"
        trimmed.contains('/') || trimmed.contains(' ') -> "包名里不能有空格或斜杠。"
        else -> null
    }
    SectionCard {
        Text(text = "系统 TTS 包名", color = Ink)
        Text(
            text = "语音包里把引擎代码写成 android-<包名> 时，会委托给这个系统 TTS 引擎朗读。留空表示不使用。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("例如 com.xiaomi.tts") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(trimmed) },
                enabled = error == null && trimmed != savedPackage,
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = {
                    draft = ""
                    onSave("")
                },
                enabled = savedPackage.isNotEmpty() || draft.isNotEmpty(),
            ) {
                AppIconGlyph(
                    icon = AppIcon.CLOSE,
                    size = 18.dp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text("清除")
            }
        }
    }
}

/** 微软离线许可证：保存后写入 Keystore 加密存储。 */
@Composable
private fun LicenseCard(configured: Boolean, mask: String, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) app.noveltts.ui.common.ConfirmDialog(
        title = "清除离线许可证？", message = "清除后微软离线音色将不可用，需要重新填写许可证。",
        confirmLabel = "清除", dangerous = true,
        onConfirm = { confirmClear = false; onSave("") }, onDismiss = { confirmClear = false },
    )
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "微软离线许可证", color = Ink, modifier = Modifier.weight(1f))
            Badge(text = if (configured) "已配置" else "未配置", highlight = configured)
        }
        Text(
            text = if (configured) {
                "当前许可证：" + mask
            } else {
                "填入 Azure 语音服务的许可证密钥后，可离线使用微软音色。密钥保存在 Android Keystore。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("许可证密钥") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onSave(input.trim())
                        input = ""
                    }
                },
                enabled = input.isNotBlank(),
            ) {
                Text("保存许可证")
            }
            if (configured) {
                OutlinedButton(onClick = { confirmClear = true }) {
                    AppIconGlyph(
                        icon = AppIcon.CLOSE,
                        size = 18.dp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text("清除")
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String, message: String) {
    if (text.isBlank()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("NovelTTS", text).apply {
        // Exported reader configuration can contain the access token; suppress clipboard previews.
        description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    })
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
