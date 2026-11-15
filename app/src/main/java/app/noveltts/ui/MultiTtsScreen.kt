package app.noveltts.ui

import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    openVoicePacksOnStart: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importPack(uri)
    }

    LaunchedEffect(openVoicePacksOnStart) {
        if (openVoicePacksOnStart) viewModel.selectSection(AppSection.PACKS)
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
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("菜单") }
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
            bottomBar = { BottomPanel(state = state, viewModel = viewModel) },
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
                    )
                }
            }
        }
    }
}

@Composable
private fun AppDrawer(
    current: AppSection,
    onSelect: (AppSection) -> Unit,
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
                    selected = current == section,
                    onClick = { onSelect(section) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            NavigationDrawerItem(
                label = { Text("打开系统语音设置") },
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
private fun BottomPanel(state: MainUiState, viewModel: MainViewModel) {
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
                            Text("停止朗读")
                        }
                    } else {
                        Button(onClick = viewModel::speak, modifier = Modifier.weight(1f)) {
                            Text("开始朗读")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = state.statusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}
