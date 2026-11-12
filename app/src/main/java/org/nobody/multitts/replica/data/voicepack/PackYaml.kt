package org.nobody.multitts.replica.data.voicepack

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * 原版 `config.yaml` + `engines.yaml` 的纯 Kotlin 解析层。
 *
 * 这里刻意不返回 [VoicePack]/[PackVoice]（它们带 `org.json` 依赖，在纯 JVM 单测里不可用），
 * 而是返回 [ParsedPack] 这组普通数据类，方便对真实包结构做单元测试。
 */
data class ParsedEngine(
    val code: String,
    val name: String,
    val type: String,
    val note: String,
) {
    val supported: Boolean get() = code == "edgetts" || code == "microsoft"
}

data class ParsedVoice(
    val engineCode: String,
    val code: String,
    val name: String,
    val locale: String,
    val param: String,
    val gender: Int,
    val sampleRate: Int,
    val speed: Float,
    val pitch: Float,
    val volume: Float,
    val description: String,
    val note: String,
    val avatar: String,
    val isOnline: Boolean,
)

data class ParsedPack(
    val engines: List<ParsedEngine>,
    val voices: List<ParsedVoice>,
) {
    val displayName: String
        get() = engines.map { it.name }.distinct().joinToString(" + ").ifBlank { "原版语音包" }
}

object PackYaml {

    fun parse(configText: String, enginesText: String): ParsedPack {
        val config = loadRoot(configText)
        val engineDocument = loadRoot(enginesText)
        val declared = engineDocument["engines"]
            .asList()
            .mapNotNull { it.asMap() }
            .associateBy { it.string("code") }

        val engines = config.keys.mapNotNull { rawCode ->
            val code = rawCode.trim()
            if (code.isEmpty()) return@mapNotNull null
            val meta = declared[code]
            ParsedEngine(
                code = code,
                name = meta?.string("name")?.ifBlank { code } ?: code,
                type = meta?.string("type")?.ifBlank { "inner" } ?: "inner",
                note = meta?.string("note").orEmpty(),
            )
        }

        val voices = buildList {
            config.forEach { (rawCode, rawVoices) ->
                val engineCode = rawCode.trim()
                if (engineCode.isEmpty()) return@forEach
                rawVoices.asList().forEachIndexed { index, rawVoice ->
                    val item = rawVoice.asMap() ?: return@forEachIndexed
                    val code = item.string("code")
                        .ifBlank { item.string("param") }
                        .ifBlank { "voice-$index" }
                    val online = item.intOrNull("type") == 1 || engineCode == "edgetts"
                    add(
                        ParsedVoice(
                            engineCode = engineCode,
                            code = code,
                            name = item.string("name").ifBlank { code },
                            locale = item.string("locale").ifBlank { inferLocale(code) },
                            param = item.string("param").ifBlank { code },
                            gender = item.intOrNull("gender") ?: -1,
                            sampleRate = item.intOrNull("sampleRate") ?: 24_000,
                            speed = item.floatOrNull("speed") ?: 1f,
                            pitch = item.floatOrNull("pitch") ?: 1f,
                            volume = item.floatOrNull("volume") ?: 1f,
                            description = item.string("desc"),
                            note = item.string("note"),
                            avatar = item.string("avatar"),
                            isOnline = online,
                        ),
                    )
                }
            }
        }
        return ParsedPack(engines = engines, voices = voices)
    }

    /**
     * 去掉原版的 Java 类型标签，例如
     * `!!org.nobody.multitts.tts.speaker.Speaker`，再用 [SafeConstructor] 读成普通 map/list。
     * 标签可能出现在根节点（engines.yaml）或每个列表项（config.yaml）上。
     */
    fun stripTags(text: String): String = text.replace(TAG_PATTERN, "")

    private val TAG_PATTERN = Regex("!![A-Za-z0-9_.$]+")

    private fun loadRoot(text: String): Map<String, Any?> {
        val loaded = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(stripTags(text))
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
