package org.nobody.multitts.replica.service

import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import org.nobody.multitts.replica.engine.EngineCatalog
import org.nobody.multitts.replica.engine.api.SynthesisHandle
import org.nobody.multitts.replica.engine.api.SynthesisListener
import org.nobody.multitts.replica.engine.api.SynthesisRequest as EngineSynthesisRequest
import org.nobody.multitts.replica.engine.proxy.SystemProxyEngine
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 复刻版的系统 TTS 引擎入口。
 *
 * 从这一版开始，复刻版可以作为"系统 TTS 引擎"被设置页选中，并被任意应用调用。
 * 音频来源当前委托给另一个已安装引擎（见 [SystemProxyEngine]），后续会替换为内置模型。
 *
 * 线程模型：[onSynthesizeText] 由框架在 binder 线程调用，必须阻塞到音频写完；
 * 这里用 [CountDownLatch] 等待代理引擎的回调，[onStop] 负责提前释放。
 */
class MultiTtsService : TextToSpeechService() {

    private val engineRef = java.util.concurrent.atomic.AtomicReference<SystemProxyEngine?>(null)
    private val voiceLock = Any()

    @Volatile
    private var cachedVoices: List<Voice> = emptyList()

    @Volatile
    private var active: ActiveSynthesis? = null

    override fun onCreate() {
        super.onCreate()
        val delegate = EngineCatalog.resolveDelegate(this)
        if (delegate == null) {
            Log.w(TAG, "没有可用的委托引擎，服务将以'无音源'状态运行")
            return
        }
        Log.i(TAG, "delegating synthesis to ${delegate.packageName} / ${delegate.label}")
        engineRef.set(SystemProxyEngine(this, delegate.packageName, delegate.label))
    }

    override fun onDestroy() {
        engineRef.getAndSet(null)?.shutdown()
        cachedVoices = emptyList()
        super.onDestroy()
    }

    // ---- 语言与音色 -------------------------------------------------------

    override fun onGetLanguage(): Array<String> {
        val voice = ensureVoices().firstOrNull { it.locale.language == "zh" } ?: ensureVoices().firstOrNull()
        return voice?.let {
            arrayOf(it.locale.isO3Language, it.locale.isO3Country, it.locale.variant)
        } ?: arrayOf("zho", "CHN", "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        matchLanguage(lang, country, variant)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        matchLanguage(lang, country, variant)

    override fun onGetVoices(): List<Voice> = ensureVoices()

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (ensureVoices().any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val voices = ensureVoices()
        return voices.firstOrNull { matches(it.locale, lang, country, variant) }?.name
            ?: voices.firstOrNull()?.name
            ?: ""
    }

    override fun onGetFeaturesForLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Set<String> = emptySet()

    // ---- 合成 -------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val engine = engineRef.get()
        val text = request.charSequenceText?.toString().orEmpty()
        if (engine == null) {
            Log.w(TAG, "synthesize rejected: no delegate engine")
            safeError(callback)
            return
        }
        if (text.isBlank()) {
            safeError(callback)
            return
        }

        val latch = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)

        val listener = object : SynthesisListener {
            override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
                if (aborted.get() || !started.compareAndSet(false, true)) return
                Log.d(TAG, "streaming pcm: ${sampleRateHz}Hz fmt=$audioFormat ch=$channelCount")
                val result = runCatching { callback.start(sampleRateHz, audioFormat, channelCount) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "callback.start failed: $result")
                    failed.set(true)
                    aborted.set(true)
                    latch.countDown()
                }
            }

            override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
                if (aborted.get() || failed.get() || !started.get() || length <= 0) return
                val result = runCatching { callback.audioAvailable(pcm, offset, length) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "callback.audioAvailable failed: $result")
                    failed.set(true)
                }
            }

            override fun onComplete() {
                if (aborted.get() || failed.get()) {
                    latch.countDown()
                    return
                }
                Log.d(TAG, "synthesis finished")
                runCatching { if (!callback.hasFinished()) callback.done() }
                    .onFailure { Log.w(TAG, "callback.done failed", it) }
                latch.countDown()
            }

            override fun onError(code: Int, message: String?) {
                Log.w(TAG, "synthesis error [$code] $message")
                aborted.set(true)
                safeError(callback)
                latch.countDown()
            }
        }

        val engineRequest = EngineSynthesisRequest(
            text = text,
            voiceId = request.voiceName?.takeIf { it.isNotEmpty() },
            rate = (request.speechRate / 100f).coerceIn(0.25f, 4f),
            pitch = (request.pitch / 100f).coerceIn(0.25f, 2f),
            volume = resolveVolume(request.params),
        )

        val handle: SynthesisHandle = engine.synthesize(engineRequest, listener)
        active = ActiveSynthesis(handle, latch)

        try {
            // 系统侧播放是流式的，这里必须等音频全部交给 callback 才能返回。
            latch.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Log.w(TAG, "synthesis interrupted", interrupted)
            Thread.currentThread().interrupt()
            handle.cancel()
        } finally {
            if (active?.latch === latch) active = null
        }
    }

    override fun onStop() {
        val current = active ?: return
        current.handle.cancel()
        current.latch.countDown()
    }

    // ---- 内部辅助 ---------------------------------------------------------

    private fun ensureVoices(): List<Voice> {
        cachedVoices.takeIf { it.isNotEmpty() }?.let { return it }
        synchronized(voiceLock) {
            if (cachedVoices.isNotEmpty()) return cachedVoices
            val engine = engineRef.get() ?: return emptyList()
            cachedVoices = engine.listVoices().mapNotNull { profile ->
                runCatching {
                    Voice(
                        profile.id,
                        Locale.forLanguageTag(profile.localeTag),
                        profile.quality,
                        profile.latency,
                        profile.requiresNetwork,
                        emptySet(),
                    )
                }.getOrNull()
            }
            return cachedVoices
        }
    }

    private fun matchLanguage(lang: String?, country: String?, variant: String?): Int {
        val voices = ensureVoices()
        if (voices.isEmpty() || lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
        val matched = voices.filter { matches(it.locale, lang, country, variant) }
        if (matched.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        return when {
            country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
            variant.isNullOrEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        }
    }

    private fun matches(locale: Locale, lang: String?, country: String?, variant: String?): Boolean {
        if (lang.isNullOrEmpty()) return false
        val languageHit = locale.isO3Language.equals(lang, ignoreCase = true) ||
            locale.language.equals(lang, ignoreCase = true)
        if (!languageHit) return false
        if (!country.isNullOrEmpty()) {
            val countryHit = locale.isO3Country.equals(country, ignoreCase = true) ||
                locale.country.equals(country, ignoreCase = true)
            if (!countryHit) return false
        }
        if (!variant.isNullOrEmpty()) {
            if (!locale.variant.equals(variant, ignoreCase = true)) return false
        }
        return true
    }

    private fun resolveVolume(params: Bundle?): Float {
        val raw = params?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) ?: 1f
        return raw.coerceIn(0f, 1f)
    }

    private fun safeError(callback: SynthesisCallback) {
        runCatching {
            if (!callback.hasFinished()) callback.error()
        }.onFailure { Log.w(TAG, "callback.error failed", it) }
    }

    private class ActiveSynthesis(
        val handle: SynthesisHandle,
        val latch: CountDownLatch,
    )

    private companion object {
        const val TAG = "MultiTtsService"
        const val SYNTHESIS_TIMEOUT_SECONDS = 600L
    }
}
