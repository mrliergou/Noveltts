package app.noveltts.forward

import app.noveltts.engine.api.AudioBudget
import app.noveltts.engine.api.ResourceLimitException
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ForwardPcmCollector(
    private val maximumBytes: Long = 32L * 1024 * 1024,
    private val pcm: ByteArrayOutputStream = ByteArrayOutputStream(),
) : SynthesisListener {
    private val done = CountDownLatch(1)
    private val lock = Any()
    private var failure: ForwardHttpException? = null
    private var finished = false

    override fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int) {
        // PCM16 is Android format 2; this collector also runs in JVM regressions.
        if (sampleRateHz != 24_000 || audioFormat != 2 || channelCount != 1)
            onError(SynthesisError.UNSUPPORTED_AUDIO, "Unsupported PCM format")
    }

    override fun onAudio(pcm: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        if (!finished) {
            try {
                AudioBudget.check(this.pcm.size().toLong(), length.toLong(), maximumBytes, "HTTP PCM")
                this.pcm.write(pcm, offset, length)
            } catch (error: ResourceLimitException) {
                onError(SynthesisError.RESOURCE_LIMIT, error.message)
            } catch (error: OutOfMemoryError) {
                onError(SynthesisError.RESOURCE_LIMIT, "HTTP audio allocation failed")
            }
        }
        Unit
    }

    override fun onComplete() = synchronized(lock) { finished = true; done.countDown() }

    override fun onError(code: Int, message: String?) = synchronized(lock) {
        if (!finished) {
            val status = when (code) {
                SynthesisError.BAD_REQUEST, SynthesisError.NO_AUDIO -> 400
                SynthesisError.RESOURCE_LIMIT -> 413
                SynthesisError.BUSY -> 503
                else -> 500
            }
            failure = ForwardHttpException(status,
                if (code == SynthesisError.NO_AUDIO) "no_audio" else "synthesis_failed", message ?: "Synthesis failed")
            finished = true
            done.countDown()
        }
    }

    fun await(context: ForwardRequestContext): ForwardAudio {
        while (!done.await(50, TimeUnit.MILLISECONDS)) context.ensureActive()
        context.ensureActive()
        return synchronized(lock) {
            failure?.let { throw it }
            if (pcm.size() == 0) throw ForwardHttpException(400, "no_audio", "No output audio")
            try { ForwardAudio(24_000, pcm.toByteArray()) }
            catch (error: OutOfMemoryError) { throw ForwardHttpException(413, "synthesis_failed", "HTTP audio allocation failed") }
        }
    }
}
