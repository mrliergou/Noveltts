package org.nobody.multitts.replica.data.voicepack

import android.content.Context
import java.io.File

/**
 * 把已安装语音包转换为稳定、可用于 Android Voice 的系统音色名。
 *
 * 系统音色名格式固定为 `multitts:<packId>:<engineCode>:<voiceCode>`，
 * 系统 TTS 回调时也通过同一格式反查，避免依赖可变显示名。
 */
class VoiceRegistry(
    context: Context,
    private val store: VoicePackStore,
) {
    private val appContext = context.applicationContext

    fun packs(): List<VoicePack> = store.installed()

    fun voices(licenseConfigured: Boolean): List<VoiceDescriptor> = buildList {
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
                val usable = modelReady && (!licenseRequired || licenseConfigured)
                val reason = when {
                    !modelReady -> "离线模型目录缺失"
                    licenseRequired && !licenseConfigured -> "需要 Microsoft Embedded Speech 许可证"
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
                        displayName = "${voice.name} · ${engine?.name ?: engineCode}",
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
                    ),
                )
            }
        }
        }.sortedWith(compareBy<VoiceDescriptor> { it.isOnline }.thenBy { it.displayName })

    fun find(voiceId: String?, licenseConfigured: Boolean): VoiceDescriptor? {
        if (voiceId.isNullOrBlank()) return null
        return voices(licenseConfigured).firstOrNull { it.systemName == voiceId }
    }

    companion object {
        fun systemName(packId: String, engineCode: String, voiceCode: String): String =
            "multitts:$packId:$engineCode:$voiceCode"
    }
}
