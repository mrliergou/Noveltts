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
import app.noveltts.data.cache.AudioCache
import app.noveltts.data.config.RulesRepository
import app.noveltts.data.config.RulesStoreException
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.data.voicepack.VoicePackEvents
import app.noveltts.data.voicepack.VoicePackStore
import app.noveltts.data.voicepack.VoiceRegistry
import app.noveltts.domain.RuleSet
import app.noveltts.domain.SynthesisPlanBuilder
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.engine.EngineManager
import app.noveltts.engine.MultiSegmentSynthesizer
import app.noveltts.engine.RetryPolicy
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    // spool 目录开启“先落盘再回放”：单段失败可换备用音色，取消时能直接丢弃半成品。
    private val synthesizer by lazy {
        MultiSegmentSynthesizer(
            engines,
            spoolDir = File(cacheDir, "spool"),
            cacheDir = File(cacheDir, AudioCache.DIR_NAME),
            cacheMaxMb = settings.cacheMaxMb,
            cacheEnabled = settings.cacheEnabled,
        )
    }
    private val voiceLock = Any()

    @Volatile
    private var cachedVoices: List<Voice> = emptyList()

    @Volatile
    private var cachedDescriptors: List<VoiceDescriptor> = emptyList()

    /** 上面两份缓存是按哪个本地引擎包名算出来的：设置一改就要重算。 */
    private var cachedLocalEnginePackage: String? = null
    private var cachedLicenseConfigured: Boolean? = null
    private val stopGeneration = AtomicLong()

    @Volatile
    private var active: ActiveSynthesis? = null

    @Volatile private var loadedVoiceName: String? = null

    private val stopPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackSession.ACTION_STOP_SYNTHESIS &&
                intent.getStringExtra(PlaybackSession.EXTRA_SESSION_ID) == null) onStop()
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            invalidateVoices()
        }
    }

    override fun onCreate() {
        app.noveltts.data.backup.ConfigurationRecovery.recover(this)
        super.onCreate()
        DiagnosticsLog.attachStore(filesDir)
        DiagnosticsLog.restoreRecent()
        val filter = IntentFilter(VoicePackEvents.ACTION_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(stopPlaybackReceiver, IntentFilter(PlaybackSession.ACTION_STOP_SYNTHESIS), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(refreshReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopPlaybackReceiver, IntentFilter(PlaybackSession.ACTION_STOP_SYNTHESIS))
        }
        runCatching { refreshVoices() }.onFailure {
            DiagnosticsLog.error(Category.ENGINE, "系统音色扫描失败", it.message)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        runCatching { unregisterReceiver(stopPlaybackReceiver) }
        stopGeneration.incrementAndGet()
        active?.cancel()
        active = null
        runCatching { synthesizer.shutdown() }
        super.onDestroy()
    }

    // ---- 语言与音色 -------------------------------------------------------

    override fun onGetLanguage(): Array<String> {
        val voices = refreshVoices()
        val locale = voices.firstOrNull { it.name == loadedVoiceName }?.locale
            ?: voices.firstOrNull { it.name == settings.systemVoice }?.locale
            ?: voices.firstOrNull { it.locale.language == "zh" }?.locale
            ?: voices.firstOrNull()?.locale ?: Locale.SIMPLIFIED_CHINESE
        return arrayOf(locale.isO3Language, locale.isO3Country, locale.variant)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        matchLanguage(lang, country, variant)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        matchLanguage(lang, country, variant)

    override fun onGetVoices(): List<Voice> = refreshVoices()

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (refreshVoices().any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName).also {
        if (it == TextToSpeech.SUCCESS) loadedVoiceName = voiceName
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val voices = refreshVoices()
        val configured = settings.systemVoice.takeIf { id -> voices.any {
            it.name == id && matches(it.locale, lang, country, variant)
        } }
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
        runCatching { synthesizeRequest(request, callback) }.onFailure {
            DiagnosticsLog.error(Category.SPEECH, "系统合成请求失败", it.message)
            safeError(callback)
        }
    }

    private fun synthesizeRequest(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            safeError(callback)
            return
        }

        val generation = stopGeneration.get()
        val requestId = java.util.UUID.randomUUID().toString().take(8)
        DiagnosticsLog.info(Category.SPEECH, "系统请求 $requestId 已接收", DiagnosticsLog.textHint(text))
        val prepared = rules.withConfigurationLock {
            val config = settings.synthesisSnapshot()
            val descriptors = descriptors()
            if (descriptors.none { it.usable }) {
                DiagnosticsLog.warn(Category.SPEECH, "系统朗读被拒绝：没有可用音色")
                return@withConfigurationLock null
            }
            val requested = descriptors.firstOrNull { it.systemName == request.voiceName && it.usable }
            val narrator = requested
                ?: descriptors.firstOrNull { it.systemName == config.narratorVoice && it.usable }
                ?: descriptors.firstOrNull { it.systemName == config.systemVoice && it.usable }
                ?: descriptors.firstOrNull { it.usable && it.localeTag.startsWith("zh") }
                ?: descriptors.firstOrNull { it.usable }
            val dialogue = descriptors.firstOrNull { it.systemName == config.dialogueVoice && it.usable } ?: narrator
            val plan = SynthesisPlanBuilder.build(text,
                config.builtInRoles.withVoices(narrator?.systemName, dialogue?.systemName),
                loadRulesOrEmpty("roles") { rules.loadRoles() }, loadRulesOrEmpty("replacements") { rules.loadReplacements() },
                config.punctuationFilter, descriptors, config.maxSegmentChars)
            plan to config
        }
        val (plan, config) = prepared ?: run { safeError(callback); return }
        if (generation != stopGeneration.get()) return
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
                    latch.countDown()
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
                DiagnosticsLog.error(Category.SPEECH, "系统朗读失败 [$code]", message?.take(200))
                Log.w(TAG, "synthesis error [$code] request=$requestId")
                aborted.set(true)
                if (!callback.hasFinished()) safeError(callback)
                latch.countDown()
            }
        }

        val session = ActiveSynthesis(latch, aborted)
        active = session
        if (generation != stopGeneration.get()) session.cancel()
        var lease: AutoCloseable? = null
        try {
            if (aborted.get()) return
            synthesizer.configure(RetryPolicy(config.maxRetryCount + 1), config.silenceBetweenSegmentsMs, config.options.timeoutSec)
            synthesizer.configureCache(config.cacheEnabled, config.cacheMaxMb)
            lease = PlaybackSession.open(this, plan.segments.any { it.voice.isOnline }) { onStop() }
            val handle = synthesizer.synthesize(plan.segments,
                (request.speechRate / 100f).coerceIn(0.25f, 4f), (request.pitch / 100f).coerceIn(0.25f, 4f),
                resolveVolume(request.params), listener, options = config.options, requestId = requestId)
            session.attach(handle)
            if (!latch.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failed.set(true)
                session.cancel()
                DiagnosticsLog.error(Category.SPEECH, "系统请求 $requestId 超时")
            }
        } catch (interrupted: InterruptedException) {
            failed.set(true)
            session.cancel()
            Thread.currentThread().interrupt()
        } finally {
            if (failed.get()) { session.cancel(); safeError(callback) }
            if (active === session) active = null
            lease?.close()
        }
    }
    override fun onStop() {
        stopGeneration.incrementAndGet()
        active?.cancel()
    }

    // ---- 内部辅助 ---------------------------------------------------------

    private fun invalidateVoices() {
        synchronized(voiceLock) {
            cachedVoices = emptyList()
            cachedDescriptors = emptyList()
        }
    }

    private fun refreshVoices(): List<Voice> = rules.withConfigurationLock { synchronized(voiceLock) {
        val licensed = settings.hasLicense()
        if (cachedVoices.isNotEmpty() && cachedLocalEnginePackage == settings.localTtsEnginePackage && cachedLicenseConfigured == licensed) {
            return@synchronized cachedVoices
        }
        cachedDescriptors = registry.voices(licensed, settings.localTtsEnginePackage)
        cachedLicenseConfigured = licensed
        cachedLocalEnginePackage = settings.localTtsEnginePackage
        cachedVoices = cachedDescriptors.filter { it.usable }.mapNotNull { descriptor ->
            val locale = TtsLanguageProtocol.locale(descriptor.localeTag) ?: return@mapNotNull null
            runCatching {
                Voice(
                    descriptor.systemName,
                    locale,
                    Voice.QUALITY_HIGH,
                    Voice.LATENCY_NORMAL,
                    descriptor.isOnline,
                    emptySet(),
                )
            }.getOrNull()
        }
        cachedVoices
    } }

    private fun descriptors(): List<VoiceDescriptor> = rules.withConfigurationLock { synchronized(voiceLock) {
        val licensed = settings.hasLicense()
        if (cachedDescriptors.isEmpty() || cachedLocalEnginePackage != settings.localTtsEnginePackage || cachedLicenseConfigured != licensed) {
            cachedDescriptors = registry.voices(licensed, settings.localTtsEnginePackage)
            cachedLicenseConfigured = licensed
            cachedLocalEnginePackage = settings.localTtsEnginePackage
        }
        cachedDescriptors
    } }

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
        return TtsLanguageProtocol.matches(locale, lang, country, variant)
    }

    private fun resolveVolume(params: Bundle?): Float =
        (params?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) ?: 1f).coerceIn(0f, 1f)

    /** 规则文件损坏或版本过新时不阻断系统朗读：本次按无规则处理，文件原样保留等待应用内修复。 */
    private fun <T> loadRulesOrEmpty(label: String, load: () -> RuleSet<T>): RuleSet<T> =
        try {
            load()
        } catch (error: RulesStoreException) {
            DiagnosticsLog.warn(Category.PIPELINE, "$label 规则不可用，本次按无规则合成", error.message?.take(200))
            Log.w(TAG, "$label unavailable, synthesizing without rules: ${error.message}")
            RuleSet()
        }

    private fun safeError(callback: SynthesisCallback) {
        runCatching {
            if (!callback.hasFinished()) callback.error()
        }.onFailure { Log.w(TAG, "callback.error failed", it) }
    }

    private class ActiveSynthesis(
        val latch: CountDownLatch,
        private val aborted: AtomicBoolean,
    ) {
        private val handle = AtomicReference<SynthesisHandle?>()
        fun attach(value: SynthesisHandle) {
            handle.set(value)
            if (aborted.get()) handle.getAndSet(null)?.cancel()
        }
        fun cancel() {
            aborted.set(true)
            handle.getAndSet(null)?.cancel()
            latch.countDown()
        }
    }

    private companion object {
        const val TAG = "MultiTtsService"
        const val SYNTHESIS_TIMEOUT_SECONDS = 600L
    }
}
