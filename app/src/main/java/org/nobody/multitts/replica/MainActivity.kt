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

        setContent {
            MultiTtsTheme {
                MultiTtsScreen(
                    viewModel = viewModel,
                    onOpenSystemSpeechSettings = ::openSystemSpeechSettings,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (sharedText.isBlank()) return
        viewModel.updateText(sharedText)
        viewModel.speak()
    }

    private fun openSystemSpeechSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_speech_settings_hint, Toast.LENGTH_LONG).show()
        }
    }
}
