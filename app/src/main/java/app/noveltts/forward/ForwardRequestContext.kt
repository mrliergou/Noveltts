package app.noveltts.forward

import app.noveltts.engine.api.SynthesisHandle
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ForwardRequestContext(
    timeoutMs: Long = 300_000,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val id: String = UUID.randomUUID().toString().take(8)
    private val deadline = clockMs() + timeoutMs
    private val failure = AtomicReference<ForwardHttpException?>()
    private val handle = AtomicReference<SynthesisHandle?>()
    private val completed = AtomicBoolean()
    private val started = AtomicBoolean()
    var onSynthesisStart: () -> Unit = {}
    val isFinished: Boolean get() = completed.get() || failure.get() != null

    /** Check elapsed time even while a peer keeps sending data below the socket idle timeout. */
    fun guardInput(input: InputStream): InputStream = object : InputStream() {
        override fun read(): Int {
            ensureActive()
            val value = input.read()
            ensureActive()
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            ensureActive()
            val count = input.read(bytes, offset, length)
            ensureActive()
            return count
        }
    }

    fun attach(value: SynthesisHandle) {
        handle.set(value)
        if (failure.get() != null || completed.get()) handle.getAndSet(null)?.cancel()
    }
    fun beginSynthesis() { ensureActive(); if (started.compareAndSet(false, true)) onSynthesisStart() }
    fun ensureActive() {
        if (clockMs() >= deadline) cancel("请求等待或合成超时", 504, "request_timeout")
        failure.get()?.let { throw it }
    }
    fun cancel(message: String = "调用方已断开连接", status: Int = 499, code: String = "client_disconnected") {
        if (completed.get()) return
        if (failure.compareAndSet(null, ForwardHttpException(status, code, message)))
            runCatching { handle.getAndSet(null)?.cancel() }
    }
    fun finish() { completed.set(true); handle.set(null) }
}

internal class ForwardRequestQueue(workers: Int = 3, capacity: Int = 9) {
    private val pool = ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity), { runnable -> Thread(runnable, "noveltts-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    val activeCount: Int get() = pool.activeCount
    val pendingCount: Int get() = pool.queue.size
    fun execute(task: () -> Unit): Boolean = try { pool.execute(task); true }
        catch (_: java.util.concurrent.RejectedExecutionException) { false }
    fun shutdown() { pool.shutdownNow() }
}
