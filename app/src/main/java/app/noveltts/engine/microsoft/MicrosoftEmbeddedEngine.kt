package app.noveltts.engine.microsoft

import android.media.AudioFormat
import android.util.Log
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 微软嵌入式语音。模型目录来自导入的原版离线语音包；许可证必须由用户在设置中提供，
 * 不从原版 APK 或模型包中复制任何授权信息。
 */
class MicrosoftEmbeddedEngine(
    private val settings: SettingsStore,
) {
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "multitts-embedded").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    fun speak(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle {
        val cancelled = AtomicBoolean(false)
        val synthesizerRef = AtomicReference<SpeechSynthesizer?>(null)
        val handle = object : SynthesisHandle {
            override fun cancel() {
                cancelled.set(true)
                runCatching { synthesizerRef.getAndSet(null)?.StopSpeakingAsync() }
            }
        }

        val modelDirectory = descriptor.modelDirectory
        if (request.text.isBlank()) {
            listener.onError(SynthesisError.BAD_REQUEST, "待合成文本为空")
            return handle
        }
        if (modelDirectory == null || !modelDirectory.isDirectory) {
            listener.onError(SynthesisError.BAD_REQUEST, "离线语音模型目录不存在")
            return handle
        }
        val license = settings.license()
        if (license.isNullOrBlank()) {
            listener.onError(
                SynthesisError.LICENSE_REQUIRED,
                "该离线音色需要 Microsoft Embedded Speech 许可证，请先在设置中配置",
            )
            return handle
        }

        executor.execute {
            var config: EmbeddedSpeechConfig? = null
            var synthesizer: SpeechSynthesizer? = null
            try {
                config = EmbeddedSpeechConfig.fromPath(modelDirectory.absolutePath)
                config.setSpeechSynthesisVoice(descriptor.param, license)
                config.setSpeechSynthesisOutputFormat(
                    SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm,
                )
                synthesizer = SpeechSynthesizer(config, null as AudioConfig?)
                synthesizerRef.set(synthesizer)
                if (cancelled.get()) return@execute

                val result = synthesizer.SpeakSsml(buildSsml(descriptor, request))
                try {
                    if (cancelled.get()) return@execute
                    if (result.reason != ResultReason.SynthesizingAudioCompleted) {
                        val details = runCatching {
                            SpeechSynthesisCancellationDetails.fromResult(result)
                        }.getOrNull()
                        val message = details?.errorDetails
                            ?.takeIf { it.isNotBlank() }
                            ?: details?.reason?.toString()
                            ?: "嵌入式语音合成失败"
                        details?.close()
                        listener.onError(SynthesisError.SYNTHESIS_FAILED, message)
                        return@execute
                    }

                    val pcm = applyGain(result.audioData, request.volume)
                    if (pcm.isEmpty()) {
                        listener.onError(SynthesisError.SYNTHESIS_FAILED, "嵌入式语音没有返回音频")
                        return@execute
                    }
                    listener.onStart(24_000, AudioFormat.ENCODING_PCM_16BIT, 1)
                    var offset = 0
                    while (offset < pcm.size) {
                        if (cancelled.get() || closed.get()) return@execute
                        val length = minOf(CHUNK_BYTES, pcm.size - offset)
                        listener.onAudio(pcm, offset, length)
                        offset += length
                    }
                    if (!cancelled.get()) listener.onComplete()
                } finally {
                    result.close()
                }
            } catch (error: Throwable) {
                if (!cancelled.get()) {
                    Log.w(TAG, "embedded speech failed", error)
                    listener.onError(
                        SynthesisError.SYNTHESIS_FAILED,
                        error.message ?: "离线语音合成失败",
                    )
                }
            } finally {
                synthesizerRef.compareAndSet(synthesizer, null)
                runCatching { synthesizer?.close() }
                runCatching { config?.close() }
            }
        }
        return handle
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }

    private fun buildSsml(descriptor: VoiceDescriptor, request: SynthesisRequest): String {
        val rate = signedPercent(((request.rate.coerceIn(0.25f, 4f) - 1f) * 100).toInt())
        val pitch = signedPercent(((request.pitch.coerceIn(0.25f, 2f) - 1f) * 50).toInt())
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='${escapeXml(descriptor.localeTag)}'>" +
            "<voice name='${escapeXml(descriptor.param)}'>" +
            "<prosody rate='$rate' pitch='$pitch'>${escapeXml(request.text)}</prosody>" +
            "</voice></speak>"
    }

    private fun signedPercent(value: Int): String =
        if (value >= 0) "+$value%" else "$value%"

    private fun applyGain(pcm: ByteArray, volume: Float): ByteArray {
        if (volume >= 1f) return pcm
        val gain = volume.coerceIn(0f, 1f)
        val output = pcm.copyOf()
        var index = 0
        while (index + 1 < output.size) {
            val sample = ((output[index].toInt() and 0xFF) or
                (output[index + 1].toInt() shl 8)).toShort().toInt()
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767).toShort()
            output[index] = (scaled.toInt() and 0xFF).toByte()
            output[index + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            index += 2
        }
        return output
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    private companion object {
        const val TAG = "MicrosoftEmbeddedEngine"
        const val CHUNK_BYTES = 8 * 1024

        init {
            // 微软 SDK 的原生库依赖 libc++_shared 的符号；不预加载时 dlopen 会报
            // "cannot locate symbol _ZNKSt6__ndk1..."。库不存在时静默跳过。
            runCatching { System.loadLibrary("c++_shared") }
        }
    }
}
