package app.noveltts.engine

import android.media.AudioFormat
import app.noveltts.data.cache.AudioCache
import app.noveltts.data.cache.CacheReadLease
import app.noveltts.data.cache.cacheKey
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.domain.PipelineSegment
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest
import app.noveltts.engine.api.remainingTimeoutMillis
import app.noveltts.engine.api.synthesisTimeoutMillis
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class PlannedSegment(
    val segment: PipelineSegment,
    val voice: VoiceDescriptor,
)

/**
 * 串行合成多角色片段，统一输出 24 kHz / 16-bit / 单声道 PCM。
 * 片段之间插入 80 ms 静音，取消会立即中断当前引擎并丢弃后续片段。
 *
 * 两种工作模式：
 * - [spoolDir] 为 null（默认）：逐块把引擎输出的音频直接转发给 listener，与旧行为一致。
 * - [spoolDir] 非 null：每段先落到临时 PCM 文件，校验 24k/PCM16/单声道后再回放；
 *   失败时按 [retryPolicy] 重试同音色或换备用音色。spool 文件在 finally 中删除。
 *
 * 音频缓存（[cacheDir] 非 null 且 [cacheEnabled]）：每段合成前先查缓存，命中就直接回放
 * 缓存 PCM、不调用引擎；合成成功且本次未被取消才写回缓存。缓存只是旁路，不改变既有的
 * 超时、重试与取消逻辑。
 *
 * 预取（synthesize 的 lookahead）：只对 spool 模式的多段任务生效，当前段回放期间提前备好
 * 下一段，消除「播完才开始合成」造成的段间等待；默认关闭，行为与原来逐段串行一致。
 *
 * 公开 API 保持不变（构造参数带默认值），现有调用方无需修改。
 */
class MultiSegmentSynthesizer(
    private val engines: EngineManager? = null,
    private val spoolDir: File? = null,
    retryPolicy: RetryPolicy = RetryPolicy(),
    silenceBetweenSegmentsMs: Int = DEFAULT_SILENCE_MS,
    cacheDir: File? = null,
    cacheMaxMb: Int = 100,
    cacheEnabled: Boolean = true,
    private val timeoutMillis: (Int, Long) -> Long = ::synthesisTimeoutMillis,
    private val engineCall: (VoiceDescriptor, SynthesisRequest, SynthesisListener) -> SynthesisHandle =
        { voice, request, listener -> requireNotNull(engines).speak(voice, request, listener) },
) {
    @Volatile
    private var retryPolicy: RetryPolicy = retryPolicy

    /** 音频缓存；[cacheDir] 为 null 时不命中也不写入。 */
    @Volatile
    private var cache: AudioCache? =
        cacheDir?.let { AudioCache(it, cacheMaxMb.coerceAtLeast(0).toLong() * MB, cacheEnabled) }

    @Volatile
    private var silenceMs: Int = silenceBetweenSegmentsMs.coerceIn(0, MAX_SILENCE_MS)

    /** 单段合成的等待上限；超时就当失败（可重试），避免计划线程被卡死的引擎永久挂住。 */
    @Volatile
    private var spoolTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_SEC * 1000L

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "multitts-plan").apply { isDaemon = true }
    }

    /** 预取线程：只在 lookahead 朗读时才创建，系统 TTS 与 HTTP 转发用不到。 */
    private val prefetchExecutor = lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "multitts-prefetch").apply { isDaemon = true }
        }
    }

    /** 运行期调整重试策略、段间静音与单段超时：设置页改完对下一次朗读立即生效，不需要重建合成器。 */
    fun configure(
        retryPolicy: RetryPolicy,
        silenceBetweenSegmentsMs: Int,
        requestTimeoutSec: Int = DEFAULT_REQUEST_TIMEOUT_SEC,
    ) {
        this.retryPolicy = retryPolicy
        this.silenceMs = silenceBetweenSegmentsMs.coerceIn(0, MAX_SILENCE_MS)
        this.spoolTimeoutMs = requestTimeoutSec.coerceIn(10, 180) * 1000L
    }

    /**
     * 运行期调整音频缓存开关与上限：设置页改完对下一次朗读立即生效，不需要重建合成器。
     * 上限变小或重新启用时顺手驱逐一次，避免旧文件继续占空间。
     */
    fun configureCache(enabled: Boolean, maxMb: Int) {
        val current = cache ?: return
        val bytes = maxMb.coerceAtLeast(0).toLong() * MB
        val changed = current.enabled != enabled || current.maxBytes != bytes
        current.enabled = enabled
        current.maxBytes = bytes
        if (changed && enabled) current.evictIfNeeded()
    }

    init { spoolDir?.let(SpoolFiles::prepare) }

    private val closed = AtomicBoolean(false)
    private val jobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    fun synthesize(
        segments: List<PlannedSegment>,
        rate: Float,
        pitch: Float,
        volume: Float,
        listener: SynthesisListener,
        onProgress: SegmentProgressListener? = null,
        onPlayback: SegmentPlaybackListener? = null,
        options: app.noveltts.engine.api.EngineRequestOptions? = null,
        requestId: String = java.util.UUID.randomUUID().toString().take(8),
        /** 当前段回放期间提前备好下一段（只对 spool 模式的多段任务生效）。 */
        lookahead: Boolean = false,
    ): SynthesisHandle {
        val capturedOptions = options ?: engines?.requestOptions()
        val job = Job(segments.toList(), rate, pitch, volume, listener, onProgress, spoolDir,
            onPlayback, retryPolicy, silenceMs, spoolTimeoutMs, capturedOptions, cache?.enabled == true,
            segments.map { it.voice }.distinctBy { it.systemName }.associate { voice -> voice.systemName to
                if (engines != null && capturedOptions != null) engines.cacheRevision(voice, capturedOptions) else voice.contentRevision }, requestId,
            lookahead && spoolDir != null && segments.size > 1)
        jobs.add(job)
        val handle = object : SynthesisHandle {
            override fun cancel() {
                if (!job.cancelled.compareAndSet(false, true)) return
                DiagnosticsLog.info(Category.SPEECH, "请求 ${job.requestId} 已取消", "耗时 ${job.elapsedMs()} ms")
                job.attempt.getAndSet(null)?.abort()
                job.current.getAndSet(null)?.cancel()
                jobs.remove(job)
            }
        }
        if (segments.isEmpty()) {
            listener.onStart(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            listener.onComplete()
            job.completed.set(true)
            jobs.remove(job)
            return handle
        }
        if (spoolDir != null) {
            spoolDir.mkdirs()
            enqueue(job) { spoolAt(job, 0) }
        } else {
            enqueue(job) { streamAt(job, 0) }
        }
        return handle
    }

    /**
     * 计划线程的统一入口：执行器可能已经 shutdown（onDestroy 之后仍有请求在路上），
     * 而且任何一段的回调异常都不允许把进程带崩。
     */
    private fun enqueue(job: Job, block: () -> Unit) {
        val task = Runnable {
            if (job.dispatched.compareAndSet(false, true)) DiagnosticsLog.info(Category.SPEECH,
                "请求 ${job.requestId} 开始", "${job.segments.size} 段 · 队列等待 ${job.elapsedMs()} ms")
            try { runCatching { block() }.onFailure { error ->
                DiagnosticsLog.error(Category.SPEECH, "合成计划线程异常", error.message?.take(200))
                failJob(job, 0, SynthesisError.SYNTHESIS_FAILED, error.message ?: "合成线程异常")
            } } finally { if (job.cancelled.get() || job.failed.get() || job.completed.get()) jobs.remove(job) }
        }
        runCatching { executor.execute(task) }.onFailure {
            DiagnosticsLog.warn(Category.SPEECH, "合成执行器已关闭，放弃本次朗读")
            if (!job.cancelled.get()) job.listener.onError(SynthesisError.NOT_READY, "合成线程已关闭")
            jobs.remove(job)
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        jobs.forEach { job ->
            job.cancelled.set(true)
            job.attempt.getAndSet(null)?.abort()
            job.current.getAndSet(null)?.cancel()
        }
        executor.shutdownNow()
        if (prefetchExecutor.isInitialized()) prefetchExecutor.value.shutdownNow()
        jobs.clear()
        engines?.shutdown()
    }

    // ---- 流式模式（spoolDir == null，旧行为） ---------------------------------

    private fun streamAt(job: Job, index: Int) {
        if (job.cancelled.get() || closed.get() || job.failed.get()) return
        if (index >= job.segments.size) {
            if (job.cancelled.get() || job.failed.get()) return
            job.listener.onComplete()
            DiagnosticsLog.info(Category.SPEECH, "请求 ${job.requestId} 合成完成", "耗时 ${job.elapsedMs()} ms")
            job.completed.set(true)
            jobs.remove(job)
            return
        }

        val planned = job.segments[index]
        job.progress?.onSegmentStart(index, job.segments.size, planned.segment.text)
        DiagnosticsLog.info(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 合成 · ${planned.voice.displayName}", DiagnosticsLog.textHint(planned.segment.text))
        val callback = SegmentCallback(job, index)
        val inner = try {
            engineCall(planned.voice, buildRequest(job, planned, planned.voice), callback)
        } catch (error: Exception) {
            failJob(job, index, SynthesisError.SYNTHESIS_FAILED, error.message ?: "引擎调用失败")
            return
        }
        job.current.set(inner)
        if (job.cancelled.get() || job.failed.get()) inner.cancel()
    }

    private inner class SegmentCallback(
        private val job: Job,
        private val index: Int,
    ) : SynthesisListener {
        private val finished = AtomicBoolean(false)
        private var receivedBytes = 0L

        override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
            if (job.cancelled.get() || finished.get()) return
            if (sampleRateHz != SAMPLE_RATE_HZ || audioFormat != AudioFormat.ENCODING_PCM_16BIT || channelCount != 1) {
                fail(
                    SynthesisError.UNSUPPORTED_AUDIO,
                    "引擎返回了不支持的音频格式：${sampleRateHz}Hz/${audioFormat}/${channelCount}",
                )
                return
            }
            if (job.started.compareAndSet(false, true)) {
                job.listener.onStart(sampleRateHz, audioFormat, channelCount)
            }
            job.playback?.onVoiceSelected(index, job.segments[index].voice, job.segments[index].segment.fallbackReason)
            job.playback?.onPlaybackStart(index)
        }

        override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
            if (job.cancelled.get() || job.failed.get()) return
            try { app.noveltts.engine.api.AudioBudget.check(receivedBytes, length.toLong(), app.noveltts.engine.api.AudioBudget.NORMALIZED_PCM, "单段 PCM") }
            catch (error: app.noveltts.engine.api.ResourceLimitException) { failJob(job, index, SynthesisError.RESOURCE_LIMIT, error.message); return }
            receivedBytes += length
            job.listener.onAudio(pcm, offset, length)
        }

        override fun onComplete() {
            if (!finished.compareAndSet(false, true) || job.cancelled.get() || job.failed.get()) return
            job.current.compareAndSet(null, null)
            job.playback?.onPlaybackComplete(index)
            if (index < job.segments.lastIndex && !job.cancelled.get()) {
                val silence = ByteArray(SAMPLE_RATE_HZ * 2 * job.silenceMs / 1000)
                if (silence.isNotEmpty()) job.listener.onAudio(silence, 0, silence.size)
            }
            enqueue(job) { streamAt(job, index + 1) }
        }

        override fun onError(code: Int, message: String?) {
            if (code == SynthesisError.NO_AUDIO && receivedBytes == 0L) {
                DiagnosticsLog.warn(Category.SPEECH, "空音频片段已跳过", message)
                if (!job.started.get()) onStart(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                onComplete()
                return
            }
            if (!finished.compareAndSet(false, true)) return
            fail(code, message)
        }

        private fun fail(code: Int, message: String?) {
            failJob(job, index, code, message)
        }
    }

    // ---- spool + 重试模式 ----------------------------------------------------

    /** 一段已经备好的音频：缓存文件、刚合成的 spool，或一个要等轮到它时才报告的失败。 */
    private sealed interface Prepared {
        fun release() {}

        class Cached(val lease: CacheReadLease) : Prepared {
            override fun release() { runCatching { lease.close() } }
        }

        class Spooled(
            val file: File,
            val request: SynthesisRequest,
            val voice: VoiceDescriptor,
            val fallbackReason: String?,
        ) : Prepared {
            override fun release() { file.delete() }
        }

        class Failed(val code: Int, val message: String?) : Prepared
        data object Empty : Prepared

        object Stopped : Prepared
    }

    private fun stopped(job: Job): Boolean = job.cancelled.get() || job.failed.get() || closed.get()

    /**
     * 计划线程：逐段「备好 → 回放」。
     *
     * 回放会阻塞到这一段真正播完（调用方的 onAudio 往往是阻塞写声卡），所以默认模式下
     * 下一段要等上一段播完才开始合成。[Job.lookahead] 打开时，当前段一到手就让预取线程
     * 去准备下一段：最多一段在播、一段在备，不会一口气合成整章。
     */
    private fun spoolAt(job: Job, index: Int) {
        var ahead: Prefetch? = null
        try {
            for (position in index until job.segments.size) {
                if (stopped(job)) return
                val startedAt = System.nanoTime()
                val prepared = if (job.lookahead) {
                    val current = ahead ?: prefetch(job, position)
                    // Keep ownership until await transfers the artifact; interruption must discard it.
                    ahead = current
                    val ready = current.await()
                    ahead = null
                    val playable = ready !is Prepared.Failed && ready != Prepared.Stopped
                    if (playable && position < job.segments.lastIndex) ahead = prefetch(job, position + 1)
                    ready
                } else {
                    prepare(job, position)
                }
                val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
                val cached = prepared is Prepared.Cached
                if (!play(job, position, prepared)) return
                DiagnosticsLog.debug(Category.SPEECH, "请求 ${job.requestId} · ${job.segments[position].segment.id} 已输出",
                    "${if (cached) "缓存" else "合成"} · 等待 $waitedMs ms · 耗时 ${(System.nanoTime() - startedAt) / 1_000_000} ms")
                if (prepared != Prepared.Empty && position < job.segments.lastIndex) {
                    val silence = ByteArray(SAMPLE_RATE_HZ * 2 * job.silenceMs / 1000)
                    if (silence.isNotEmpty()) job.listener.onAudio(silence, 0, silence.size)
                }
            }
        } finally {
            ahead?.discard()
        }
        if (!job.cancelled.get() && !job.failed.get()) {
            job.completed.set(true)
            job.listener.onComplete()
            DiagnosticsLog.info(Category.SPEECH, "请求 ${job.requestId} 合成完成", "耗时 ${job.elapsedMs()} ms")
        }
    }

    /** 预取线程上的一次「备好某一段」；结果只交给计划线程一次，没人要时自己清理。 */
    private inner class Prefetch(private val job: Job, private val index: Int) : Runnable {
        private val done = CountDownLatch(1)
        private var result: Prepared? = null
        private var discarded = false

        override fun run() {
            val prepared = try {
                prepare(job, index)
            } catch (error: Throwable) {
                DiagnosticsLog.error(Category.SPEECH, "预取线程异常", error.message?.take(200))
                Prepared.Failed(SynthesisError.SYNTHESIS_FAILED, error.message ?: "合成线程异常")
            }
            synchronized(this) { if (discarded) prepared.release() else result = prepared }
            done.countDown()
        }

        fun await(): Prepared {
            done.await()
            return synchronized(this) { result.also { result = null } } ?: Prepared.Stopped
        }

        fun discard() {
            synchronized(this) {
                discarded = true
                result?.release()
                result = null
            }
            done.countDown()
        }
    }

    private fun prefetch(job: Job, index: Int): Prefetch {
        val task = Prefetch(job, index)
        if (closed.get() || runCatching { prefetchExecutor.value.execute(task) }.isFailure) task.discard()
        return task
    }

    /** 备好一段：先查缓存，没有就合成（含重试与备用音色）。不碰 listener 的音频回调。 */
    private fun prepare(job: Job, index: Int): Prepared {
        if (stopped(job)) return Prepared.Stopped
        val planned = job.segments[index]
        job.progress?.onSegmentStart(index, job.segments.size, planned.segment.text)
        return cachedFor(job, planned) ?: synthesizeWithRetry(job, index)
    }

    /** 回放一段并释放它占用的文件；返回 false 表示任务已经结束（取消或失败），不要再继续。 */
    private fun play(job: Job, index: Int, prepared: Prepared): Boolean {
        try {
            when (prepared) {
                Prepared.Stopped -> return false
                Prepared.Empty -> {
                    announce(job, index, job.segments[index].voice, null)
                    if (stopped(job)) return false
                    job.playback?.onPlaybackComplete(index)
                }
                is Prepared.Failed -> {
                    // 预取到的失败拖到这里才报：上一段此时已经完整播完，不会被截断。
                    failJob(job, index, prepared.code, prepared.message)
                    return false
                }
                is Prepared.Cached -> {
                    announce(job, index, job.segments[index].voice, job.segments[index].segment.fallbackReason)
                    if (!streamCached(job, index, prepared.lease)) return false
                    if (stopped(job)) return false
                    job.playback?.onPlaybackComplete(index)
                }
                is Prepared.Spooled -> {
                    announce(job, index, prepared.voice, prepared.fallbackReason)
                    streamSpool(job, prepared.file)
                    if (stopped(job)) return false
                    job.playback?.onPlaybackComplete(index)
                    // 默认模式沿用旧时机：播放结束且未取消才写缓存。预取模式在合成完就写过了。
                    if (!job.lookahead) storeInCache(job, prepared.request, prepared.file)
                }
            }
        } finally {
            prepared.release()
        }
        return !stopped(job)
    }

    private fun announce(job: Job, index: Int, voice: VoiceDescriptor, fallbackReason: String?) {
        if (job.started.compareAndSet(false, true)) {
            job.listener.onStart(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
        }
        job.playback?.onVoiceSelected(index, voice, fallbackReason)
        job.playback?.onPlaybackStart(index)
    }

    private fun synthesizeWithRetry(job: Job, index: Int): Prepared {
        val planned = job.segments[index]

        DiagnosticsLog.info(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 合成 · ${planned.voice.displayName}", DiagnosticsLog.textHint(planned.segment.text))

        val candidates = candidatesFor(job, planned)
        var lastCode = SynthesisError.SYNTHESIS_FAILED
        var lastMessage: String? = null

        for ((candidateIndex, voice) in candidates.withIndex()) {
            var attempt = 1
            while (true) {
                if (stopped(job)) return Prepared.Stopped
                val request = buildRequest(job, planned, voice).copy(prefetch = job.lookahead && index > 0)
                val attemptStarted = System.nanoTime()
                val outcome = synthesizeToSpool(job, request, voice)
                DiagnosticsLog.debug(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 引擎尝试 $attempt",
                    "${voice.engineCode} · ${(System.nanoTime() - attemptStarted) / 1_000_000} ms · ${outcome.bytes} 字节 · code ${outcome.code}")
                if (outcome.ok) {
                    // 预取模式下合成完就写缓存：暂停、跳段后回来能直接命中，也不占用段间的时间。
                    // 写失败只是少一次命中，不影响本次朗读。
                    if (job.lookahead) storeInCache(job, request, outcome.file)
                    DiagnosticsLog.info(
                        Category.SPEECH,
                        "请求 ${job.requestId} · ${planned.segment.id} 就绪（候选 ${candidateIndex + 1}/${candidates.size}，第 ${attempt} 次尝试，${outcome.bytes} 字节）",
                    )
                    return Prepared.Spooled(
                        outcome.file,
                        request,
                        voice,
                        if (voice.systemName == planned.voice.systemName) planned.segment.fallbackReason
                        else "主音色合成失败，使用备用音色 ${voice.displayName}",
                    )
                }

                outcome.file.delete()
                if (job.cancelled.get() || closed.get()) return Prepared.Stopped
                if (outcome.code == SynthesisError.NO_AUDIO) {
                    DiagnosticsLog.warn(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 空音频片段已跳过")
                    return Prepared.Empty
                }
                lastCode = outcome.code
                lastMessage = outcome.message
                DiagnosticsLog.warn(
                    Category.SPEECH,
                    "请求 ${job.requestId} · ${planned.segment.id} 音色失败 [${outcome.code}] · ${voice.displayName}",
                    outcome.message?.take(200),
                )
                // 失败只返回、不在这里 failJob：预取时上一段可能还在播，由 play() 在轮到本段时再报。
                if (isFatal(outcome.code)) return Prepared.Failed(lastCode, lastMessage)
                if (job.retryPolicy.shouldRetry(outcome.code, attempt)) {
                    if (!pauseBeforeRetry(job, job.retryPolicy.retryDelayMillis(attempt))) return Prepared.Stopped
                    attempt += 1
                    continue
                }
                if (!job.retryPolicy.allowsStandby(outcome.code)) return Prepared.Failed(lastCode, lastMessage)
                if (candidateIndex < candidates.lastIndex &&
                    !pauseBeforeRetry(job, job.retryPolicy.retryDelayMillis(attempt))) return Prepared.Stopped
                break // 同音色不再重试，换备用音色
            }
        }

        return Prepared.Failed(lastCode, lastMessage)
    }

    private fun pauseBeforeRetry(job: Job, millis: Long): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (!stopped(job)) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return true
            Thread.sleep(minOf(25L, (remaining / 1_000_000L).coerceAtLeast(1L)))
        }
        return false
    }

    /**
     * 候选音色 = 主音色 + 同段备用。
     *
     * 备用从本次合成计划里挑：同样的 [PipelineSegment.isDialogue] 分类、可用、
     * 且与主音色不同，最多 [MAX_STANDBY_VOICES] 个。计划里没有其它音色时自然只有主音色。
     */
    private fun candidatesFor(job: Job, planned: PlannedSegment): List<VoiceDescriptor> {
        val primary = planned.voice
        if (!job.retryPolicy.allowStandbyVoice) return listOf(primary)
        val standby = job.segments.asSequence()
            .filter { it.segment.isDialogue == planned.segment.isDialogue }
            .map { it.voice }
            .filter { it.usable && it.systemName != primary.systemName }
            .distinctBy { it.systemName }
            .take(MAX_STANDBY_VOICES)
            .toList()
        return listOf(primary) + standby
    }

    private fun synthesizeToSpool(
        job: Job,
        request: SynthesisRequest,
        voice: VoiceDescriptor,
    ): SpoolOutcome {
        val file = File.createTempFile("segment-", ".pcm", job.spoolDir)
        val attempt = SpoolAttempt(file, job)
        job.attempt.set(attempt)
        val handle = try {
            engineCall(voice, request, attempt)
        } catch (error: OutOfMemoryError) {
            attempt.onError(SynthesisError.RESOURCE_LIMIT, "引擎音频内存不足")
            job.attempt.compareAndSet(attempt, null)
            return attempt.await(0)
        } catch (error: Exception) {
            DiagnosticsLog.error(Category.SPEECH, "调用引擎失败：${voice.engineCode}", error.message?.take(200))
            attempt.onError(SynthesisError.SYNTHESIS_FAILED, error.message ?: "引擎调用失败")
            job.attempt.compareAndSet(attempt, null)
            return attempt.await(0)
        }
        job.current.set(handle)
        if (job.cancelled.get() || attempt.limitReached()) {
            handle.cancel()
            attempt.abort()
        }
        val outcome = attempt.await(request.remainingTimeoutMillis() ?: timeoutMillis(request.text.length, job.timeoutMs))
        // An engine may complete synchronously after using up the deadline before await begins.
        val expired = request.remainingTimeoutMillis() == 0L &&
            (outcome.ok || outcome.code == SynthesisError.NO_AUDIO)
        if (expired) runCatching { handle.cancel() }
        job.current.compareAndSet(handle, null)
        job.attempt.compareAndSet(attempt, null)
        return if (expired) SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, "合成超时", outcome.bytes) else outcome
    }

    private fun streamSpool(job: Job, file: File) {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(SPOOL_CHUNK_BYTES)
            while (!job.cancelled.get() && !job.failed.get() && !closed.get()) {
                val read = input.read(buffer)
                if (read <= 0) break
                job.listener.onAudio(buffer, 0, read)
            }
        }
    }

    /**
     * 查缓存：命中就拿住读 lease（回放结束才释放，期间不会被淘汰）。
     * 文件被系统清理或截断时删掉这条缓存并返回 null，调用方照常走合成/重试路径。
     */
    private fun cachedFor(job: Job, planned: PlannedSegment): Prepared.Cached? {
        val cache = this.cache ?: return null
        if (!job.cacheEnabled) return null
        val key = cacheKeyOf(buildRequest(job, planned, planned.voice), job)
        val lease = cache.acquireRead(key) ?: return null
        val length = lease.file.length()
        if (!lease.file.isFile || length <= 0L || length > app.noveltts.engine.api.AudioBudget.SPOOL || length % BYTES_PER_FRAME != 0L) {
            runCatching { lease.close() }
            cache.invalidate(key)
            return null
        }
        DiagnosticsLog.debug(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 缓存命中")
        return Prepared.Cached(lease)
    }

    /** 回放缓存文件；读取出错时按失败结束任务并返回 false。 */
    private fun streamCached(job: Job, index: Int, lease: CacheReadLease): Boolean {
        val stream = lease.input
        val buffer = ByteArray(SPOOL_CHUNK_BYTES)
        while (!stopped(job)) {
            val read = try {
                stream.read(buffer)
            } catch (error: Exception) {
                // 缓存被系统清理或截断：按失败结束本段，绝不让异常冲出计划线程。
                DiagnosticsLog.error(Category.SPEECH, "读取缓存音频失败", error.message?.take(200))
                failJob(job, index, SynthesisError.SYNTHESIS_FAILED, error.message ?: "缓存音频读取失败")
                return false
            }
            if (read <= 0) break
            job.listener.onAudio(buffer, 0, read)
        }
        return true
    }

    /** 合成成功且未取消时把 spool 音频复制进缓存；任何异常都只当这次没缓存。 */
    private fun storeInCache(job: Job, request: SynthesisRequest, spool: File) {
        val cache = this.cache ?: return
        if (!job.cacheEnabled || job.cancelled.get() || job.failed.get() || closed.get()) return
        if (spool.length() <= 0L) return
        val key = cacheKeyOf(request, job)
        val tmp = runCatching { cache.beginWrite(key) }.getOrNull() ?: return
        val copied = runCatching { spool.copyTo(tmp, overwrite = true) }.isSuccess
        if (copied) {
            cache.commitWrite(tmp, key)
            runCatching { cache.evictIfNeeded(force = false) }
        } else {
            cache.abortWrite(tmp)
        }
    }

    /** 把合成参数按 0.01 精度量化后算缓存 key（Float 直接比会因末位差异反复 miss）。 */
    private fun cacheKeyOf(request: SynthesisRequest, job: Job): String = cacheKey(
        voice = request.voiceId.orEmpty(),
        rate = (request.rate * 100f).roundToInt(),
        pitch = (request.pitch * 100f).roundToInt(),
        volume = (request.volume * 100f).roundToInt(),
        text = request.text,
        revision = job.revisions[request.voiceId].orEmpty(),
    )

    /** 一次“合成到 spool”的尝试：只落盘，不碰 listener；取消/错误都通过 [await] 返回。 */
    private inner class SpoolAttempt(
        private val file: File,
        private val job: Job,
    ) : SynthesisListener {
        private val done = CountDownLatch(1)
        private val finished = AtomicBoolean(false)
        private var stream: FileOutputStream? = null

        @Volatile
        private var bytes: Long = 0L

        @Volatile
        private var outcome: SpoolOutcome? = null

        /** 取消时由 cancel() 调用：立即结束等待，绝不重试。 */
        @Synchronized
        fun abort() {
            if (!finished.compareAndSet(false, true)) {
                done.countDown()
                return
            }
            closeQuietly()
            outcome = SpoolOutcome(false, file, SynthesisError.CANCELLED, "已取消", bytes)
            done.countDown()
        }

        fun await(timeoutMs: Long): SpoolOutcome {
            try {
                if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    // 先 abort 抢到票，再撤销引擎侧这次请求：cancel 会同步回调
                    // CANCELLED，抢不到票就会被吞掉，不会把"超时"错报成"已取消"。
                    // 不撤销的话它继续占着单线程执行器，后续每次重试都排在它后面，
                    // 于是每一轮都必然再超时。
                    abort()
                    job.current.getAndSet(null)?.cancel()
                    return SpoolOutcome(
                        false,
                        file,
                        SynthesisError.SYNTHESIS_FAILED,
                        "合成超时（" + (timeoutMs / 1000) + " 秒）",
                        bytes,
                    )
                }
            } catch (interrupted: InterruptedException) {
                abort()
                job.current.getAndSet(null)?.cancel()
                Thread.currentThread().interrupt()
            }
            return outcome ?: SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, "合成未完成", bytes)
        }

        @Synchronized
        override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
            if (finished.get() || job.cancelled.get() || closed.get() || stream != null) return
            if (sampleRateHz != SAMPLE_RATE_HZ || audioFormat != AudioFormat.ENCODING_PCM_16BIT || channelCount != 1) {
                finish(
                    SpoolOutcome(
                        false,
                        file,
                        SynthesisError.UNSUPPORTED_AUDIO,
                        "引擎返回了不支持的音频格式：${sampleRateHz}Hz/${audioFormat}/${channelCount}",
                        0L,
                    ),
                )
                return
            }
            val opened = runCatching { FileOutputStream(file) }.getOrNull()
            if (opened == null) {
                finish(SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, "无法创建 spool 文件", 0L))
                return
            }
            stream = opened
        }

        @Synchronized
        override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
            if (finished.get() || length <= 0) return
            try {
                app.noveltts.engine.api.AudioBudget.check(bytes, length.toLong(), app.noveltts.engine.api.AudioBudget.SPOOL, "单段音频")
            } catch (error: app.noveltts.engine.api.ResourceLimitException) {
                finish(SpoolOutcome(false, file, SynthesisError.RESOURCE_LIMIT, error.message, bytes))
                job.current.getAndSet(null)?.cancel()
                return
            }
            val output = stream ?: return
            runCatching { output.write(pcm, offset, length) }
                .onSuccess { bytes += length }
                .onFailure { finish(SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, it.message ?: "写入 spool 失败", bytes)) }
        }

        @Synchronized
        override fun onComplete() {
            if (job.cancelled.get() || closed.get()) {
                finish(SpoolOutcome(false, file, SynthesisError.CANCELLED, "已取消", bytes))
                return
            }
            if (bytes <= 0L) {
                finish(SpoolOutcome(false, file, SynthesisError.NO_AUDIO, "引擎没有返回音频", 0L))
                return
            }
            finish(SpoolOutcome(true, file, 0, null, bytes))
        }

        @Synchronized
        override fun onError(code: Int, message: String?) {
            finish(SpoolOutcome(false, file, code, message, bytes))
        }

        fun limitReached(): Boolean = outcome?.code == SynthesisError.RESOURCE_LIMIT

        @Synchronized
        private fun finish(result: SpoolOutcome) {
            if (!finished.compareAndSet(false, true)) return
            closeQuietly()
            outcome = result
            done.countDown()
        }

        private fun closeQuietly() {
            runCatching { stream?.close() }
            stream = null
        }
    }

    private data class SpoolOutcome(
        val ok: Boolean,
        val file: File,
        val code: Int,
        val message: String?,
        val bytes: Long,
    )

    private fun buildRequest(job: Job, planned: PlannedSegment, voice: VoiceDescriptor): SynthesisRequest =
        SynthesisRequest(
            text = planned.segment.text,
            voiceId = voice.systemName,
            rate = app.noveltts.engine.api.SynthesisParameters.rate(job.rate * voice.defaultSpeed * planned.segment.speechRate),
            pitch = app.noveltts.engine.api.SynthesisParameters.pitch(job.pitch * voice.defaultPitch * planned.segment.speechPitch),
            volume = app.noveltts.engine.api.SynthesisParameters.volume(job.volume * voice.defaultVolume * planned.segment.speechVolume),
            options = job.options,
            requestId = job.requestId,
            segmentId = planned.segment.id,
            deadlineNanos = System.nanoTime() + timeoutMillis(planned.segment.text.length, job.timeoutMs) * 1_000_000L,
        )

    /** 统一的终止路径：取消或已失败时不再回调，保证 listener 只收到一次 onError。 */
    private fun failJob(job: Job, index: Int, code: Int, message: String?) {
        if (job.cancelled.get() || !job.failed.compareAndSet(false, true)) return
        DiagnosticsLog.error(Category.SPEECH, "请求 ${job.requestId} · ${job.segments.getOrNull(index)?.segment?.id} 合成失败 [${code}]", message?.take(200))
        // 预取线程可能正等着下一段的引擎结果：先 abort 再 cancel，顺序同超时路径。
        job.attempt.getAndSet(null)?.abort()
        job.current.getAndSet(null)?.cancel()
        job.listener.onError(code, message)
    }

    private class Job(
        val segments: List<PlannedSegment>,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val listener: SynthesisListener,
        val progress: SegmentProgressListener?,
        val spoolDir: File?,
        val playback: SegmentPlaybackListener?,
        val retryPolicy: RetryPolicy,
        val silenceMs: Int,
        val timeoutMs: Long,
        val options: app.noveltts.engine.api.EngineRequestOptions?,
        val cacheEnabled: Boolean,
        val revisions: Map<String, String>,
        val requestId: String,
        /** 当前段回放期间提前准备下一段。 */
        val lookahead: Boolean,
    ) {
        private val createdAt = System.nanoTime()
        fun elapsedMs(): Long = (System.nanoTime() - createdAt) / 1_000_000
        val dispatched = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val current = AtomicReference<SynthesisHandle?>(null)
        val attempt = AtomicReference<SpoolAttempt?>(null)
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 24_000
        const val SPOOL_CHUNK_BYTES = 4 * 1024
        const val MAX_STANDBY_VOICES = 2
        const val DEFAULT_SILENCE_MS = 80
        const val MAX_SILENCE_MS = 2_000
        const val DEFAULT_REQUEST_TIMEOUT_SEC = 60
        const val BYTES_PER_FRAME = 2L
        const val MB = 1024L * 1024L
    }
}
