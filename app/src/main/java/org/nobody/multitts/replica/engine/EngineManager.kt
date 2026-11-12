package org.nobody.multitts.replica.engine

import android.content.Context
import org.nobody.multitts.replica.data.settings.SettingsStore
import org.nobody.multitts.replica.data.voicepack.VoiceDescriptor
import org.nobody.multitts.replica.engine.api.SynthesisError
import org.nobody.multitts.replica.engine.api.SynthesisHandle
import org.nobody.multitts.replica.engine.api.SynthesisListener
import org.nobody.multitts.replica.engine.api.SynthesisRequest
import org.nobody.multitts.replica.engine.edge.EdgeTtsEngine
import org.nobody.multitts.replica.engine.microsoft.MicrosoftEmbeddedEngine

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
