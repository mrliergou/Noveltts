package app.noveltts.engine

import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest

/**
 * 引擎后端在注册表里的统一入口。
 *
 * 放在 `engine` 包而不是 `engine.api` 的原因：本接口的入参是 [VoiceDescriptor]
 * （`data.voicepack` 层的数据模型），而 `engine.api` 刻意只依赖纯 JVM 类型
 * （VoiceProfile / SynthesisRequest / SynthesisError），不反向依赖 data 层。
 * 把本接口下沉到 api 包会造成 api → data 的依赖倒置，因此留在 engine 包。
 *
 * 实现约束与 [app.noveltts.engine.api.SynthesisEngine] 一致：同一实例可以并发
 * 创建多次合成，但音频回调互不串流；[shutdown] 之后不得再产生任何回调。
 */
interface EngineAdapter {
    /** 注册表键，对应 [VoiceDescriptor.engineCode]；同一个注册表内必须唯一。 */
    val engineCode: String

    /** 后端是否已就绪。未就绪时 [EngineManager] 直接回 NOT_READY，不进入合成。 */
    fun isAvailable(): Boolean

    fun synthesize(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle

    fun shutdown()
}
