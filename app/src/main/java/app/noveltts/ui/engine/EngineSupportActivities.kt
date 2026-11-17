package app.noveltts.ui.engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import app.noveltts.MainActivity
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoicePackStore
import app.noveltts.data.voicepack.VoiceRegistry
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.service.TtsLanguageProtocol

/**
 * 系统「文字转语音」设置里点「检查语音数据」时被调起
 * （`android.speech.tts.engine.CHECK_TTS_DATA`）。
 *
 * 本应用的音色是声明式的（语音包只声明引擎与音色，不含需要下载的数据包），
 * 所以这里不做下载，只把当前可用/不可用音色回报给系统，让设置界面能正确显示状态。
 */
class TtsCheckDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLog.attachStore(filesDir)
        val checked = runCatching {
            val settings = SettingsStore(this)
            VoiceRegistry(this, VoicePackStore(this)).voices(settings.hasLicense(), settings.localTtsEnginePackage)
                .map { TtsLanguageProtocol.Language(it.localeTag, it.usable) }
                .let(TtsLanguageProtocol::check)
        }.getOrElse { error ->
            DiagnosticsLog.error(Category.ENGINE, "系统语音数据检查失败", error.message)
            TtsLanguageProtocol.CheckResult(emptyList(), emptyList())
        }
        val result = Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(checked.available))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList(checked.unavailable))
        setResult(if (checked.available.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
            else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}

/**
 * 系统「试听示例」调用入口（`android.speech.tts.engine.GET_SAMPLE_TEXT`）。
 *
 * 系统只会把返回的文本交给引擎朗读，所以这里按语言挑选一段中文示例；
 * 拿到不认识的语言时统一回落到普通话示例，避免设置界面显示空白。
 */
class TtsSampleTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent?.getStringExtra(EXTRA_LANGUAGE).orEmpty().lowercase(Locale.ROOT)
        val supported = runCatching {
            val settings = SettingsStore(this)
            VoiceRegistry(this, VoicePackStore(this)).voices(settings.hasLicense(), settings.localTtsEnginePackage)
                .filter { it.usable }.mapNotNull { TtsLanguageProtocol.locale(it.localeTag) }
                .any { language.isBlank() || TtsLanguageProtocol.matches(it, language,
                    intent?.getStringExtra("country"), intent?.getStringExtra("variant")) }
        }.getOrDefault(false)
        val text = when {
            language.startsWith("en") -> SAMPLE_EN
            else -> SAMPLE_ZH
        }
        setResult(if (supported) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED,
            Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text))
        finish()
    }

    private companion object {
        /** AOSP 的试听界面向引擎查询示例文本时使用的 extra 名。 */
        const val EXTRA_LANGUAGE = "language"

        const val SAMPLE_ZH =
            "你好，这是 NovelTTS 的试听示例。旁白与对话可以使用不同音色，例如“这句话可以由另一个角色朗读”。"
        const val SAMPLE_EN =
            "Hello, this is a NovelTTS sample. Narrator and dialogue can use different voices."
    }
}

/**
 * 系统设置里点「引擎设置」时被调起
 * （`android.speech.tts.engine.CONFIGURE_ENGINE`），直接打开应用主界面。
 */
class TtsConfigureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra("open", "integration"))
        finish()
    }
}
