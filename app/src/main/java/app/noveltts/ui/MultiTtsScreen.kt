package app.noveltts.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.noveltts.ui.common.AppIcon
import app.noveltts.ui.common.AppIconGlyph
import app.noveltts.ui.common.BackgroundNotificationPermission
import app.noveltts.ui.common.MenuCloseIcon
import app.noveltts.ui.log.LogEventBar
import app.noveltts.ui.log.LogPage
import app.noveltts.ui.packs.PacksPage
import app.noveltts.ui.rules.ReplacementsPage
import app.noveltts.ui.rules.RolesPage
import app.noveltts.ui.settings.SettingsPage
import app.noveltts.ui.theme.Ink
import app.noveltts.ui.theme.Muted
import app.noveltts.ui.workbench.WorkbenchPage
import kotlinx.coroutines.launch

/**
 * 应用外壳：左侧抽屉切换各页面，底部面板承载朗读控制与状态行。
 * 各页面实现位于 `ui/workbench`、`ui/packs`、`ui/rules`、`ui/settings`。
 *
 * 调用签名由 `MainActivity` 固定，改动需同步修改 Activity。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiTtsScreen(
    viewModel: MainViewModel,
    onOpenSystemSpeechSettings: () -> Unit,
    openSectionOnStart: AppSection? = null,
    openLogsOnStart: Boolean = false,
    openIntegrationOnStart: Boolean = false,
    openSettingsDetailOnStart: String? = null,
    onOpenConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackgroundNotificationPermission(active = state.isSpeaking || state.forwardRunning)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showLogs by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = showLogs) { showLogs = false }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importPack(uri)
    }

    var pendingSettingsDetail by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openSectionOnStart, openLogsOnStart, openIntegrationOnStart, openSettingsDetailOnStart) {
        if (openIntegrationOnStart) pendingSettingsDetail = "INTEGRATION"
        else if (openSettingsDetailOnStart != null) pendingSettingsDetail = openSettingsDetailOnStart
        if (openLogsOnStart) showLogs = true
        if (openSectionOnStart != null) viewModel.selectSection(openSectionOnStart)
        onOpenConsumed()
    }

    var dismissedRecovery by rememberSaveable { mutableStateOf<String?>(null) }
    state.recoveryWarning?.takeIf { it != dismissedRecovery }?.let { message ->
        app.noveltts.ui.common.ConfirmDialog(title = "请检查还原后的配置", message = message,
            confirmLabel = "知道了", dangerous = false, onConfirm = { dismissedRecovery = message },
            onDismiss = { dismissedRecovery = message })
    }
    state.pendingPackUpdate?.let { preview ->
        app.noveltts.ui.common.ConfirmDialog(title = "确认更新语音包",
            message = "${preview.name}：${preview.previousVersion} → ${preview.nextVersion}。关联 ${preview.linkedRoles} 个角色、${preview.linkedDefaults} 个默认音色。稳定 ID 和音色覆盖设置会保留；缺失音色显示回落提示。取消或失败保留旧包。",
            confirmLabel = "更新", dangerous = false, onConfirm = { viewModel.confirmPackUpdate(true) }, onDismiss = { viewModel.confirmPackUpdate(false) })
    }
    if (showLogs) {
        LogPage(onBack = { showLogs = false })
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                current = state.section,
                onSelect = { section ->
                    viewModel.selectSection(section)
                    scope.launch { drawerState.close() }
                },
                onOpenLogs = {
                    showLogs = true
                    scope.launch { drawerState.close() }
                },
                onOpenSystemSpeechSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSystemSpeechSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            MenuCloseIcon(expanded = drawerState.isOpen)
                        }
                    },
                    title = {
                        Column {
                            Text(text = "NovelTTS", fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(
                                text = state.section.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
                    },
                )
            },
            bottomBar = {
                BottomPanel(
                    state = state,
                    viewModel = viewModel,
                    onOpenLogs = { showLogs = true },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (state.section) {
                    AppSection.WORKBENCH -> WorkbenchPage(state = state, viewModel = viewModel)
                    AppSection.PACKS -> PacksPage(
                        state = state,
                        viewModel = viewModel,
                        onImport = {
                            importLauncher.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*"),
                            )
                        },
                    )

                    AppSection.ROLES -> RolesPage(state = state, viewModel = viewModel)
                    AppSection.REPLACEMENTS -> ReplacementsPage(state = state, viewModel = viewModel)
                    AppSection.SETTINGS -> SettingsPage(
                        state = state,
                        viewModel = viewModel,
                        onOpenSystemSpeechSettings = onOpenSystemSpeechSettings,

                        openDetail = pendingSettingsDetail,
                        onOpenConsumed = { pendingSettingsDetail = null },
                        backEnabled = !drawerState.isOpen,
                    )
                }
            }
        }
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
}

/** 抽屉页面到线性图标的映射：图标仅作辅助，文字标签始终保留。 */
private fun AppSection.icon(): AppIcon = when (this) {
    AppSection.WORKBENCH -> AppIcon.TEXT
    AppSection.PACKS -> AppIcon.FOLDER
    AppSection.ROLES -> AppIcon.PERSON
    AppSection.REPLACEMENTS -> AppIcon.SWAP
    AppSection.SETTINGS -> AppIcon.GEAR
}

@Composable
private fun AppDrawer(
    current: AppSection,
    onSelect: (AppSection) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSystemSpeechSettings: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(272.dp)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 18.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "NovelTTS",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
                Text(
                    text = "多语音合成工作台",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            AppSection.entries.forEach { section ->
                NavigationDrawerItem(
                    label = { Text(section.label) },
                    icon = { AppIconGlyph(section.icon()) },
                    selected = current == section,
                    onClick = { onSelect(section) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            NavigationDrawerItem(
                label = { Text("诊断日志") },
                icon = { AppIconGlyph(AppIcon.TERMINAL) },
                selected = false,
                onClick = onOpenLogs,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text("打开系统语音设置") },
                icon = { AppIconGlyph(AppIcon.EXTERNAL) },
                selected = false,
                onClick = onOpenSystemSpeechSettings,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "系统音色名 multitts:<包>:<引擎>:<音色>",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun BottomPanel(
    state: MainUiState,
    viewModel: MainViewModel,
    onOpenLogs: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (state.section == AppSection.WORKBENCH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isSpeaking) {
                        OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIconGlyph(AppIcon.STOP, size = 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("停止朗读")
                            }
                        }
                    } else {
                        Button(onClick = if (state.isPaused) viewModel::resume else viewModel::speak, modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIconGlyph(AppIcon.PLAY, size = 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.isPaused) "继续朗读" else "开始朗读")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (state.isSpeaking && !state.isPreview || state.isPaused) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.TextButton(onClick = viewModel::previousSegment, modifier = Modifier.weight(1f)) { Text("上一段") }
                        androidx.compose.material3.TextButton(onClick = if (state.isPaused) viewModel::speak else viewModel::pause,
                            modifier = Modifier.weight(1f)) { Text(if (state.isPaused) "重新朗读" else "暂停") }
                        androidx.compose.material3.TextButton(onClick = viewModel::nextSegment, modifier = Modifier.weight(1f)) { Text("下一段") }
                    }
                }
                if (state.playbackError && state.isPaused) Row(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.TextButton(onClick = viewModel::retryCurrentSegment, modifier = Modifier.weight(1f)) { Text("重试当前段") }
                    androidx.compose.material3.TextButton(onClick = viewModel::useStandbyVoice, modifier = Modifier.weight(1f)) { Text("使用备用音色") }
                }
            }
            LogEventBar(onClick = onOpenLogs)
            Spacer(Modifier.height(4.dp))
            // 固定两行：状态文案长短变化（合成/播放/失败）不再让底部面板和上方内容区跟着伸缩。
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                minLines = 2,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
