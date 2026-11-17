package app.noveltts.engine.android

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.Category
import app.noveltts.engine.DiagnosticsLog
import app.noveltts.engine.EngineAdapter
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest
import app.noveltts.engine.proxy.WavAudio
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把合成委托给设备上另一个（用户配置的）系统 TTS 引擎。
 *
 * 工作方式：`TextToSpeech(context, listener, enginePackage)` 建立连接，用
 * `synthesizeToFile` 输出临时 WAV，再用 [WavAudio] 解成单声道 16-bit PCM，
 * 最后重采样到 24 kHz 回放给 listener。
 *
 * 安全约束：显式排除 `context.packageName`，防止把合成委托给本应用自己形成递归
 * （本应用自己也注册了 TextToSpeechService）。
 *
 * 由 [app.noveltts.engine.EngineManager.localAdapter] 在语音包引擎代码写成
 * "android-<包名>"、且包名与设置项 SettingsStore.localTtsEnginePackage 一致时按需创建。
 */
class AndroidTtsBridgeAdapter(
    context: Context,
    private val enginePackage: String,
) : EngineAdapter {
    private val appContext = context.applicationContext

    override val engineCode: String = "android-${enginePackage}"

    private val spoolDir = File(appContext.cacheDir, "android-tts").apply { mkdirs() }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "multitts-android-tts").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, PendingUtterance>()

    /**
     * 一次连接尝试的初始化状态。重建连接时整体替换，旧连接迟到的回调通过 identity
     * 比对被丢弃，不会把旧状态写进新一代（否则会误判成已初始化或永久 ERROR）。
     */
    private class InitState {
        val latch = CountDownLatch(1)

        @Volatile
        var finished = false

        @Volatile
        var status = TextToSpeech.ERROR
    }

    @Volatile
    private var initState = InitState()

    private val initLock = Any()

    /** 初始化失败后的冷却截止时间：冷却期内不再重建，避免每段都白等满 5 秒。 */
    @Volatile
    private var nextInitRetryAt = 0L


    /** 空包名或指向自己都按“不可用”处理，绝不注册成递归引擎。 */
    private val selfReference = enginePackage.isBlank() || enginePackage == appContext.packageName

    // 声明在 tts 之前：init 里要注册它，Kotlin 不允许属性晚于使用初始化。
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            val item = utteranceId?.let { pending.remove(it) } ?: return
            // shutdown 之后 executor 已关闭，迟到回调直接丢弃，不能抛 RejectedExecutionException。
            if (closed.get()) {
                item.file.delete()
                return
            }
            runCatching { executor.execute { deliver(item) } }.onFailure {
                item.file.delete()
                if (!closed.get() && item.finish()) {
                    item.listener.onError(SynthesisError.NOT_READY, "合成执行器已关闭")
                }
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            val item = utteranceId?.let { pending.remove(it) } ?: return
            item.cancelled.set(true)
            item.file.delete()
            // 必须通知等待方，否则系统打断 / 引擎自行停止 / QUEUE_FLUSH 会让合成线程永久挂住。
            if (item.finish()) item.listener.onError(SynthesisError.CANCELLED, "已取消")
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val item = utteranceId?.let { pending.remove(it) } ?: return
            item.file.delete()
            if (item.cancelled.get() || closed.get()) return
            // onDone 可能已经先到：同一句话只回调一次。
            if (!item.finish()) return
            val code = mapError(errorCode)
            DiagnosticsLog.warn(Category.ENGINE, "本地系统 TTS 合成失败：${enginePackage}（errorCode=${errorCode}）")
            item.listener.onError(code, "系统 TTS 合成失败（errorCode=${errorCode}）")
        }
    }

    @Volatile
    private var tts: TextToSpeech? = null

    init {
        ensureTts()
    }

    override fun isAvailable(): Boolean {
        if (selfReference || closed.get()) return false
        val engine = ensureTts() ?: return false
        return awaitInit(engine)
    }

    /**
     * 建立或复用连接。初始化失败后 30 秒内不再重建：这样引擎彻底不可用时每段都
     * 立刻失败，而不是各自等满 5 秒；用户在系统里修好引擎后下一次合成会自动重连。
     */
    private fun ensureTts(): TextToSpeech? {
        if (selfReference || closed.get()) return null
        val current = tts
        if (current != null) {
            if (awaitInit(current)) return current
            if (SystemClock.elapsedRealtime() < nextInitRetryAt) return null
        }
        synchronized(initLock) {
            // 锁内复核 closed：shutdown 之后再建连接会泄漏一个没人关闭的引擎。
            if (closed.get()) return null
            val state = InitState()
            initState = state
            val existing = tts
            if (existing != null && existing !== current && awaitInit(existing)) return existing
            runCatching { existing?.shutdown() }
            tts = null
            val fresh = runCatching {
                TextToSpeech(appContext, { status ->
                    if (initState === state) {
                        state.status = status
                        state.finished = true
                        state.latch.countDown()
                    }
                }, enginePackage)
            }.getOrElse { error ->
                DiagnosticsLog.error(Category.ENGINE, "本地系统 TTS 初始化失败：" + enginePackage, error.message?.take(200))
                if (initState === state) {
                    state.finished = true
                    state.latch.countDown()
                }
                null
            }
            tts = fresh
            fresh?.setOnUtteranceProgressListener(progressListener)
            return fresh
        }
    }

    override fun synthesize(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle {
        val file = File.createTempFile("android-tts-", ".wav", spoolDir)
        val utteranceId = UUID.randomUUID().toString()
        val item = PendingUtterance(file, listener, request.volume)

        val handle = object : SynthesisHandle {
            override fun cancel() {
                item.cancelled.set(true)
                // 抢到票才发终止回调：不少 OEM 引擎在未入队时不会回调 onStop，
                // 只靠引擎回调会让等待方一直挂到超时。
                val claimed = pending.remove(utteranceId) != null
                file.delete()
                runCatching { tts?.stop() }
                if (claimed && !closed.get() && item.finish()) {
                    item.listener.onError(SynthesisError.CANCELLED, "已取消")
                }
            }
        }

        if (request.text.isBlank()) {
            file.delete()
            listener.onError(SynthesisError.BAD_REQUEST, "待合成文本为空")
            return handle
        }
        if (selfReference) {
            DiagnosticsLog.warn(Category.ENGINE, "拒绝把合成委托给应用自身：${enginePackage}")
            file.delete()
            listener.onError(SynthesisError.BAD_REQUEST, "不能把合成委托给应用自身：${enginePackage}")
            return handle
        }
        val engine = ensureTts()
        if (engine == null || !awaitInit(engine)) {
            file.delete()
            listener.onError(SynthesisError.INIT_FAILED, "系统 TTS 未就绪：${enginePackage}")
            return handle
        }

        executor.execute {
            if (item.cancelled.get() || closed.get()) {
                file.delete()
                return@execute
            }
            // 先登记再合成：极短文本的 onDone 可能在 synthesizeToFile 返回前就到达。
            pending[utteranceId] = item
            val started = try {
                applyVoice(engine, descriptor)
                engine.setSpeechRate(request.rate.coerceIn(0.5f, 2f))
                engine.setPitch(request.pitch.coerceIn(0.5f, 2f))
                engine.synthesizeToFile(request.text, Bundle(), file, utteranceId)
            } catch (error: Exception) {
                DiagnosticsLog.error(Category.ENGINE, "本地系统 TTS 调用失败：${enginePackage}", error.message?.take(200))
                TextToSpeech.ERROR
            }
            if (started != TextToSpeech.SUCCESS) {
                // 只有从 pending 里抢到这张票才由这里发终止回调；抢不到说明 onDone
                // 已经把 item 摘走并在投递 deliver 了，此时删文件会让 deliver 读到空文件。
                val claimed = pending.remove(utteranceId) != null
                if (claimed) file.delete()
                if (claimed && !item.cancelled.get() && !closed.get() && item.finish()) {
                    DiagnosticsLog.warn(Category.ENGINE, "本地系统 TTS 拒绝合成：" + enginePackage)
                    listener.onError(SynthesisError.SYNTHESIS_FAILED, "系统 TTS 拒绝了合成请求")
                }
            }
        }
        return handle
    }

    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        val leftovers = pending.values.toList()
        pending.clear()
        leftovers.forEach { item ->
            item.cancelled.set(true)
            item.file.delete()
            // 必须通知等待方，否则切换引擎/退出时会静音到超时。
            if (item.finish()) {
                runCatching { item.listener.onError(SynthesisError.CANCELLED, "引擎已关闭") }
            }
        }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        executor.shutdownNow()
        spoolDir.listFiles()?.forEach { file -> runCatching { file.delete() } }
    }

    /** 读取 WAV、混为 24 kHz 单声道 PCM16，再按 4 KB 分块回放。 */
    private fun deliver(item: PendingUtterance) {
        // 抢票：失败分支或 cancel 已经发过终止回调时这里必须直接退出，
        // 否则同一句话会收到两次终止回调（先 SYNTHESIS_FAILED 再 SYNTHESIS_FAILED）。
        if (!item.finish()) {
            item.file.delete()
            return
        }
        if (item.cancelled.get() || closed.get()) {
            item.file.delete()
            return
        }
        val pcm: ByteArray
        try {
            val audio = WavAudio.read(item.file, item.volume)
            pcm = resampleToPcm16(audio.sampleRateHz, audio.pcm)
            DiagnosticsLog.info(
                Category.ENGINE,
                "本地系统 TTS 音频就绪：" + enginePackage,
                "" + audio.sampleRateHz + "Hz -> " + SAMPLE_RATE_HZ + "Hz，" + pcm.size + " 字节",
            )
        } catch (error: WavAudio.UnsupportedAudioException) {
            DiagnosticsLog.warn(Category.ENGINE, "系统 TTS 返回了不支持的音频格式：" + enginePackage, error.message?.take(200))
            item.listener.onError(SynthesisError.UNSUPPORTED_AUDIO, error.message)
            return
        } catch (error: Exception) {
            DiagnosticsLog.error(Category.ENGINE, "系统 TTS 音频解析失败：" + enginePackage, error.message?.take(200))
            item.listener.onError(SynthesisError.SYNTHESIS_FAILED, error.message)
            return
        } finally {
            item.file.delete()
        }
        if (pcm.isEmpty()) {
            item.listener.onError(SynthesisError.UNSUPPORTED_AUDIO, "系统 TTS 没有返回可用的音频")
            return
        }
        // 回放阶段刻意不包在 try 里：listener 自己抛异常，不能把已经发出的
        // onComplete 变成一次 onError。
        item.listener.onStart(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
        var offset = 0
        while (offset < pcm.size) {
            if (item.cancelled.get() || closed.get()) return
            val length = minOf(CHUNK_BYTES, pcm.size - offset)
            item.listener.onAudio(pcm, offset, length)
            offset += length
        }
        if (!item.cancelled.get()) item.listener.onComplete()
    }

    /**
     * 按音色名/语言挑系统引擎的 Voice。
     *
     * 系统引擎的音色集合不可控，匹配不到时退回 `setLanguage`；两者都失败就让引擎
     * 用默认音色合成，不因为音色映射失败就整段报错。
     */
    private fun applyVoice(engine: TextToSpeech, descriptor: VoiceDescriptor) {
        val voices = runCatching { engine.voices }.getOrNull()
        val match = voices?.firstOrNull { it.name == descriptor.voiceCode || it.name == descriptor.param }
            ?: voices?.firstOrNull { it.locale.toLanguageTag().equals(descriptor.localeTag, ignoreCase = true) }
        if (match != null) {
            runCatching { engine.voice = match }
            return
        }
        val locale = runCatching { Locale.forLanguageTag(descriptor.localeTag) }.getOrNull()
        if (locale != null && locale.language.isNotEmpty()) {
            runCatching { engine.setLanguage(locale) }
        }
    }

    private fun awaitInit(engine: TextToSpeech?): Boolean {
        if (engine == null) return false
        val state = initState
        if (!state.finished) {
            runCatching { state.latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            if (!state.finished) {
                // 超时就按失败处理，并进入冷却期；不然每段都要再等满 5 秒。
                state.status = TextToSpeech.ERROR
                state.finished = true
                DiagnosticsLog.warn(Category.ENGINE, "本地系统 TTS 初始化超时：" + enginePackage)
            }
        }
        if (state.status != TextToSpeech.SUCCESS) {
            // 引擎明确返回 ERROR 也要冷却，否则每个调用周期都会重建一次连接。
            nextInitRetryAt = SystemClock.elapsedRealtime() + INIT_RETRY_COOLDOWN_MS
            return false
        }
        return !closed.get()
    }

    private fun mapError(errorCode: Int): Int = when (errorCode) {
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> SynthesisError.NETWORK_FAILED
        TextToSpeech.ERROR_OUTPUT -> SynthesisError.UNSUPPORTED_AUDIO
        TextToSpeech.ERROR_SERVICE -> SynthesisError.INIT_FAILED
        TextToSpeech.ERROR_INVALID_REQUEST -> SynthesisError.BAD_REQUEST
        else -> SynthesisError.SYNTHESIS_FAILED
    }

    /**
     * 线性插值重采样到 24 kHz。
     *
     * [WavAudio] 已经混成单声道，这里只处理采样率；源就是 24 kHz 时原样返回。
     * 线性插值对语音足够，做不了任意格式（如 ADPCM）的解码，那些由 WavAudio 抛
     * 不支持并转成 UNSUPPORTED_AUDIO。
     */
    private fun resampleToPcm16(sourceRateHz: Int, pcm: ByteArray): ByteArray {
        if (sourceRateHz == SAMPLE_RATE_HZ) return pcm
        if (sourceRateHz <= 0 || pcm.size < 2) return ByteArray(0)
        val frames = pcm.size / 2
        val outFrames = (frames.toLong() * SAMPLE_RATE_HZ / sourceRateHz).toInt()
        if (outFrames <= 0) return ByteArray(0)
        val step = sourceRateHz.toDouble() / SAMPLE_RATE_HZ
        val output = ByteArray(outFrames * 2)
        for (index in 0 until outFrames) {
            val position = index * step
            val base = position.toInt()
            val fraction = position - base
            val first = sampleAt(pcm, base)
            val second = sampleAt(pcm, minOf(base + 1, frames - 1))
            val value = (first + (second - first) * fraction).toInt().coerceIn(-32768, 32767)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return output
    }

    private fun sampleAt(pcm: ByteArray, frame: Int): Int {
        val offset = frame * 2
        if (offset < 0 || offset + 1 >= pcm.size) return 0
        return ((pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
    }

    private class PendingUtterance(
        val file: File,
        val listener: SynthesisListener,
        val volume: Float,
    ) {
        val cancelled = AtomicBoolean(false)

        private val done = AtomicBoolean(false)

        /** 一句话只允许一次终止回调；返回 true 表示这次轮到你通知 listener。 */
        fun finish(): Boolean = done.compareAndSet(false, true)
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 24_000
        const val CHUNK_BYTES = 4 * 1024
        const val INIT_TIMEOUT_MS = 5_000L
        const val INIT_RETRY_COOLDOWN_MS = 30_000L
    }
}