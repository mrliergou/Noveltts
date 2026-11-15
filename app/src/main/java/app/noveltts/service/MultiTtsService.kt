package app.noveltts.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import app.noveltts.data.config.RulesRepository
import app.noveltts.data.config.RulesStoreException
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.data.voicepack.VoicePackEvents
import app.noveltts.data.voicepack.VoicePackStore
import app.noveltts.data.voicepack.VoiceRegistry
import app.noveltts.domain.RuleSet
import app.noveltts.domain.SynthesisPlanBuilder
import app.noveltts.engine.EngineManager
import app.noveltts.engine.MultiSegmentSynthesizer
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MultiTTS 自己的 Android TextToSpeechService。
 *
 * 系统只看到 usable=true 的音色；合成直接调用 Edge 或微软嵌入式引擎，不再转发给
 * 其他已安装 TTS。离线模型未配置许可证时保留在应用内，但不暴露给系统。
 */
class MultiTtsService : TextToSpeechService() {
    private val settings by lazy { SettingsStore(this) }
    private val packStore by lazy { VoicePackStore(this) }
    private val registry by lazy { VoiceRegistry(this, packStore) }
    private val rules by lazy { RulesRepository(this) }
    private val engines by lazy { EngineManager(this, settings) }
    private val synthesizer by lazy { MultiSegmentSynthesizer(engines) }
    private val voiceLock = Any()

    @Volatile
    private var cachedVoices: List<Voice> = emptyList()

    @Volatile
    private var cachedDescriptors: List<VoiceDescriptor> = emptyList()

    @Volatile
    private var active: ActiveSynthesis? = null

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            invalidateVoices()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(VoicePackEvents.ACTION_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(refreshReceiver, filter)
        }
        refreshVoices()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        active?.handle?.cancel()
        active?.latch?.countDown()
        active = null
        synthesizer.shutdown()
        super.onDestroy()
    }

    // ---- 语言与音色 -------------------------------------------------------

    override fun onGetLanguage(): Array<String> {
        val locale = refreshVoices().firstOrNull()?.locale ?: Locale.SIMPLIFIED_CHINESE
        return arrayOf(locale.isO3Language, locale.isO3Country, locale.variant)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        matchLanguage(lang, country, variant)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        matchLanguage(lang, country, variant)

    override fun onGetVoices(): List<Voice> = refreshVoices()

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (refreshVoices().any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val voices = refreshVoices()
        val configured = settings.systemVoice.takeIf { id -> voices.any { it.name == id } }
        return configured
            ?: voices.firstOrNull { matches(it.locale, lang, country, variant) }?.name
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
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            safeError(callback)
            return
        }

        val descriptors = descriptors()
        if (descriptors.none { it.usable }) {
            Log.w(TAG, "synthesize rejected: no usable voice")
            safeError(callback)
            return
        }
        val requested = descriptors.firstOrNull { it.systemName == request.voiceName && it.usable }
        val narrator = requested
            ?: descriptors.firstOrNull { it.systemName == settings.narratorVoice && it.usable }
            ?: descriptors.firstOrNull { it.systemName == settings.systemVoice && it.usable }
            ?: descriptors.firstOrNull { it.usable && it.localeTag.startsWith("zh") }
            ?: descriptors.firstOrNull { it.usable }
        val dialogue = descriptors.firstOrNull {
            it.systemName == settings.dialogueVoice && it.usable
        } ?: narrator

        val plan = SynthesisPlanBuilder.build(
            text = text,
            narratorVoiceId = narrator?.systemName,
            dialogueVoiceId = dialogue?.systemName,
            roles = loadRulesOrEmpty("roles") { rules.loadRoles() },
            replacements = loadRulesOrEmpty("replacements") { rules.loadReplacements() },
            filterPurePunctuation = settings.punctuationFilter,
            voices = descriptors,
        )
        if (plan.segments.isEmpty()) {
            val started = runCatching {
                callback.start(24_000, AudioFormat.ENCODING_PCM_16BIT, 1)
            }.getOrDefault(TextToSpeech.ERROR)
            if (started == TextToSpeech.SUCCESS) {
                runCatching { if (!callback.hasFinished()) callback.done() }
            } else {
                safeError(callback)
            }
            return
        }

        val latch = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val listener = object : SynthesisListener {
            override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
                if (aborted.get() || !started.compareAndSet(false, true)) return
                val result = runCatching {
                    callback.start(sampleRateHz, audioFormat, channelCount)
                }.getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS) {
                    failed.set(true)
                    aborted.set(true)
                    latch.countDown()
                }
            }

            override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
                if (aborted.get() || failed.get() || !started.get() || length <= 0) return
                val result = runCatching {
                    callback.audioAvailable(pcm, offset, length)
                }.getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS) {
                    failed.set(true)
                    aborted.set(true)
                }
            }

            override fun onComplete() {
                if (!aborted.get() && !failed.get()) {
                    runCatching { if (!callback.hasFinished()) callback.done() }
                        .onFailure { Log.w(TAG, "callback.done failed", it) }
                }
                latch.countDown()
            }

            override fun onError(code: Int, message: String?) {
                Log.w(TAG, "synthesis error [$code] $message")
                aborted.set(true)
                if (!callback.hasFinished()) safeError(callback)
                latch.countDown()
            }
        }

        val handle = synthesizer.synthesize(
            segments = plan.segments,
            rate = (request.speechRate / 100f).coerceIn(0.25f, 4f),
            pitch = (request.pitch / 100f).coerceIn(0.25f, 4f),
            volume = resolveVolume(request.params),
            listener = listener,
        )
        active = ActiveSynthesis(handle, latch)
        try {
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
        active?.handle?.cancel()
        active?.latch?.countDown()
    }

    // ---- 内部辅助 ---------------------------------------------------------

    private fun invalidateVoices() {
        synchronized(voiceLock) {
            cachedVoices = emptyList()
            cachedDescriptors = emptyList()
        }
    }

    private fun refreshVoices(): List<Voice> = synchronized(voiceLock) {
        if (cachedVoices.isNotEmpty()) return@synchronized cachedVoices
        cachedDescriptors = registry.voices(settings.hasLicense())
        cachedVoices = cachedDescriptors.filter { it.usable }.mapNotNull { descriptor ->
            runCatching {
                Voice(
                    descriptor.systemName,
                    Locale.forLanguageTag(descriptor.localeTag),
                    Voice.QUALITY_HIGH,
                    Voice.LATENCY_NORMAL,
                    descriptor.isOnline,
                    emptySet(),
                )
            }.getOrNull()
        }
        cachedVoices
    }

    private fun descriptors(): List<VoiceDescriptor> = synchronized(voiceLock) {
        if (cachedDescriptors.isEmpty()) {
            cachedDescriptors = registry.voices(settings.hasLicense())
        }
        cachedDescriptors
    }

    private fun matchLanguage(lang: String?, country: String?, variant: String?): Int {
        val voices = refreshVoices()
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
        if (!variant.isNullOrEmpty() && !locale.variant.equals(variant, ignoreCase = true)) {
            return false
        }
        return true
    }

    private fun resolveVolume(params: Bundle?): Float =
        (params?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) ?: 1f).coerceIn(0f, 1f)

    /** 规则文件损坏或版本过新时不阻断系统朗读：本次按无规则处理，文件原样保留等待应用内修复。 */
    private fun <T> loadRulesOrEmpty(label: String, load: () -> RuleSet<T>): RuleSet<T> =
        try {
            load()
        } catch (error: RulesStoreException) {
            Log.w(TAG, "$label unavailable, synthesizing without rules: ${error.message}")
            RuleSet()
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
