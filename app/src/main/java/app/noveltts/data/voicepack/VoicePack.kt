package app.noveltts.data.voicepack

import app.noveltts.data.stringOrDefault

import org.json.JSONArray
import org.json.JSONObject

enum class VoicePackFormat {
    LEGACY_JSON,
    ORIGINAL_YAML,
    ;

    companion object {
        fun fromStorage(value: String?): VoicePackFormat =
            entries.firstOrNull { it.name == value } ?: LEGACY_JSON
    }
}

data class PackEngine(
    val code: String,
    val name: String,
    val type: String = "inner",
    val note: String = "",
    val supported: Boolean = code == "edgetts" || code == "microsoft",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("name", name)
        put("type", type)
        put("note", note)
        put("supported", supported)
    }

    companion object {
        fun fromJson(json: JSONObject): PackEngine? {
            val code = json.stringOrDefault("code").trim()
            if (code.isEmpty()) return null
            return PackEngine(
                code = code,
                name = json.stringOrDefault("name", code).ifBlank { code },
                type = json.stringOrDefault("type", "inner"),
                note = json.stringOrDefault("note"),
                supported = json.optBoolean("supported", code == "edgetts" || code == "microsoft"),
            )
        }
    }
}

/**
 * 语音包清单（pack.json）与在线目录（catalog.json）共用的数据模型。
 *
 * 语音包本身只是"音色声明"：包内每个音色可以绑定到一个系统 TTS 引擎与引擎内的语音名，
 * 应用后即切换引擎并选中该语音。后续本地模型包会在此模型上扩展 model 字段。
 */
data class PackVoice(
    val id: String,
    val name: String,
    val locale: String = "zh-CN",
    val engine: String? = null,
    val voiceName: String? = null,
    val requiresNetwork: Boolean = false,
    val engineCode: String? = null,
    val code: String = id,
    val param: String = voiceName.orEmpty(),
    val gender: Int = -1,
    val isOnline: Boolean = requiresNetwork,
    val sampleRate: Int = 24_000,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val description: String = "",
    val note: String = "",
    val avatar: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("locale", locale)
        engine?.let { put("engine", it) }
        voiceName?.let { put("voiceName", it) }
        put("requiresNetwork", requiresNetwork)
        engineCode?.let { put("engineCode", it) }
        put("code", code)
        put("param", param)
        put("gender", gender)
        put("isOnline", isOnline)
        put("sampleRate", sampleRate)
        put("speed", speed.toDouble())
        put("pitch", pitch.toDouble())
        put("volume", volume.toDouble())
        put("description", description)
        put("note", note)
        put("avatar", avatar)
    }

    companion object {
        fun fromJson(json: JSONObject): PackVoice = PackVoice(
            id = json.stringOrDefault("id").ifBlank { json.stringOrDefault("name") },
            name = json.stringOrDefault("name", json.stringOrDefault("id")),
            locale = json.stringOrDefault("locale", "zh-CN"),
            engine = json.stringOrDefault("engine").takeIf { it.isNotBlank() },
            voiceName = json.stringOrDefault("voiceName").takeIf { it.isNotBlank() },
            requiresNetwork = json.optBoolean("requiresNetwork", false),
            engineCode = json.stringOrDefault("engineCode").takeIf { it.isNotBlank() },
            code = json.stringOrDefault("code").ifBlank { json.stringOrDefault("id") },
            param = json.stringOrDefault("param", json.stringOrDefault("voiceName")),
            gender = json.optInt("gender", -1),
            isOnline = json.optBoolean("isOnline", json.optBoolean("requiresNetwork", false)),
            sampleRate = json.optInt("sampleRate", 24_000),
            speed = json.optDouble("speed", 1.0).toFloat(),
            pitch = json.optDouble("pitch", 1.0).toFloat(),
            volume = json.optDouble("volume", 1.0).toFloat(),
            description = json.stringOrDefault("description"),
            note = json.stringOrDefault("note"),
            avatar = json.stringOrDefault("avatar"),
        )
    }
}

data class VoicePack(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val voices: List<PackVoice> = emptyList(),
    val installedAt: Long = 0L,
    val sourceUrl: String? = null,
    val format: VoicePackFormat = VoicePackFormat.LEGACY_JSON,
    val engines: List<PackEngine> = emptyList(),
    val contentRevision: String = "",
) {
    val isOriginal: Boolean get() = format == VoicePackFormat.ORIGINAL_YAML

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("id", id)
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
        put("installedAt", installedAt)
        sourceUrl?.let { put("sourceUrl", it) }
        put("format", format.name)
        put("engines", JSONArray().apply { engines.forEach { put(it.toJson()) } })
        put("voices", JSONArray().apply { voices.forEach { put(it.toJson()) } })
    }

    companion object {
        const val SCHEMA = 1
        private val ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        /** 解析 pack.json；id 非法或缺失时抛 [IllegalArgumentException]。 */
        fun fromJson(json: JSONObject): VoicePack {
            val id = json.stringOrDefault("id").trim()
            require(isValidId(id)) { "pack.json 中的 id 非法：\"$id\"（仅允许字母、数字、. _ -）" }
            val voicesJson = json.optJSONArray("voices") ?: JSONArray()
            val voices = buildList {
                for (index in 0 until voicesJson.length()) {
                    val item = voicesJson.optJSONObject(index) ?: continue
                    add(PackVoice.fromJson(item))
                }
            }
            return VoicePack(
                id = id,
                name = json.stringOrDefault("name", id).ifBlank { id },
                version = json.stringOrDefault("version", "1.0.0").ifBlank { "1.0.0" },
                author = json.stringOrDefault("author"),
                description = json.stringOrDefault("description"),
                voices = voices,
                installedAt = json.optLong("installedAt", 0L),
                sourceUrl = json.stringOrDefault("sourceUrl").takeIf { it.isNotBlank() },
                format = VoicePackFormat.fromStorage(json.stringOrDefault("format")),
                engines = buildList {
                    val enginesJson = json.optJSONArray("engines") ?: JSONArray()
                    for (index in 0 until enginesJson.length()) {
                        enginesJson.optJSONObject(index)?.let { item ->
                            PackEngine.fromJson(item)?.let(::add)
                        }
                    }
                },
            )
        }
    }
}

/** 在线目录条目：描述一个可下载的语音包。 */
data class CatalogEntry(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val url: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
    val voiceCount: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject): CatalogEntry? {
            val id = json.stringOrDefault("id").trim()
            val url = json.stringOrDefault("url").trim()
            if (!VoicePack.isValidId(id) || url.isEmpty()) return null
            return CatalogEntry(
                id = id,
                name = json.stringOrDefault("name", id).ifBlank { id },
                version = json.stringOrDefault("version", "1.0.0").ifBlank { "1.0.0" },
                author = json.stringOrDefault("author"),
                description = json.stringOrDefault("description"),
                url = url,
                sha256 = json.stringOrDefault("sha256").takeIf { it.isNotBlank() },
                sizeBytes = json.optLong("sizeBytes", 0L),
                voiceCount = json.optInt("voiceCount", 0),
            )
        }
    }
}
