package app.noveltts

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.noveltts.data.settings.SettingsStore
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.ui.AppSection
import app.noveltts.ui.MainViewModel
import app.noveltts.ui.NovelTtsScreen
import app.noveltts.ui.common.ConfirmDialog
import app.noveltts.ui.theme.NovelTtsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(application as NovelTtsApplication,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MainViewModel::class.java]
    }
    private var openRequest by mutableStateOf<String?>(null)
    private var pendingPackUrl by mutableStateOf<String?>(null)
    private var pendingCatalogUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.noveltts.data.backup.ConfigurationRecovery.recover(this)
        Log.i(TAG, "onCreate action=${intent?.action}")
        openRequest = savedInstanceState?.getString("pendingOpen")
        pendingPackUrl = savedInstanceState?.getString(STATE_PACK_URL)
        pendingCatalogUrl = savedInstanceState?.getString(STATE_CATALOG_URL)
        // 诊断日志落盘：只挂载不 detach，进程活着就一直异步追加；目录不可写时静默退化为纯内存。
        DiagnosticsLog.attachStore(filesDir)
        DiagnosticsLog.restoreRecent()
        handleDebugExtras(intent)
        if (savedInstanceState == null) {
            handleOpenIntent(intent)
            handleSharedText(intent)
            handlePackInstall(intent)
            handleCatalogIntent(intent)
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            NovelTtsTheme(themeMode = state.themeMode) {
                NovelTtsScreen(
                    viewModel = viewModel,
                    onOpenSystemSpeechSettings = ::openSystemSpeechSettings,
                    onOpenConsumed = { openRequest = null },
                    openSectionOnStart = openSection(openRequest),
                    openLogsOnStart = openRequest == OPEN_LOGS,
                    openIntegrationOnStart = openRequest == "integration",
                    openSettingsDetailOnStart = when (openRequest) {
                        "external" -> "EXTERNAL_CALL"
                        "reading" -> "READING"
                        "data" -> "DATA"
                        "synthesis" -> "SYNTHESIS"
                        "bgm" -> "BACKGROUND_MUSIC"
                        else -> null
                    },
                )
                val packUrl = pendingPackUrl
                val catalogUrl = pendingCatalogUrl
                if (packUrl != null || catalogUrl != null) {
                    val isPack = packUrl != null
                    val url = packUrl ?: catalogUrl.orEmpty()
                    val dismiss = { if (isPack) pendingPackUrl = null else pendingCatalogUrl = null }
                    ConfirmDialog(
                        title = if (isPack) "安装外部语音包？" else "更改在线目录？",
                        message = (if (isPack) "外部应用请求下载并安装以下语音包，已有同包配置可能被替换。"
                            else "外部应用请求将在线目录改为以下地址。") + "\n\n" + url.take(500),
                        confirmLabel = if (isPack) "安装" else "更改并刷新",
                        dangerous = isPack,
                        onConfirm = {
                            dismiss()
                            if (isPack) viewModel.installPackFromUrl(url)
                            else { viewModel.setCatalogUrl(url); viewModel.refreshCatalog() }
                        },
                        onDismiss = dismiss,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingOpen", openRequest)
        outState.putString(STATE_PACK_URL, pendingPackUrl)
        outState.putString(STATE_CATALOG_URL, pendingCatalogUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent action=${intent.action}")
        handleDebugExtras(intent)
        handleSharedText(intent)
        handlePackInstall(intent)
        handleCatalogIntent(intent)
        // Route each explicit request once without recreating the Activity.
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        val value = intent?.getStringExtra(EXTRA_OPEN)?.trim()?.lowercase()
        intent?.removeExtra(EXTRA_OPEN)
        if (openSection(value) != null || value == OPEN_LOGS) openRequest = value
    }
    /**
     * 支持通过 `--es open <workbench|voicepacks|roles|replacements|settings|logs>` 直达页面，
     * 便于分发链接与自动化验证；未知取值回落到工作台。
     */
    private fun openSection(value: String?): AppSection? = when (value) {
        OPEN_WORKBENCH -> AppSection.WORKBENCH
        OPEN_VOICE_PACKS, "packs" -> AppSection.PACKS
        "roles" -> AppSection.ROLES
        "books" -> AppSection.BOOKS
        "replacements" -> AppSection.REPLACEMENTS
        "settings", "integration", "external", "reading", "data", "synthesis", "bgm" -> AppSection.SETTINGS
        else -> null
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (sharedText.isBlank()) return
        intent.removeExtra(Intent.EXTRA_TEXT)
        viewModel.updateText(sharedText)
        viewModel.speak()
    }

    /**
     * External install requests require confirmation before downloading or replacing a package.
     * Consume the extra before showing the dialog, so rotation never repeats an accepted request.
     */
    private fun handlePackInstall(intent: Intent?) {
        pendingPackUrl = consumeExternalUrl(intent, EXTRA_PACK_URL) ?: pendingPackUrl
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

    /** External directory changes follow the same confirmation and one-shot handling as installs. */
    private fun handleCatalogIntent(intent: Intent?) {
        pendingCatalogUrl = consumeExternalUrl(intent, EXTRA_CATALOG_URL) ?: pendingCatalogUrl
    }

    private fun consumeExternalUrl(intent: Intent?, key: String): String? {
        val url = intent?.getStringExtra(key)?.trim().orEmpty()
        intent?.removeExtra(key)
        if (url.isEmpty()) return null
        val protocol = runCatching { java.net.URL(url).protocol }.getOrNull()
        if (protocol !in setOf("http", "https") || url.length > 8192) {
            Toast.makeText(this, "外部地址无效，仅支持 HTTP / HTTPS", Toast.LENGTH_LONG).show()
            return null
        }
        return url
    }

    private companion object {
        const val TAG = "MainActivity"
        const val STATE_PACK_URL = "pendingPackUrl"
        const val STATE_CATALOG_URL = "pendingCatalogUrl"
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
