package app.noveltts.engine

import android.content.Context
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest
import app.noveltts.engine.edge.EdgeTtsEngine
import app.noveltts.engine.microsoft.MicrosoftEmbeddedEngine

/**
 * Edge 在线引擎的注册表适配器：纯转发，不改引擎内部逻辑。
 *
 * [isAvailable] 恒为 true：网络是否可用只有真正发起合成才知道，交给引擎返回
 * NETWORK_FAILED，注册表不需要提前拒绝。
 */
internal class EdgeTtsEngineAdapter(
    context: Context,
    settings: SettingsStore,
) : EngineAdapter {
    override val engineCode: String = CODE

    private val engine = EdgeTtsEngine(context, settings)

    override fun isAvailable(): Boolean = true

    override fun synthesize(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle = engine.speak(descriptor, request, listener)

    override fun shutdown() = engine.shutdown()

    companion object {
        const val CODE = "edgetts"
    }
}

/**
 * 微软嵌入式引擎的注册表适配器：纯转发，不改引擎内部逻辑。
 *
 * [isAvailable] 恒为 true，许可证检查仍由引擎自身完成（LICENSE_REQUIRED）。如果
 * 这里用 `settings.hasLicense()` 判可用性，缺许可证时会退化成 NOT_READY，丢掉
 * 更精确的错误码和提示文案。
 */
internal class MicrosoftEmbeddedEngineAdapter(
    settings: SettingsStore,
) : EngineAdapter {
    override val engineCode: String = CODE

    private val engine = MicrosoftEmbeddedEngine(settings)

    override fun isAvailable(): Boolean = true

    override fun synthesize(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle = engine.speak(descriptor, request, listener)

    override fun shutdown() = engine.shutdown()

    companion object {
        const val CODE = "microsoft"
    }
}
