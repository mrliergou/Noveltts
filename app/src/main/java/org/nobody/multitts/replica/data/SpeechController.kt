package org.nobody.multitts.replica.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

data class SpeechEngineState(
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
    val engineName: String = "",
    val voices: List<SpeechVoice> = emptyList(),
    val selectedVoice: String = "",
    val statusMessage: String = "正在连接系统语音引擎…",
)

class SpeechController(
    context: Context,
    private val onStateChanged: (SpeechEngineState) -> Unit,
    private var enginePackage: String? = null,
) {
    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var state = SpeechEngineState()
    private var queued: PendingSpeech? = null

    fun initialize() {
        if (textToSpeech != null) return

        val onInit = TextToSpeech.OnInitListener { result -> handleInit(result) }
        textToSpeech = if (enginePackage.isNullOrBlank()) {
            TextToSpeech(appContext, onInit)
        } else {
            // 指定引擎时用于预览复刻版自己，而不必先把它设成系统默认引擎。
            TextToSpeech(appContext, onInit, enginePackage)
        }
    }

    private fun handleInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            update(state.copy(statusMessage = "没有可用的系统语音引擎"))
            return
        }

        val tts = textToSpeech ?: return
        val availableVoices = tts.voices.orEmpty()
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map(::toSpeechVoice)
        val engineName = tts.engines
            .firstOrNull { it.name == (enginePackage ?: tts.defaultEngine) }
            ?.label
            .orEmpty()
        val selectedVoice = state.selectedVoice
            .takeIf { selected -> availableVoices.any { it.name == selected } }
            ?: availableVoices.firstOrNull()?.name.orEmpty()

        state = state.copy(
            isReady = true,
            engineName = engineName,
            voices = availableVoices,
            selectedVoice = selectedVoice,
            statusMessage = if (engineName.isBlank()) "系统语音引擎已连接" else "已连接 · $engineName",
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                update(state.copy(isSpeaking = true, statusMessage = "正在朗读"))
            }

            override fun onDone(utteranceId: String?) {
                update(state.copy(isSpeaking = false, statusMessage = "朗读完成"))
            }

            @Deprecated("Deprecated by Android; retained for older engines.")
            override fun onError(utteranceId: String?) {
                onSpeechError()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onSpeechError()
            }
        })
        update(state)
        queued?.let { pending ->
            queued = null
            speak(pending.text, pending.speed, pending.pitch, pending.volume)
        }
    }

    fun speak(text: String, speed: Int, pitch: Int, volume: Int) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            update(state.copy(statusMessage = "请输入要朗读的内容"))
            return
        }
        val tts = textToSpeech
        if (!state.isReady || tts == null) {
            queued = PendingSpeech(cleanText, speed, pitch, volume)
            update(state.copy(statusMessage = "系统语音引擎正在启动"))
            return
        }

        applyVoice(state.selectedVoice)
        tts.setSpeechRate(0.5f + speed / 50f)
        tts.setPitch(0.5f + pitch / 50f)
        val parameters = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume / 100f)
        }
        val result = tts.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            parameters,
            "multitts-${System.currentTimeMillis()}",
        )
        if (result == TextToSpeech.ERROR) {
            update(state.copy(isSpeaking = false, statusMessage = "请求未能发送，请检查系统语音引擎"))
        } else {
            update(state.copy(isSpeaking = true, statusMessage = "朗读请求已发送"))
        }
    }

    fun stop() {
        queued = null
        textToSpeech?.stop()
        update(state.copy(isSpeaking = false, statusMessage = "已停止朗读"))
    }

    fun selectVoice(name: String) {
        if (name == state.selectedVoice) return
        applyVoice(name)
        update(state.copy(selectedVoice = name))
    }

    /** 切换朗读所用的系统引擎；传 null 表示跟随系统默认引擎。 */
    fun useEngine(packageName: String?) {
        val normalized = packageName?.takeIf { it.isNotBlank() }
        if (normalized == enginePackage) return
        enginePackage = normalized
        queued = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        state = SpeechEngineState(
            statusMessage = if (normalized == null) "正在连接系统默认引擎…" else "正在连接 $normalized…",
        )
        update(state)
        initialize()
    }

    fun shutdown() {
        queued = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private fun applyVoice(name: String) {
        val voice = textToSpeech?.voices?.firstOrNull { it.name == name } ?: return
        textToSpeech?.voice = voice
    }

    private fun toSpeechVoice(voice: Voice): SpeechVoice {
        val displayName = when {
            voice.name.startsWith("microsoft_zh-CN-Xiaoxiao", ignoreCase = true) -> "晓晓 · 普通话"
            voice.name.startsWith("microsoft_zh-CN-Yunxi", ignoreCase = true) -> "云希 · 普通话"
            voice.name.startsWith("microsoft_zh-CN-Yunyang", ignoreCase = true) -> "云扬 · 普通话"
            voice.name.startsWith("microsoft_zh-CN", ignoreCase = true) -> "Microsoft · 普通话"
            else -> voice.name.replace('_', ' ')
        }
        return SpeechVoice(
            name = voice.name,
            displayName = displayName,
            localeName = voice.locale.getDisplayName(Locale.SIMPLIFIED_CHINESE),
        )
    }

    private fun update(newState: SpeechEngineState) {
        state = newState
        onStateChanged(newState)
    }

    private fun onSpeechError() {
        update(state.copy(isSpeaking = false, statusMessage = "朗读失败，请检查系统语音引擎"))
    }

    private data class PendingSpeech(
        val text: String,
        val speed: Int,
        val pitch: Int,
        val volume: Int,
    )
}
