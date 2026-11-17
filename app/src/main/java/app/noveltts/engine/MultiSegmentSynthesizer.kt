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

    private fun silence(): ByteArray {
        val ms = silenceMs
        return if (ms <= 0) ByteArray(0) else ByteArray(SAMPLE_RATE_HZ * 2 * ms / 1000)
    }
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
    ): SynthesisHandle {
        val capturedOptions = options ?: engines?.requestOptions()
        val job = Job(segments.toList(), rate, pitch, volume, listener, onProgress, spoolDir,
            onPlayback, retryPolicy, silenceMs, spoolTimeoutMs, capturedOptions, cache?.enabled == true,
            segments.map { it.voice }.distinctBy { it.systemName }.associate { voice -> voice.systemName to
                if (engines != null && capturedOptions != null) engines.cacheRevision(voice, capturedOptions) else voice.contentRevision }, requestId)
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
        if (job.cancelled.get()) inner.cancel()
    }

    private inner class SegmentCallback(
        private val job: Job,
        private val index: Int,
    ) : SynthesisListener {
        private val finished = AtomicBoolean(false)

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
            if (!finished.compareAndSet(false, true)) return
            fail(code, message)
        }

        private fun fail(code: Int, message: String?) {
            failJob(job, index, code, message)
        }
    }

    // ---- spool + 重试模式 ----------------------------------------------------

    private fun spoolAt(job: Job, index: Int) {
        for (position in index until job.segments.size) {
            if (job.cancelled.get() || job.failed.get() || closed.get()) return
            job.progress?.onSegmentStart(position, job.segments.size, job.segments[position].segment.text)
            val startedAt = System.nanoTime()
            val cached = playFromCache(job, position, job.segments[position])
            if (!cached && !spoolOne(job, position)) return
            DiagnosticsLog.debug(Category.SPEECH, "请求 ${job.requestId} · ${job.segments[position].segment.id} 已输出",
                "${if (cached) "缓存" else "合成"} · 耗时 ${(System.nanoTime() - startedAt) / 1_000_000} ms")
            if (position < job.segments.lastIndex) {
                val silence = ByteArray(SAMPLE_RATE_HZ * 2 * job.silenceMs / 1000)
                if (silence.isNotEmpty()) job.listener.onAudio(silence, 0, silence.size)
            }
        }
        if (!job.cancelled.get() && !job.failed.get()) {
            job.completed.set(true)
            job.listener.onComplete()
            DiagnosticsLog.info(Category.SPEECH, "请求 ${job.requestId} 合成完成", "耗时 ${job.elapsedMs()} ms")
        }
    }

    private fun spoolOne(job: Job, index: Int): Boolean {
        val planned = job.segments[index]

        DiagnosticsLog.info(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 合成 · ${planned.voice.displayName}", DiagnosticsLog.textHint(planned.segment.text))

        val candidates = candidatesFor(job, planned)
        var lastCode = SynthesisError.SYNTHESIS_FAILED
        var lastMessage: String? = null

        for ((candidateIndex, voice) in candidates.withIndex()) {
            var attempt = 1
            while (true) {
                if (job.cancelled.get() || job.failed.get() || closed.get()) return false
                val request = buildRequest(job, planned, voice)
                val attemptStarted = System.nanoTime()
                val outcome = synthesizeToSpool(job, request, voice)
                DiagnosticsLog.debug(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 引擎尝试 $attempt",
                    "${voice.engineCode} · ${(System.nanoTime() - attemptStarted) / 1_000_000} ms · ${outcome.bytes} 字节 · code ${outcome.code}")
                if (outcome.ok) {
                    if (job.started.compareAndSet(false, true)) {
                        job.listener.onStart(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                    }
                    try {
                        job.playback?.onVoiceSelected(index, voice, if (voice.systemName == planned.voice.systemName)
                            planned.segment.fallbackReason else "主音色合成失败，使用备用音色 ${voice.displayName}")
                        job.playback?.onPlaybackStart(index)
                        streamSpool(job, outcome.file)
                        job.playback?.onPlaybackComplete(index)
                        // 播放结束且未取消才写缓存；写失败只是少一次命中，不影响本次朗读。
                        storeInCache(job, request, outcome.file)
                    } finally {
                        outcome.file.delete()
                    }
                    if (job.cancelled.get() || job.failed.get() || closed.get()) return false
                    DiagnosticsLog.info(
                        Category.SPEECH,
                        "请求 ${job.requestId} · ${planned.segment.id} 就绪（候选 ${candidateIndex + 1}/${candidates.size}，第 ${attempt} 次尝试，${outcome.bytes} 字节）",
                    )
                    return true
                }

                outcome.file.delete()
                if (job.cancelled.get() || closed.get()) return false
                lastCode = outcome.code
                lastMessage = outcome.message
                DiagnosticsLog.warn(
                    Category.SPEECH,
                    "请求 ${job.requestId} · ${planned.segment.id} 音色失败 [${outcome.code}] · ${voice.displayName}",
                    outcome.message?.take(200),
                )
                if (isFatal(outcome.code)) {
                    failJob(job, index, lastCode, lastMessage)
                    return false
                }
                if (job.retryPolicy.shouldRetry(outcome.code, attempt)) {
                    attempt += 1
                    continue
                }
                if (!job.retryPolicy.allowsStandby(outcome.code)) {
                    failJob(job, index, lastCode, lastMessage)
                    return false
                }
                break // 同音色不再重试，换备用音色
            }
        }

        failJob(job, index, lastCode, lastMessage)
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
        } catch (error: Exception) {
            DiagnosticsLog.error(Category.SPEECH, "调用引擎失败：${voice.engineCode}", error.message?.take(200))
            job.attempt.compareAndSet(attempt, null)
            return SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, error.message ?: "引擎调用失败", 0L)
        }
        job.current.set(handle)
        if (job.cancelled.get()) {
            handle.cancel()
            attempt.abort()
        }
        val outcome = attempt.await((20_000L + request.text.length * 150L).coerceIn(job.timeoutMs, MAX_SPOOL_TIMEOUT_MS))
        job.current.compareAndSet(handle, null)
        job.attempt.compareAndSet(attempt, null)
        return outcome
    }

    /**
     * 超时按文本长度放宽：500 字中文约 100 秒音频，固定 60 秒会让长段必然失败。
     * 下限是用户配置的请求超时，上限 300 秒兜底。
     */
    private fun timeoutFor(text: String): Long =
        (20_000L + text.length * 150L).coerceIn(spoolTimeoutMs, MAX_SPOOL_TIMEOUT_MS)

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
     * 缓存命中时把缓存文件当作本段的 spool 音频回放，并推进到下一段。
     * 返回 true 表示本段已处理；false 表示没命中（或缓存文件不可读且一个字节都没写），
     * 调用方照常走合成/重试路径。
     */
    private fun playFromCache(job: Job, index: Int, planned: PlannedSegment): Boolean {
        val cache = this.cache ?: return false
        if (!job.cacheEnabled) return false
        val key = cacheKeyOf(buildRequest(job, planned, planned.voice), job)
        val lease = cache.acquireRead(key) ?: return false
        DiagnosticsLog.debug(Category.SPEECH, "请求 ${job.requestId} · ${planned.segment.id} 缓存命中")
        val streamed = try { streamCached(job, index, lease) } finally { lease.close() }
        if (!streamed) {
            // 文件被系统清理/截断：删掉这条缓存，回到正常合成路径重新生成。
            cache.invalidate(key)
            return false
        }
        if (job.cancelled.get() || job.failed.get() || closed.get()) return true
        job.playback?.onPlaybackComplete(index)
        return true
    }

    /** 回放缓存文件；false 表示还没往 listener 写过任何字节，可以安全回退到合成。 */
    private fun streamCached(job: Job, index: Int, lease: CacheReadLease): Boolean {
        val file = lease.file
        val length = file.length()
        if (!file.isFile || length <= 0L || length % BYTES_PER_FRAME != 0L) return false
        val input = lease.input
        input.use { stream ->
            if (job.started.compareAndSet(false, true)) {
                job.listener.onStart(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            }
            job.playback?.onVoiceSelected(index, job.segments[index].voice, job.segments[index].segment.fallbackReason)
            job.playback?.onPlaybackStart(index)
            val buffer = ByteArray(SPOOL_CHUNK_BYTES)
            while (!job.cancelled.get() && !job.failed.get() && !closed.get()) {
                val read = try {
                    stream.read(buffer)
                } catch (error: Exception) {
                    // 缓存被系统清理或截断：按失败结束本段，绝不让异常冲出计划线程。
                    DiagnosticsLog.error(Category.SPEECH, "读取缓存音频失败", error.message?.take(200))
                    failJob(job, index, SynthesisError.SYNTHESIS_FAILED, error.message ?: "缓存音频读取失败")
                    return true
                }
                if (read <= 0) break
                job.listener.onAudio(buffer, 0, read)
            }
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
                Thread.currentThread().interrupt()
                abort()
            }
            return outcome ?: SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, "合成未完成", bytes)
        }

        override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
            if (finished.get() || job.cancelled.get() || closed.get()) return
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

        override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
            if (finished.get() || length <= 0) return
            val output = stream ?: return
            runCatching { output.write(pcm, offset, length) }
                .onSuccess { bytes += length }
                .onFailure { finish(SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, it.message ?: "写入 spool 失败", bytes)) }
        }

        override fun onComplete() {
            if (job.cancelled.get() || closed.get()) {
                finish(SpoolOutcome(false, file, SynthesisError.CANCELLED, "已取消", bytes))
                return
            }
            if (bytes <= 0L) {
                finish(SpoolOutcome(false, file, SynthesisError.SYNTHESIS_FAILED, "引擎没有返回音频", 0L))
                return
            }
            finish(SpoolOutcome(true, file, 0, null, bytes))
        }

        override fun onError(code: Int, message: String?) {
            finish(SpoolOutcome(false, file, code, message, bytes))
        }

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
            rate = (job.rate * voice.defaultSpeed * planned.segment.speechRate).coerceIn(0.25f, 4f),
            pitch = (job.pitch * voice.defaultPitch * planned.segment.speechPitch).coerceIn(0.25f, 4f),
            volume = (job.volume * voice.defaultVolume * planned.segment.speechVolume).coerceIn(0f, 1f),
            options = job.options,
            requestId = job.requestId,
            segmentId = planned.segment.id,
        )

    /** 统一的终止路径：取消或已失败时不再回调，保证 listener 只收到一次 onError。 */
    private fun failJob(job: Job, index: Int, code: Int, message: String?) {
        if (job.cancelled.get() || !job.failed.compareAndSet(false, true)) return
        DiagnosticsLog.error(Category.SPEECH, "请求 ${job.requestId} · ${job.segments.getOrNull(index)?.segment?.id} 合成失败 [${code}]", message?.take(200))
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
        const val MAX_SPOOL_TIMEOUT_MS = 300_000L
        const val BYTES_PER_FRAME = 2L
        const val MB = 1024L * 1024L
    }
}
