package app.noveltts.ui.settings

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.noveltts.ui.MainUiState
import app.noveltts.ui.MainViewModel

/** 设置页的二级页面；一级列表只负责入口与摘要。 */
internal enum class SettingsDetailPage(val title: String) {
    READING("朗读行为"),
    SYNTHESIS("合成与网络"),
    BACKGROUND_MUSIC("背景音乐"),
    EXTERNAL_CALL("外部调用"),
    DATA("数据管理"),
    INTEGRATION("系统集成"),
    APPEARANCE("外观"),
}

/**
 * 设置：一级入口列表 + 二级详情页。
 *
 * 二级页状态用 rememberSaveable 记住，进程重建后仍停留在原来的页面；
 * 返回按钮回到一级列表，不参与 [app.noveltts.ui.AppSection] 的切换。
 */
@Composable
fun SettingsPage(
    state: MainUiState,
    viewModel: MainViewModel,
    onOpenSystemSpeechSettings: () -> Unit,
    openIntegration: Boolean = false,
    openDetail: String? = null,
    onOpenConsumed: () -> Unit = {},
    backEnabled: Boolean = true,
) {
    var opened by rememberSaveable { mutableStateOf<String?>(if (openIntegration) SettingsDetailPage.INTEGRATION.name else null) }
    LaunchedEffect(openDetail) {
        if (SettingsDetailPage.entries.any { it.name == openDetail }) { opened = openDetail; onOpenConsumed() }
    }
    BackHandler(enabled = backEnabled && opened != null) { opened = null }
    val page = SettingsDetailPage.entries.firstOrNull { it.name == opened }
    if (page == null) {
        SettingsIndexPage(state = state, onOpen = { opened = it.name })
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsDetailHeader(title = page.title, onBack = { opened = null })
        when (page) {
            SettingsDetailPage.READING -> ReadingBehaviorSettings(state, viewModel)
            SettingsDetailPage.SYNTHESIS -> SynthesisNetworkSettings(state, viewModel)
            SettingsDetailPage.BACKGROUND_MUSIC -> BackgroundMusicSettings(state, viewModel)
            SettingsDetailPage.EXTERNAL_CALL -> ExternalCallSettings(state, viewModel)
            SettingsDetailPage.DATA -> DataManagementSettings(state, viewModel)
            SettingsDetailPage.INTEGRATION ->
                SystemIntegrationSettings(state, viewModel, onOpenSystemSpeechSettings)
            SettingsDetailPage.APPEARANCE -> AppearanceSettings(state, viewModel)
        }
    }
}
