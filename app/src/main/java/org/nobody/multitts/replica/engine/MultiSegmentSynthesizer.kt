package org.nobody.multitts.replica.engine

import android.media.AudioFormat
import org.nobody.multitts.replica.data.voicepack.VoiceDescriptor
import org.nobody.multitts.replica.domain.PipelineSegment
import org.nobody.multitts.replica.engine.api.SynthesisError
import org.nobody.multitts.replica.engine.api.SynthesisHandle
import org.nobody.multitts.replica.engine.api.SynthesisListener
import org.nobody.multitts.replica.engine.api.SynthesisRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PlannedSegment(
    val segment: PipelineSegment,
    val voice: VoiceDescriptor,
)

/**
 * 串行合成多角色片段，统一输出 24 kHz / 16-bit / 单声道 PCM。
 * 片段之间插入 80 ms 静音，取消会立即中断当前引擎并丢弃后续片段。
 */
class MultiSegmentSynthesizer(
    private val engines: EngineManager,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "multitts-plan").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    fun synthesize(
        segments: List<PlannedSegment>,
        rate: Float,
        pitch: Float,
        volume: Float,
        listener: SynthesisListener,
    ): SynthesisHandle {
        val job = Job(segments, rate, pitch, volume, listener)
        val handle = object : SynthesisHandle {
            override fun cancel() {
                job.cancelled.set(true)
                job.current.getAndSet(null)?.cancel()
            }
        }
        if (segments.isEmpty()) {
            listener.onStart(24_000, AudioFormat.ENCODING_PCM_16BIT, 1)
            listener.onComplete()
            return handle
        }
        executor.execute { synthesizeAt(job, 0) }
        return handle
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        engines.shutdown()
    }

    private fun synthesizeAt(job: Job, index: Int) {
        if (job.cancelled.get() || closed.get() || job.failed.get()) return
        if (index >= job.segments.size) {
            if (job.cancelled.get() || job.failed.get()) return
            job.listener.onComplete()
            return
        }

        val planned = job.segments[index]
        val request = SynthesisRequest(
            text = planned.segment.text,
            voiceId = planned.voice.systemName,
            rate = (job.rate * planned.voice.defaultSpeed).coerceIn(0.25f, 4f),
            pitch = (job.pitch * planned.voice.defaultPitch).coerceIn(0.25f, 4f),
            volume = (job.volume * planned.voice.defaultVolume).coerceIn(0f, 1f),
        )
        val callback = SegmentCallback(job, index)
        val inner = engines.speak(planned.voice, request, callback)
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
            if (sampleRateHz != 24_000 || audioFormat != AudioFormat.ENCODING_PCM_16BIT || channelCount != 1) {
                fail(
                    SynthesisError.UNSUPPORTED_AUDIO,
                    "引擎返回了不支持的音频格式：${sampleRateHz}Hz/$audioFormat/$channelCount",
                )
                return
            }
            if (job.started.compareAndSet(false, true)) {
                job.listener.onStart(sampleRateHz, audioFormat, channelCount)
            }
        }

        override fun onAudio(pcm: ByteArray, offset: Int, length: Int) {
            if (job.cancelled.get() || job.failed.get()) return
            job.listener.onAudio(pcm, offset, length)
        }

        override fun onComplete() {
            if (!finished.compareAndSet(false, true) || job.cancelled.get() || job.failed.get()) return
            job.current.compareAndSet(null, null)
            if (index < job.segments.lastIndex && !job.cancelled.get()) {
                job.listener.onAudio(SILENCE_80MS, 0, SILENCE_80MS.size)
            }
            synthesizeAt(job, index + 1)
        }

        override fun onError(code: Int, message: String?) {
            if (!finished.compareAndSet(false, true)) return
            fail(code, message)
        }

        private fun fail(code: Int, message: String?) {
            if (job.cancelled.get() || !job.failed.compareAndSet(false, true)) return
            job.current.getAndSet(null)?.cancel()
            job.listener.onError(code, message)
        }
    }

    private class Job(
        val segments: List<PlannedSegment>,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val listener: SynthesisListener,
    ) {
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val current = AtomicReference<SynthesisHandle?>(null)
    }

    private companion object {
        val SILENCE_80MS = ByteArray(24_000 * 2 * 80 / 1000)
    }
}
