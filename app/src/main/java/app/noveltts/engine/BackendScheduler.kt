package app.noveltts.engine

import app.noveltts.engine.api.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One process-wide budget shared by app, system TTS and HTTP managers. */
class BackendScheduler(private val limit: Int = 2, private val queueLimit: Int = 8) {
    private val lock = Any()
    private val active = mutableSetOf<Task>()
    private val waiting = mutableListOf<Task>()
    private val executor = Executors.newFixedThreadPool(limit) { r -> Thread(r, "noveltts-backend").apply { isDaemon = true } }

    fun submit(owner: Any, request: SynthesisRequest, listener: SynthesisListener,
        start: (SynthesisListener) -> SynthesisHandle): SynthesisHandle {
        val task = Task(owner, request, listener, start)
        val accepted = synchronized(lock) {
            if (active.size < limit) { active.add(task); executor.execute { task.run() }; true }
            else if (waiting.size < queueLimit) { waiting.add(task); true }
            else false
        }
        if (!accepted) {
            task.ended.set(true)
            listener.onError(SynthesisError.BUSY, "合成繁忙：已有 $limit 个活动任务和 $queueLimit 个等待任务，请稍后重试")
        }
        return task
    }

    fun cancelOwner(owner: Any) {
        val tasks = synchronized(lock) { (active + waiting).filter { it.owner === owner } }
        tasks.forEach { it.cancel() }
    }

    private inner class Task(val owner: Any, val request: SynthesisRequest,
        private val listener: SynthesisListener, private val start: (SynthesisListener) -> SynthesisHandle) : SynthesisHandle {
        val ended = AtomicBoolean(false)
        private val handle = AtomicReference<SynthesisHandle?>(null)
        private val created = System.nanoTime()
        private val firstPcm = AtomicBoolean(false)
        fun run() {
            if (ended.get()) return
            request.timing.mark("queue-finished")
            DiagnosticsLog.debug(Category.ENGINE, "请求 ${request.requestId} · ${request.segmentId} 后端开始",
                "queue=${(System.nanoTime() - created) / 1_000_000}ms · rate=${request.rate} pitch=${request.pitch} volume=${request.volume}")
            val callback = object : SynthesisListener {
                override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
                    if (!ended.get()) listener.onStart(sampleRateHz, audioFormat, channelCount)
                }
                override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
                    if (ended.get()) return
                    if (firstPcm.compareAndSet(false, true)) request.timing.mark("first-pcm")
                    listener.onAudio(pcm, offset, length)
                }
                override fun onComplete() { finish { listener.onComplete() } }
                override fun onError(code: Int, message: String?) { finish { listener.onError(code, message) } }
            }
            try {
                val result = start(callback)
                handle.set(result)
                if (ended.get()) handle.getAndSet(null)?.cancel()
            } catch (error: Exception) { callback.onError(SynthesisError.SYNTHESIS_FAILED, error.message) }
        }
        private fun finish(callback: () -> Unit) {
            if (!ended.compareAndSet(false, true)) return
            try { callback() } finally { release() }
        }
        private fun release() {
            synchronized(lock) {
                waiting.remove(this)
                active.remove(this)
                while (active.size < limit && waiting.isNotEmpty()) {
                    val next = waiting.firstOrNull { !it.request.prefetch } ?: waiting.first()
                    waiting.remove(next)
                    if (!next.ended.get()) { active.add(next); executor.execute { next.run() } }
                }
            }
        }
        override fun cancel() {
            if (!ended.compareAndSet(false, true)) return
            try { handle.getAndSet(null)?.cancel() } finally {
                try { listener.onError(SynthesisError.CANCELLED, "已取消") } finally { release() }
            }
        }
    }

    companion object { val shared = BackendScheduler() }
}