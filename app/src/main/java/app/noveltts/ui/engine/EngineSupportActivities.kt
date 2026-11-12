package app.noveltts.ui.engine

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.noveltts.MainActivity
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoicePackStore
import app.noveltts.data.voicepack.VoiceRegistry

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
        val registry = VoiceRegistry(this, VoicePackStore(this))
        val licenseConfigured = SettingsStore(this).hasLicense()
        val descriptors = registry.voices(licenseConfigured)

        val available = ArrayList(descriptors.filter { it.usable }.map { it.systemName })
        val unavailable = ArrayList(descriptors.filterNot { it.usable }.map { it.systemName })

        val result = Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)
        setResult(RESULT_OK, result)
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
        val language = intent?.getStringExtra(EXTRA_LANGUAGE).orEmpty().lowercase()
        val text = when {
            language.startsWith("en") -> SAMPLE_EN
            else -> SAMPLE_ZH
        }
        setResult(RESULT_OK, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text))
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
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
