package app.noveltts.service

import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The platform synthesis thread is the only caller of this sink. */
internal interface SynthesisSink {
    val maxBufferSize: Int
    fun start(sampleRate: Int, format: Int, channels: Int): Int
    fun audio(bytes: ByteArray, offset: Int, length: Int): Int
    fun error(code: Int)
    fun done()
    fun hasFinished(): Boolean
}

/** Bounded handoff from the plan thread; the timeout counts waiting, never platform backpressure. */
internal class SynthesisCallbackPump(private val idleTimeoutMillis: Long = 600_000) : SynthesisListener {
    private sealed interface Event {
        data class Start(val rate: Int, val format: Int, val channels: Int) : Event
        data class Audio(val bytes: ByteArray) : Event
    }
    private data class End(val error: Int? = null, val stopped: Boolean = false)
    private val queue = ArrayBlockingQueue<Event>(8)
    private val end = AtomicReference<End?>()
    private val handle = AtomicReference<SynthesisHandle?>()

    fun attach(value: SynthesisHandle) {
        handle.set(value)
        if (end.get()?.let { it.stopped || it.error != null } == true) cancelHandle()
    }

    override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
        enqueue(Event.Start(sampleRateHz, audioFormat, channelCount))
    }

    override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= pcm.size - length)
        var position = offset
        while (position < offset + length && end.get() == null) {
            val count = minOf(4096, offset + length - position)
            enqueue(Event.Audio(pcm.copyOfRange(position, position + count)))
            position += count
        }
    }

    override fun onComplete() { end.compareAndSet(null, End()) }
    override fun onError(code: Int, message: String?) { fail() }

    fun fail(code: Int = -3) {
        while (true) {
            val current = end.get()
            if (current?.stopped == true || current?.error != null) return
            if (end.compareAndSet(current, End(error = code))) break
        }
        queue.clear()
        cancelHandle()
    }

    /** Platform stop already owns its terminal callback; never overwrite its STOPPED status. */
    fun stop() {
        end.set(End(stopped = true))
        queue.clear()
        cancelHandle()
    }

    private fun cancelHandle() { runCatching { handle.getAndSet(null)?.cancel() } }

    private fun enqueue(event: Event) {
        while (end.get() == null) {
            if (queue.offer(event, 25, TimeUnit.MILLISECONDS)) return
        }
    }

    fun drain(sink: SynthesisSink) {
        var started = false
        var waitingSince = System.nanoTime()
        try {
            while (true) {
                val terminal = end.get()
                if (terminal?.stopped == true) return
                if (terminal?.error != null) {
                    if (!sink.hasFinished()) {
                        try { sink.error(terminal.error) } finally { sink.done() }
                    }
                    return
                }
                val event = queue.poll(25, TimeUnit.MILLISECONDS)
                if (end.get()?.let { it.stopped || it.error != null } == true) continue
                if (event == null) {
                    if (end.get() != null && queue.isEmpty()) {
                        if (!started && sink.start(24_000, 2, 1) != 0) { stop(); return }
                        if (!sink.hasFinished()) sink.done()
                        return
                    }
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitingSince) >= idleTimeoutMillis) fail(-7)
                    continue
                }
                when (event) {
                    is Event.Start -> if (!started) {
                        if (sink.start(event.rate, event.format, event.channels) != 0) { stop(); return }
                        started = true
                    }
                    is Event.Audio -> {
                        check(started) { "Audio arrived before its format" }
                        val max = sink.maxBufferSize
                        require(max > 0) { "Invalid synthesis callback buffer size" }
                        var offset = 0
                        while (offset < event.bytes.size) {
                            if (end.get()?.let { it.stopped || it.error != null } == true) break
                            val count = minOf(max, event.bytes.size - offset)
                            if (sink.audio(event.bytes, offset, count) != 0) { stop(); return }
                            offset += count
                        }
                    }
                }
                waitingSince = System.nanoTime()
            }
        } catch (error: Throwable) {
            fail()
            throw error
        } finally {
            if (end.get()?.let { it.stopped || it.error != null } == true) cancelHandle()
            handle.set(null)
        }
    }
}