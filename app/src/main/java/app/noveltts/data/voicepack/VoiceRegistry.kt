package app.noveltts.data.voicepack

import android.content.Context
import java.io.File

/**
 * 把已安装语音包转换为稳定、可用于 Android Voice 的系统音色名。
 *
 * 系统音色名格式固定为 `multitts:<packId>:<engineCode>:<voiceCode>`，
 * 系统 TTS 回调时也通过同一格式反查，避免依赖可变显示名。
 *
 * 注意：应用已改名 NovelTTS，但这个 `multitts:` 前缀**故意保留**——它会被第三方阅读器、
 * 系统设置以及本应用的已保存设置同时记住，换掉等于让所有已选音色失效。
 * 计划在功能稳定后统一迁移，届时需要同步改 [systemName]、[find] 的旧 ID 兼容逻辑、
 * `SettingsStore`/`RulesRepository` 里保存的 voiceId，以及在 UI 上提示用户重选音色。
 */
class VoiceRegistry(
    context: Context,
    private val store: VoicePackStore,
) {
    private val appContext = context.applicationContext

    fun packs(): List<VoicePack> = store.installed()

    fun voices(licenseConfigured: Boolean, localEnginePackage: String = ""): List<VoiceDescriptor> = buildList {
        packs().forEach { pack ->
            if (!pack.isOriginal) return@forEach
            pack.voices.forEach { voice ->
                val engineCode = voice.engineCode ?: return@forEach
                val engine = pack.engines.firstOrNull { it.code == engineCode }
                if (engine?.supported != true) return@forEach
                val modelDir = when (engineCode) {
                    "microsoft" -> File(store.directoryOf(pack.id), "microsoft/${voice.code}")
                    else -> null
                }
                val licenseRequired = engineCode == "microsoft"
                val modelReady = !licenseRequired || modelDir?.isDirectory == true
                // android-<包名> 的桥接引擎只有设置里配了同一个包名时才真的能合成，
                // 否则会在合成时报 BAD_REQUEST；这里提前把原因显示给用户。
                val bridgePackage = if (engineCode.startsWith(LOCAL_ENGINE_PREFIX)) {
                    engineCode.removePrefix(LOCAL_ENGINE_PREFIX)
                } else {
                    null
                }
                val bridgeReady = bridgePackage == null ||
                    (localEnginePackage.isNotBlank() && bridgePackage == localEnginePackage)
                val usable = modelReady && (!licenseRequired || licenseConfigured) && bridgeReady
                val reason = when {
                    !modelReady -> "离线模型目录缺失"
                    licenseRequired && !licenseConfigured -> "需要 Microsoft Embedded Speech 许可证"
                    !bridgeReady -> "需要在设置里把本地引擎配置为 " + bridgePackage
                    else -> null
                }
                add(
                    VoiceDescriptor(
                        systemName = systemName(pack.id, engineCode, voice.code),
                        packId = pack.id,
                        packName = pack.name,
                        engineCode = engineCode,
                        voiceCode = voice.code,
                        param = voice.param,
                        displayName = "${voice.name} · ${engine.name}",
                        localeTag = voice.locale,
                        gender = voice.gender,
                        isOnline = voice.isOnline,
                        sampleRate = voice.sampleRate,
                        defaultSpeed = voice.speed,
                        defaultPitch = voice.pitch,
                        defaultVolume = voice.volume,
                        description = voice.description,
                        licenseRequired = licenseRequired,
                        usable = usable,
                        modelDirectory = modelDir,
                        unusableReason = reason,
                        contentRevision = pack.contentRevision,
                    ),
                )
            }
        }
        }.sortedWith(compareBy<VoiceDescriptor> { it.isOnline }.thenBy { it.displayName })

    fun find(
        voiceId: String?,
        licenseConfigured: Boolean,
        localEnginePackage: String = "",
    ): VoiceDescriptor? {
        if (voiceId.isNullOrBlank()) return null
        return voices(licenseConfigured, localEnginePackage).firstOrNull { it.systemName == voiceId }
    }

    companion object {
        /** 桥接给本地系统 TTS 引擎的引擎代码前缀，与 EngineManager 保持一致。 */
        private const val LOCAL_ENGINE_PREFIX = "android-"

        fun systemName(packId: String, engineCode: String, voiceCode: String): String =
            "multitts:$packId:$engineCode:$voiceCode"
    }
}
