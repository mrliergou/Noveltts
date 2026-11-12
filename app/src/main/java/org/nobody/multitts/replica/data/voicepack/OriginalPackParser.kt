package org.nobody.multitts.replica.data.voicepack

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.security.MessageDigest

/**
 * 解析原版 MultiTTS 语音包。
 *
 * 原版包在 zip 根目录提供 config.yaml（引擎代码 -> 音色列表）和 engines.yaml
 * （引擎元数据）。YAML 中带有 org.nobody.multitts 的 Java 类型标签，这里先去掉标签，
 * 再按普通 map/list 读取，避免把原版 APK 的类带进本项目。
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

        val config = loadRoot(configFile.readText())
        val engineDocument = loadRoot(enginesFile.readText())
        val engineItems = engineDocument["engines"].asList().mapNotNull { it.asMap() }
        val declaredEngines = engineItems.associateBy { it.string("code") }

        val engines = config.keys.mapNotNull { rawCode ->
            val code = rawCode.trim()
            if (code.isEmpty()) return@mapNotNull null
            val declared = declaredEngines[code]
            PackEngine(
                code = code,
                name = declared?.string("name")?.ifBlank { code } ?: code,
                type = declared?.string("type")?.ifBlank { "inner" } ?: "inner",
                note = declared?.string("note").orEmpty(),
                supported = code == "edgetts" || code == "microsoft",
            )
        }

        val voices = buildList {
            config.forEach { (rawCode, rawVoices) ->
                val engineCode = rawCode.trim()
                if (engineCode.isEmpty()) return@forEach
                rawVoices.asList().forEachIndexed { index, rawVoice ->
                    val item = rawVoice.asMap() ?: return@forEachIndexed
                    val code = item.string("code").ifBlank { item.string("param") }
                        .ifBlank { "voice-$index" }
                    val locale = item.string("locale").ifBlank { inferLocale(code) }
                    val online = item.intOrNull("type") == 1 || engineCode == "edgetts"
                    add(
                        PackVoice(
                            id = "$engineCode:$code",
                            name = item.string("name").ifBlank { code },
                            locale = locale,
                            voiceName = code,
                            requiresNetwork = online,
                            engineCode = engineCode,
                            code = code,
                            param = item.string("param").ifBlank { code },
                            gender = item.intOrNull("gender") ?: -1,
                            isOnline = online,
                            sampleRate = item.intOrNull("sampleRate") ?: 24_000,
                            speed = item.floatOrNull("speed") ?: 1f,
                            pitch = item.floatOrNull("pitch") ?: 1f,
                            volume = item.floatOrNull("volume") ?: 1f,
                            description = item.string("desc"),
                            note = item.string("note"),
                            avatar = item.string("avatar"),
                        ),
                    )
                }
            }
        }

        val name = engines.map { it.name }.distinct().joinToString(" + ")
            .ifBlank { "原版语音包" }
        return VoicePack(
            id = packId(directory),
            name = "$name 语音包",
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

    private fun loadRoot(text: String): Map<String, Any?> {
        val cleaned = text.replace(Regex("!![A-Za-z0-9_.$]+"), "")
        val loaded = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(cleaned)
        return loaded.asMap().orEmpty()
    }

    private fun Any?.asMap(): Map<String, Any?>? {
        val source = this as? Map<*, *> ?: return null
        return buildMap {
            source.forEach { (key, value) ->
                if (key != null) put(key.toString(), value)
            }
        }
    }

    private fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

    private fun Map<String, Any?>.string(key: String): String =
        this[key]?.toString()?.takeUnless { it == "null" }?.trim().orEmpty()

    private fun Map<String, Any?>.intOrNull(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun Map<String, Any?>.floatOrNull(key: String): Float? = when (val value = this[key]) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }

    private fun inferLocale(code: String): String {
        val match = Regex("^[a-zA-Z]{2}-[a-zA-Z]{2}").find(code) ?: return "zh-CN"
        return match.value
    }
}
