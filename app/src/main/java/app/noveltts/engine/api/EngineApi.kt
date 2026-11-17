package app.noveltts.engine.api

/**
 * 引擎对外暴露的单个音色。
 *
 * [id] 在单个引擎内部唯一且稳定；跨引擎比较没有意义。
 */
data class VoiceProfile(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val quality: Int = DEFAULT_QUALITY,
    val latency: Int = DEFAULT_LATENCY,
    val requiresNetwork: Boolean = false,
) {
    companion object {
        // 与 android.speech.tts.Voice.QUALITY_NORMAL / LATENCY_NORMAL 对齐，避免 api 层依赖平台常量
        const val DEFAULT_QUALITY = 300
        const val DEFAULT_LATENCY = 300
    }
}

/**
 * 归一化后的合成请求。
 *
 * [rate] / [pitch] 取值 0.5f..2.0f（1.0f 为原速），[volume] 取值 0f..1f。
 * 调用方负责把 UI 的 0..100 滑块和系统的百分比参数映射到这段区间。
 */
data class SynthesisRequest(
    val text: String,
    val voiceId: String? = null,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val options: EngineRequestOptions? = null,
    val requestId: String? = null,
    val segmentId: String? = null,
)

data class EngineRequestOptions(val edgeEndpoint: String, val timeoutSec: Int, val localEnginePackage: String)

/** 合成结果回调。除 [onStart] 之外，所有回调都在同一个后台线程上串行触发。 */
interface SynthesisListener {
    /** 在第一个音频块之前恰好调用一次，声明 PCM 格式。 */
    fun onStart(sampleRateHz: Int, audioFormat: Int, channelCount: Int)

    /** 输出一块 PCM。回调返回后，[pcm] 这段缓冲区可能被复用，接收方必须自行拷贝。 */
    fun onAudio(pcm: ByteArray, offset: Int, length: Int)

    fun onComplete()

    fun onError(code: Int, message: String?)
}

/** 可用于取消一次进行中的合成。 */
interface SynthesisHandle {
    fun cancel()
}

/**
 * 一个音源后端。
 *
 * 实现必须保证：同一实例可以并发创建多次合成，但音频回调互不串流；
 * [shutdown] 之后不得再产生任何回调。
 */
interface SynthesisEngine {
    val id: String
    val displayName: String

    /** 当前可用音色；后端尚未就绪时返回空列表，不得抛异常。 */
    fun listVoices(): List<VoiceProfile>

    fun defaultVoiceId(): String?

    fun synthesize(request: SynthesisRequest, listener: SynthesisListener): SynthesisHandle

    fun shutdown()
}

/** 引擎层错误码，与 [android.speech.tts.TextToSpeech] 的错误码语义保持接近。 */
object SynthesisError {
    const val INIT_FAILED = -1
    const val NOT_READY = -2
    const val BAD_REQUEST = -3
    const val SYNTHESIS_FAILED = -4
    const val UNSUPPORTED_AUDIO = -5
    const val CANCELLED = -6
    const val LICENSE_REQUIRED = -7
    const val NETWORK_FAILED = -8
}
