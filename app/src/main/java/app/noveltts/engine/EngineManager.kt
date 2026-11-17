package app.noveltts.engine

import android.content.Context
import app.noveltts.data.settings.SettingsStore
import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.android.AndroidTtsBridgeAdapter
import app.noveltts.engine.api.SynthesisError
import app.noveltts.engine.api.SynthesisHandle
import app.noveltts.engine.api.SynthesisListener
import app.noveltts.engine.api.SynthesisRequest
import app.noveltts.engine.api.EngineRequestOptions

/**
 * 按语音包的引擎代码（[VoiceDescriptor.engineCode]）把请求路由到真实合成后端。
 *
 * 内部是一张 `engineCode -> EngineAdapter` 的注册表。默认注册 Edge 在线引擎与
 * 微软嵌入式引擎；测试或后续功能可以通过 [adapters] 参数注入自定义注册表。
 */
class EngineManager(
    context: Context,
    private val settings: SettingsStore,
    adapters: List<EngineAdapter> = defaultEngineAdapters(context.applicationContext, settings),
) {
    private val appContext: Context = context.applicationContext

    /** 去重后的注册顺序，[shutdown] 按它逐个关闭。 */
    private val registered: List<EngineAdapter> = adapters.distinct()

    private val byCode: Map<String, EngineAdapter> = registered.associateBy { it.engineCode }

    /** 按设置项临时创建的本地引擎适配器：设置改了不用重启，下一次合成即生效。 */
    private val lazyAdapters = mutableMapOf<String, EngineAdapter>()

    /** [shutdown] 之后不再接受新请求，也不新建本地适配器。 */
    @Volatile
    private var closed = false

    /** 当前注册的引擎代码，按注册顺序；诊断与调试用。 */
    fun engineCodes(): List<String> = registered.map { it.engineCode }

    fun requestOptions(): EngineRequestOptions = settings.synthesisSnapshot().options

    fun cacheRevision(voice: VoiceDescriptor, options: EngineRequestOptions): String {
        val adapter = when (voice.engineCode) {
            "edgetts" -> "edge:${app.noveltts.engine.edge.EdgeGec.SEC_MS_GEC_VERSION}:frames2:${options.edgeEndpoint}"
            "microsoft" -> "embedded:1.37.0:pcm2"
            else -> {
                val installed = runCatching { appContext.packageManager.getPackageInfo(options.localEnginePackage, 0) }.getOrNull()
                @Suppress("DEPRECATION")
                "android:pcm2:${installed?.versionName}:${if (android.os.Build.VERSION.SDK_INT >= 28) installed?.longVersionCode else installed?.versionCode}"
            }
        }
        return "${voice.contentRevision}|$adapter"
    }

    fun speak(
        descriptor: VoiceDescriptor,
        request: SynthesisRequest,
        listener: SynthesisListener,
    ): SynthesisHandle {
        if (closed) {
            listener.onError(SynthesisError.NOT_READY, "引擎已关闭")
            return NoopHandle
        }
        if (!descriptor.usable) {
            DiagnosticsLog.warn(Category.ENGINE, "音色不可用：${descriptor.engineCode} · ${descriptor.displayName}", descriptor.unusableReason)
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
        val adapter = byCode[descriptor.engineCode] ?: localAdapter(descriptor.engineCode,
            request.options?.localEnginePackage ?: settings.localTtsEnginePackage)
        if (adapter == null) {
            DiagnosticsLog.error(Category.ENGINE, "引擎未注册：${descriptor.engineCode}")
            listener.onError(SynthesisError.BAD_REQUEST, "不支持的引擎：${descriptor.engineCode}")
            return NoopHandle
        }
        return try {
            // 取到适配器之后再过一次 closed：可能刚好在创建与调用之间被 shutdown。
            if (closed) {
                listener.onError(SynthesisError.NOT_READY, "引擎已关闭")
                NoopHandle
            } else if (!adapter.isAvailable()) {
                DiagnosticsLog.warn(Category.ENGINE, "引擎未就绪：${descriptor.engineCode} · ${descriptor.displayName}")
                listener.onError(SynthesisError.NOT_READY, "引擎未就绪：${descriptor.engineCode}")
                NoopHandle
            } else {
                adapter.synthesize(descriptor, request, listener)
            }
        } catch (error: Exception) {
            // 关闭竞态下适配器可能抛 RejectedExecutionException 之类的运行时异常，
            // 绝不能让异常穿透到合成线程把整次朗读打断。
            DiagnosticsLog.error(Category.ENGINE, "引擎调用异常：${descriptor.engineCode}", error.message?.take(200))
            listener.onError(SynthesisError.SYNTHESIS_FAILED, error.message ?: "引擎调用异常")
            NoopHandle
        }
    }

    fun shutdown() {
        val all = synchronized(lazyAdapters) {
            closed = true
            val lazy = lazyAdapters.values.toList()
            lazyAdapters.clear()
            registered + lazy
        }
        all.forEach { adapter ->
            runCatching { adapter.shutdown() }.onFailure { error ->
                DiagnosticsLog.warn(Category.ENGINE, "关闭引擎失败：${adapter.engineCode}", error.message?.take(200))
            }
        }
    }

    /**
     * 本地系统 TTS 桥接的按需注册：引擎代码形如 "android-<包名>"，包名必须与设置项
     * [SettingsStore.localTtsEnginePackage] 一致，且不能是本应用自己。
     */
    private fun localAdapter(engineCode: String, packageName: String): EngineAdapter? {
        if (!engineCode.startsWith(LOCAL_ENGINE_PREFIX)) return null
        if (packageName.isBlank() || packageName == appContext.packageName) return null
        if (engineCode != LOCAL_ENGINE_PREFIX + packageName) return null
        return synchronized(lazyAdapters) {
            if (closed) return@synchronized null
            // 设置里换了引擎包名时，旧适配器不再可用，先回收再建新的（旧 key 不会自动淘汰）。
            lazyAdapters.getOrPut(engineCode) { AndroidTtsBridgeAdapter(appContext, packageName) }
        }
    }

    private companion object {
        const val LOCAL_ENGINE_PREFIX = "android-"
    }

    private object NoopHandle : SynthesisHandle {
        override fun cancel() = Unit
    }
}

/**
 * 默认注册表。
 *
 * 本地 Android TTS 桥接（[AndroidTtsBridgeAdapter]）不在这里注册：它依赖设置项
 * [SettingsStore.localTtsEnginePackage]，由 EngineManager.localAdapter 在遇到
 * "android-<包名>" 时按需创建，改完设置立刻生效。
 */
private fun defaultEngineAdapters(
    context: Context,
    settings: SettingsStore,
): List<EngineAdapter> = buildList {
    add(EdgeTtsEngineAdapter(context, settings))
    add(MicrosoftEmbeddedEngineAdapter(settings))
}
