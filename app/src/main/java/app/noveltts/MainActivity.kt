package app.noveltts

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.noveltts.data.settings.SettingsStore
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.ui.AppSection
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.MultiTtsScreen
import app.noveltts.ui.theme.MultiTtsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.noveltts.data.backup.ConfigurationRecovery.recover(this)
        Log.i(TAG, "onCreate intent=$intent")
        // 诊断日志落盘：只挂载不 detach，进程活着就一直异步追加；目录不可写时静默退化为纯内存。
        DiagnosticsLog.attachStore(filesDir)
        DiagnosticsLog.restoreRecent()
        handleDebugExtras(intent)
        handleSharedText(intent)
        handlePackInstall(intent)
        handleCatalogIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            MultiTtsTheme(themeMode = state.themeMode) {
                MultiTtsScreen(
                    viewModel = viewModel,
                    onOpenSystemSpeechSettings = ::openSystemSpeechSettings,
                    openSectionOnStart = openSection(intent),
                    openLogsOnStart = intent?.getStringExtra(EXTRA_OPEN)?.trim()?.lowercase() == OPEN_LOGS,
                    openIntegrationOnStart = intent?.getStringExtra(EXTRA_OPEN) == "integration",
                    openSettingsDetailOnStart = when (intent?.getStringExtra(EXTRA_OPEN)) {
                        "external" -> "EXTERNAL_CALL"
                        "reading" -> "READING"
                        "data" -> "DATA"
                        "synthesis" -> "SYNTHESIS"
                        "bgm" -> "BACKGROUND_MUSIC"
                        else -> null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent intent=$intent")
        handleDebugExtras(intent)
        handleSharedText(intent)
        handlePackInstall(intent)
        handleCatalogIntent(intent)
        // 运行中再次收到「直达页面」请求时重建一次，让 onCreate 的启动路由生效。
        if (handleOpenExtra(intent)) recreate()
    }

    /** `--es open <页面>` 是否属于本 Activity 需要处理的直达请求。 */
    private fun handleOpenExtra(intent: Intent?): Boolean =
        openSection(intent) != null || intent?.getStringExtra(EXTRA_OPEN)?.trim()?.lowercase() == OPEN_LOGS

    /**
     * 支持通过 `--es open <workbench|voicepacks|roles|replacements|settings|logs>` 直达页面，
     * 便于分发链接与自动化验证；未知取值回落到工作台。
     */
    private fun openSection(intent: Intent?): AppSection? = when (intent?.getStringExtra(EXTRA_OPEN)?.trim()?.lowercase()) {
        OPEN_WORKBENCH -> AppSection.WORKBENCH
        OPEN_VOICE_PACKS, "packs" -> AppSection.PACKS
        "roles" -> AppSection.ROLES
        "replacements" -> AppSection.REPLACEMENTS
        "settings", "integration", "external", "reading", "data", "synthesis", "bgm" -> AppSection.SETTINGS
        else -> null
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (sharedText.isBlank()) return
        viewModel.updateText(sharedText)
        viewModel.speak()
    }

    /**
     * 支持通过 `am start … --es packUrl <zip 直链>` 直接安装语音包，
     * 便于分发链接与自动化验证；日常使用走「语音包」界面的导入/下载。
     */
    private fun handlePackInstall(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_PACK_URL)?.trim().orEmpty()
        Log.i(TAG, "handlePackInstall hasUrl=${url.isNotEmpty()}")
        if (url.isEmpty()) return
        viewModel.installPackFromUrl(url)
    }

    /**
     * 仅 debug 构建生效的调试入口（正式包忽略这两个 extra）：
     * - `--es license <许可证字符串>`：写入 Embedded Speech 许可证，便于自动化验证离线音色；
     * - `--es narrator <systemName>`：预设旁白音色，配合 `ACTION_SEND` 直接触发一次合成。
     *
     * 必须在 ViewModel 首次创建之前调用：ViewModel 构造时读取设置并计算音色可用性。
     */
    private fun handleDebugExtras(intent: Intent?) {
        if (intent == null) return
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        if (intent.hasExtra("keepAwake")) {
            if (intent.getBooleanExtra("keepAwake", false)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val store = SettingsStore(this)
        val license = intent.getStringExtra(EXTRA_LICENSE)?.trim().orEmpty()
        if (license.isNotEmpty()) {
            store.setLicense(license)
            Log.i(TAG, "debug: license stored, length=${license.length}")
        }
        val narrator = intent.getStringExtra(EXTRA_NARRATOR)?.trim().orEmpty()
        if (narrator.isNotEmpty()) {
            store.narratorVoice = narrator
            Log.i(TAG, "debug: narratorVoice='$narrator'")
        }
    }

    /** `--es catalog <catalog.json 地址>`：设置在线目录并立即刷新，便于分发与自动化验证。 */
    private fun handleCatalogIntent(intent: Intent?) {
        val catalog = intent?.getStringExtra(EXTRA_CATALOG_URL)?.trim().orEmpty()
        if (catalog.isEmpty()) return
        viewModel.setCatalogUrl(catalog)
        viewModel.refreshCatalog()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val EXTRA_PACK_URL = "packUrl"
        const val EXTRA_CATALOG_URL = "catalog"
        const val EXTRA_OPEN = "open"
        const val OPEN_WORKBENCH = "workbench"
        const val OPEN_VOICE_PACKS = "voicepacks"
        const val OPEN_LOGS = "logs"
        const val EXTRA_LICENSE = "license"
        const val EXTRA_NARRATOR = "narrator"
    }

    private fun openSystemSpeechSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_speech_settings_hint, Toast.LENGTH_LONG).show()
        }
    }
}
