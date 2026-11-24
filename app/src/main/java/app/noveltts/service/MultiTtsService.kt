package app.noveltts.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.os.Build
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
import java.util.concurrent.atomic.AtomicLong

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
    private val synthesizerDelegate = lazy {
        MultiSegmentSynthesizer(
            engines,
            spoolDir = File(cacheDir, "spool"),
            cacheDir = File(cacheDir, AudioCache.DIR_NAME),
            cacheMaxMb = settings.cacheMaxMb,
            cacheEnabled = settings.cacheEnabled,
        )
    }
    private val synthesizer by synthesizerDelegate
    private val catalog by lazy { app.noveltts.data.voicepack.VoiceCatalog(load = {
        registry.voices(settings.hasLicense(), settings.localTtsEnginePackage)
    }) }
    private val stopGeneration = AtomicLong()

    @Volatile
    private var active: SynthesisCallbackPump? = null

    @Volatile private var loadedVoiceName: String? = null

    private val stopPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackSession.ACTION_STOP_SYNTHESIS &&
                intent.getStringExtra(PlaybackSession.EXTRA_SESSION_ID) == null) active?.fail(TextToSpeech.ERROR_SERVICE)
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
            registerReceiver(refreshReceiver, filter, VoicePackEvents.INTERNAL_PERMISSION, null)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopPlaybackReceiver, IntentFilter(PlaybackSession.ACTION_STOP_SYNTHESIS), VoicePackEvents.INTERNAL_PERMISSION, null)
        }
        runCatching { refreshVoices() }.onFailure {
            DiagnosticsLog.error(Category.ENGINE, "系统音色扫描失败", it.message)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(refreshReceiver) }
        runCatching { unregisterReceiver(stopPlaybackReceiver) }
        stopGeneration.incrementAndGet()
        active?.stop()
        active = null
        if (synthesizerDelegate.isInitialized()) runCatching { synthesizer.shutdown() }
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
        val session = SynthesisCallbackPump()
        val generation = stopGeneration.get()
        active = session
        if (generation != stopGeneration.get()) session.stop()
        var lease: AutoCloseable? = null
        try {
            try {
                lease = prepareRequest(request, session, generation)
            } catch (error: Exception) {
                DiagnosticsLog.error(Category.SPEECH, "系统合成请求失败", error.message)
                if (generation == stopGeneration.get()) session.fail()
            }
            session.drain(object : SynthesisSink {
                override val maxBufferSize get() = callback.maxBufferSize
                override fun start(sampleRate: Int, format: Int, channels: Int) = callback.start(sampleRate, format, channels)
                override fun audio(bytes: ByteArray, offset: Int, length: Int) = callback.audioAvailable(bytes, offset, length)
                override fun error(code: Int) { callback.error(code) }
                override fun done() { callback.done() }
                override fun hasFinished() = callback.hasFinished()
            })
        } catch (error: Exception) {
            session.fail()
            if (generation == stopGeneration.get()) safeError(callback)
            if (error is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            if (active === session) active = null
            lease?.close()
        }
    }

    private fun prepareRequest(request: SynthesisRequest, session: SynthesisCallbackPump, generation: Long): AutoCloseable? {
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) { session.onComplete(); return null }
        val requestId = java.util.UUID.randomUUID().toString().take(8)
        val rate = (request.speechRate / 100f).coerceIn(0.25f, 4f)
        val pitch = (request.pitch / 100f).coerceIn(0.25f, 2f)
        // Android applies KEY_PARAM_VOLUME to playback. Role and voice gains remain in the plan.
        val volume = 1f
        DiagnosticsLog.info(Category.SPEECH, "系统请求 $requestId 已接收",
            "${DiagnosticsLog.textHint(text)} · inputRate=${request.speechRate}% inputPitch=${request.pitch}% · rate=$rate pitch=$pitch volume=$volume")
        val prepared = rules.withConfigurationLock {
            val config = settings.synthesisSnapshot()
            val descriptors = descriptors()
            check(descriptors.any { it.usable }) { "没有可用音色" }
            val narrator = descriptors.firstOrNull { it.systemName == request.voiceName && it.usable }
                ?: descriptors.firstOrNull { it.systemName == config.narratorVoice && it.usable }
                ?: descriptors.firstOrNull { it.systemName == config.systemVoice && it.usable }
                ?: descriptors.firstOrNull { it.usable && it.localeTag.startsWith("zh") }
                ?: descriptors.firstOrNull { it.usable }
            val dialogue = descriptors.firstOrNull { it.systemName == config.dialogueVoice && it.usable } ?: narrator
            SynthesisPlanBuilder.Snapshot(text,
                config.builtInRoles.withVoices(narrator?.systemName, dialogue?.systemName),
                rules.loadRoles(), rules.loadReplacements(), config.punctuationFilter, descriptors,
                config.maxSegmentChars, config.unattributedPolicy) to config
        }
        val (snapshot, config) = prepared
        val plan = snapshot.build()
        if (generation != stopGeneration.get()) { session.stop(); return null }
        if (plan.segments.isEmpty()) { session.onComplete(); return null }
        synthesizer.configure(RetryPolicy(config.maxRetryCount + 1), config.silenceBetweenSegmentsMs, config.options.timeoutSec)
        synthesizer.configureCache(config.cacheEnabled, config.cacheMaxMb)
        val lease = PlaybackSession.open(this, plan.segments.any { it.voice.isOnline }) {
            session.fail(TextToSpeech.ERROR_SERVICE)
        }
        try {
            session.attach(synthesizer.synthesize(plan.segments, rate, pitch, volume,
                session, options = config.options, requestId = requestId))
        } catch (error: Exception) {
            lease.close()
            throw error
        }
        return lease
    }

    override fun onStop() {
        stopGeneration.incrementAndGet()
        active?.stop()
    }

    // ---- 内部辅助 ---------------------------------------------------------

    private fun invalidateVoices() { VoicePackEvents.invalidate() }

    private fun refreshVoices(): List<Voice> = descriptors().filter { it.usable }.mapNotNull { descriptor ->
        val locale = TtsLanguageProtocol.locale(descriptor.localeTag) ?: return@mapNotNull null
        runCatching { Voice(descriptor.systemName, locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL,
            descriptor.isOnline, emptySet()) }.getOrNull()
    }

    private fun descriptors(): List<VoiceDescriptor> = catalog.voices()
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

    private fun safeError(callback: SynthesisCallback) {
        runCatching {
            if (!callback.hasFinished()) { try { callback.error() } finally { callback.done() } }
        }.onFailure { Log.w(TAG, "callback.error failed", it) }
    }

    private companion object {
        const val TAG = "MultiTtsService"
    }
}
