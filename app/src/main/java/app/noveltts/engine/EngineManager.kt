package app.noveltts.engine

import android.content.Context
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest
import app.noveltts.engine.edge.EdgeTtsEngine
import app.noveltts.engine.microsoft.MicrosoftEmbeddedEngine

/** 按语音包的引擎代码选择真实合成后端。 */
class EngineManager(
    context: Context,
    private val settings: SettingsStore,
) {
    private val edge = EdgeTtsEngine(context, settings)
    private val microsoft = MicrosoftEmbeddedEngine(settings)

    fun speak(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle {
        if (!descriptor.usable) {
            listener.onError(
                if (descriptor.licenseRequired && !settings.hasLicense()) {
                    SynthesisError.LICENSE_REQUIRED
                } else {
                    SynthesisError.NOT_READY
                },
                descriptor.unusableReason ?: "该音色当前不可用",
            )
            return NoopHandle
        }
        return when (descriptor.engineCode) {
            "edgetts" -> edge.speak(descriptor, request, listener)
            "microsoft" -> microsoft.speak(descriptor, request, listener)
            else -> {
                listener.onError(SynthesisError.BAD_REQUEST, "不支持的引擎：${descriptor.engineCode}")
                NoopHandle
            }
        }
    }

    fun shutdown() {
        edge.shutdown()
        microsoft.shutdown()
    }

    private object NoopHandle : SynthesisHandle {
        override fun cancel() = Unit
    }
}
