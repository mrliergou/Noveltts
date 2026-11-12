package org.nobody.multitts.replica.data.voicepack

import java.io.File
import java.security.MessageDigest

/**
 * 解析原版 MultiTTS 语音包。
 *
 * 原版包在 zip 根目录提供 config.yaml（引擎代码 -> 音色列表）和 engines.yaml
 * （引擎元数据）。YAML 的读取逻辑在 [PackYaml]，这里只负责补包 ID、时间戳并映射成
 * [VoicePack] 数据模型。
 */
object OriginalPackParser {
    const val CONFIG_NAME = "config.yaml"
    const val ENGINES_NAME = "engines.yaml"

    fun isOriginalPack(directory: File): Boolean =
        File(directory, CONFIG_NAME).isFile && File(directory, ENGINES_NAME).isFile

    fun packId(directory: File): String = packId(
        File(directory, CONFIG_NAME).readBytes(),
        File(directory, ENGINES_NAME).readBytes(),
    )

    /** 包 ID 只由两个 YAML 的字节决定，同一包重复导入得到同一 ID。 */
    fun packId(configBytes: ByteArray, enginesBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(configBytes)
        digest.update(0.toByte())
        digest.update(enginesBytes)
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return "vp-" + hash.take(16)
    }

    fun parse(
        directory: File,
        sourceUrl: String? = null,
        installedAt: Long = System.currentTimeMillis(),
    ): VoicePack {
        val configFile = File(directory, CONFIG_NAME)
        val enginesFile = File(directory, ENGINES_NAME)
        require(configFile.isFile) { "缺少 $CONFIG_NAME" }
        require(enginesFile.isFile) { "缺少 $ENGINES_NAME" }

        val parsed = PackYaml.parse(configFile.readText(), enginesFile.readText())

        val engines = parsed.engines.map { engine ->
            PackEngine(
                code = engine.code,
                name = engine.name,
                type = engine.type,
                note = engine.note,
                supported = engine.supported,
            )
        }

        val voices = parsed.voices.map { voice ->
            PackVoice(
                id = "${voice.engineCode}:${voice.code}",
                name = voice.name,
                locale = voice.locale,
                voiceName = voice.code,
                requiresNetwork = voice.isOnline,
                engineCode = voice.engineCode,
                code = voice.code,
                param = voice.param,
                gender = voice.gender,
                isOnline = voice.isOnline,
                sampleRate = voice.sampleRate,
                speed = voice.speed,
                pitch = voice.pitch,
                volume = voice.volume,
                description = voice.description,
                note = voice.note,
                avatar = voice.avatar,
            )
        }

        return VoicePack(
            id = packId(configFile.readBytes(), enginesFile.readBytes()),
            name = "${parsed.displayName} 语音包",
            version = "1.0.0",
            author = "MultiTTS",
            description = engines.joinToString("、") { it.name },
            voices = voices,
            installedAt = installedAt,
            sourceUrl = sourceUrl,
            format = VoicePackFormat.ORIGINAL_YAML,
            engines = engines,
        )
    }
}
