package org.nobody.multitts.replica

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.nobody.multitts.replica.ui.MainViewModel
import org.nobody.multitts.replica.ui.MultiTtsScreen
import org.nobody.multitts.replica.ui.theme.MultiTtsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedText(intent)
        handlePackInstall(intent)
        handleCatalogIntent(intent)

        setContent {
            MultiTtsTheme {
                MultiTtsScreen(
                    viewModel = viewModel,
                    onOpenSystemSpeechSettings = ::openSystemSpeechSettings,
                    openVoicePacksOnStart = intent?.getStringExtra(EXTRA_OPEN) == OPEN_VOICE_PACKS,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
        handlePackInstall(intent)
        handleCatalogIntent(intent)
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
        if (url.isEmpty()) return
        viewModel.installPackFromUrl(url)
    }

    /** `--es catalog <catalog.json 地址>`：设置在线目录并立即刷新，便于分发与自动化验证。 */
    private fun handleCatalogIntent(intent: Intent?) {
        val catalog = intent?.getStringExtra(EXTRA_CATALOG_URL)?.trim().orEmpty()
        if (catalog.isEmpty()) return
        viewModel.setCatalogUrl(catalog)
        viewModel.refreshCatalog()
    }

    private companion object {
        const val EXTRA_PACK_URL = "packUrl"
        const val EXTRA_CATALOG_URL = "catalog"
        const val EXTRA_OPEN = "open"
        const val OPEN_VOICE_PACKS = "voicepacks"
    }

    private fun openSystemSpeechSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_speech_settings_hint, Toast.LENGTH_LONG).show()
        }
    }
}
