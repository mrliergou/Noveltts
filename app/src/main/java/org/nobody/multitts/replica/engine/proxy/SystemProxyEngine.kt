package org.nobody.multitts.replica.engine.proxy

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.nobody.multitts.replica.engine.api.SynthesisEngine
import org.nobody.multitts.replica.engine.api.SynthesisError
import org.nobody.multitts.replica.engine.api.SynthesisHandle
import org.nobody.multitts.replica.engine.api.SynthesisListener
import org.nobody.multitts.replica.engine.api.SynthesisRequest
import org.nobody.multitts.replica.engine.api.VoiceProfile
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 委托型引擎：把合成请求转给设备上另一个已安装的系统 TTS 引擎。
 *
 * 这是第一阶段的音源策略（选项 C 的第一步）：先证明"系统 TTS provider 闭环"能跑通，
 * 再谈内置本地模型。实现要点：
 *  - 用 `TextToSpeech(context, listener, delegatePackage)` 显式指定后端，避免自委托；
 *  - 委托引擎通过 `synthesizeToFile` 写 WAV 到私有缓存，再解成 PCM 分块回吐；
 *  - 所有 TTS 控制调用都回主线程执行，避免部分 ROM 对调用线程的隐式要求；
 *  - WAV 解析与回调在独立工作线程上，绝不阻塞主线程。
 */
class SystemProxyEngine(
    context: Context,
    private val delegatePackage: String,
    private val delegateLabel: String,
) : SynthesisEngine {

    override val id: String = "system-proxy"

    override val displayName: String = "系统语音 · $delegateLabel"

    private val appContext = context.applicationContext
    private val ready = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, Job>()
    private val cacheDir = File(appContext.cacheDir, "proxy-tts")

    private val control = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("multitts-proxy", android.os.Process.THREAD_PRIORITY_AUDIO)
    private val worker: Handler

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    private var voices: List<VoiceProfile> = emptyList()

    init {
        cacheDir.mkdirs()
        workerThread.start()
        worker = Handler(workerThread.looper)
        control.post { connect() }
    }

    private fun connect() {
        if (closed.get()) return
        val created = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.i(TAG, "delegate engine ready: $delegatePackage")
                voices = readVoices()
            } else {
                failure = "无法初始化委托引擎 $delegatePackage（status=$status）"
                Log.w(TAG, failure!!)
            }
            ready.countDown()
        }, delegatePackage)

        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = complete(utteranceId, null)

            override fun onError(utteranceId: String?) = complete(utteranceId, "委托引擎合成失败")

            @Deprecated("框架仍可能在旧引擎上回调此重载，保留以兼容。")
            override fun onError(utteranceId: String?, errorCode: Int) =
                complete(utteranceId, "委托引擎合成失败（error=$errorCode）")
        })
        tts = created
    }

    private fun readVoices(): List<VoiceProfile> {
        val current = tts ?: return emptyList()
        return runCatching {
            current.voices.orEmpty().map { voice ->
                VoiceProfile(
                    id = voice.name,
                    displayName = labelOf(voice),
                    localeTag = voice.locale.toLanguageTag(),
                    quality = voice.quality,
                    latency = voice.latency,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }.sortedBy { it.displayName }
        }.getOrElse {
            Log.w(TAG, "read voices failed", it)
            emptyList()
        }
    }

    private fun labelOf(voice: android.speech.tts.Voice): String {
        val localeName = runCatching { voice.locale.getDisplayName(Locale.getDefault()) }.getOrNull()
        return if (localeName.isNullOrBlank()) voice.name else "$localeName · ${voice.name}"
    }

    override fun listVoices(): List<VoiceProfile> {
        if (!awaitReady(BIND_TIMEOUT_MS)) return emptyList()
        if (voices.isEmpty()) voices = readVoices()
        return voices
    }

    override fun defaultVoiceId(): String? {
        val all = listVoices()
        if (all.isEmpty()) return null
        return all.firstOrNull { it.localeTag.startsWith("zh", ignoreCase = true) }?.id ?: all.first().id
    }

    override fun synthesize(request: SynthesisRequest, listener: SynthesisListener): SynthesisHandle {
        val index = sequence.incrementAndGet()
        val job = Job(
            request = request,
            listener = listener,
            file = File(cacheDir, "utt-$index.wav"),
            utteranceId = "multitts-$index",
        )
        val handle = object : SynthesisHandle {
            override fun cancel() {
                job.cancelled.set(true)
                pending.remove(job.utteranceId)?.file?.delete()
                control.post { runCatching { tts?.stop() } }
            }
        }

        if (request.text.isBlank()) {
            listener.onError(SynthesisError.BAD_REQUEST, "待合成文本为空")
            return handle
        }
        if (!awaitReady(BIND_TIMEOUT_MS)) {
            listener.onError(SynthesisError.INIT_FAILED, failure ?: "委托引擎尚未就绪")
            return handle
        }

        pending[job.utteranceId] = job
        control.post {
            val current = tts
            if (current == null || closed.get()) {
                pending.remove(job.utteranceId)
                listener.onError(SynthesisError.INIT_FAILED, failure ?: "委托引擎不可用")
                return@post
            }
            if (job.cancelled.get()) {
                pending.remove(job.utteranceId)
                return@post
            }
            val result = runCatching {
                current.setSpeechRate(request.rate)
                current.setPitch(request.pitch)
                request.voiceId?.let { id ->
                    current.voices?.firstOrNull { it.name == id }?.let { current.voice = it }
                }
                current.synthesizeToFile(request.text, engineParams(), job.file, job.utteranceId)
            }.getOrElse {
                Log.w(TAG, "synthesizeToFile threw", it)
                TextToSpeech.ERROR
            }
            if (result != TextToSpeech.SUCCESS) {
                pending.remove(job.utteranceId)
                job.file.delete()
                listener.onError(SynthesisError.SYNTHESIS_FAILED, "委托引擎拒绝了合成请求（result=$result）")
            }
        }
        return handle
    }

    private fun engineParams(): Bundle = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
    }

    /** UtteranceProgressListener 回调线程不确定，统一切到工作线程再读文件。 */
    private fun complete(utteranceId: String?, error: String?) {
        val job = utteranceId?.let { pending.remove(it) } ?: return
        worker.post { stream(job, error) }
    }

    private fun stream(job: Job, error: String?) {
        try {
            if (job.cancelled.get() || closed.get()) return
            if (error != null) {
                job.listener.onError(SynthesisError.SYNTHESIS_FAILED, error)
                return
            }
            val audio = try {
                WavAudio.read(job.file, job.request.volume)
            } catch (e: WavAudio.UnsupportedAudioException) {
                Log.w(TAG, "decode failed", e)
                job.listener.onError(SynthesisError.UNSUPPORTED_AUDIO, e.message)
                return
            } catch (e: Exception) {
                Log.w(TAG, "read audio failed", e)
                job.listener.onError(SynthesisError.SYNTHESIS_FAILED, e.message)
                return
            }

            if (job.cancelled.get()) return
            job.listener.onStart(audio.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1)

            var offset = 0
            while (offset < audio.pcm.size) {
                if (job.cancelled.get() || closed.get()) return
                val length = minOf(CHUNK_BYTES, audio.pcm.size - offset)
                job.listener.onAudio(audio.pcm, offset, length)
                offset += length
            }
            if (!job.cancelled.get()) job.listener.onComplete()
        } catch (t: Throwable) {
            Log.w(TAG, "stream failed", t)
            runCatching { job.listener.onError(SynthesisError.SYNTHESIS_FAILED, t.message) }
        } finally {
            job.file.delete()
        }
    }

    private fun awaitReady(timeoutMs: Long): Boolean {
        if (failure != null) return false
        if (tts != null && ready.count == 0L) return true
        // 主线程上不能阻塞等待：初始化本身就跑在主线程。
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        return runCatching { ready.await(timeoutMs, TimeUnit.MILLISECONDS) && failure == null && tts != null }
            .getOrDefault(false)
    }

    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.cancelled.set(true); it.file.delete() }
        pending.clear()
        control.post { runCatching { tts?.shutdown() } }
        workerThread.quitSafely()
    }

    private class Job(
        val request: SynthesisRequest,
        val listener: SynthesisListener,
        val file: File,
        val utteranceId: String,
    ) {
        val cancelled = AtomicBoolean(false)
    }

    private companion object {
        const val TAG = "SystemProxyEngine"
        const val BIND_TIMEOUT_MS = 8_000L
        const val CHUNK_BYTES = 8 * 1024
    }
}
