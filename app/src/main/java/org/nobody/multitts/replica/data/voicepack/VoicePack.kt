package org.nobody.multitts.replica.data.voicepack

import org.json.JSONArray
import org.json.JSONObject

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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("locale", locale)
        engine?.let { put("engine", it) }
        voiceName?.let { put("voiceName", it) }
        put("requiresNetwork", requiresNetwork)
    }

    companion object {
        fun fromJson(json: JSONObject): PackVoice = PackVoice(
            id = json.optString("id").ifBlank { json.optString("name") },
            name = json.optString("name", json.optString("id")),
            locale = json.optString("locale", "zh-CN"),
            engine = json.optString("engine").takeIf { it.isNotBlank() },
            voiceName = json.optString("voiceName").takeIf { it.isNotBlank() },
            requiresNetwork = json.optBoolean("requiresNetwork", false),
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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("id", id)
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
        put("installedAt", installedAt)
        sourceUrl?.let { put("sourceUrl", it) }
        put("voices", JSONArray().apply { voices.forEach { put(it.toJson()) } })
    }

    companion object {
        const val SCHEMA = 1
        private val ID_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        /** 解析 pack.json；id 非法或缺失时抛 [IllegalArgumentException]。 */
        fun fromJson(json: JSONObject): VoicePack {
            val id = json.optString("id").trim()
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
                name = json.optString("name", id).ifBlank { id },
                version = json.optString("version", "1.0.0").ifBlank { "1.0.0" },
                author = json.optString("author"),
                description = json.optString("description"),
                voices = voices,
                installedAt = json.optLong("installedAt", 0L),
                sourceUrl = json.optString("sourceUrl").takeIf { it.isNotBlank() },
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
            val id = json.optString("id").trim()
            val url = json.optString("url").trim()
            if (!VoicePack.isValidId(id) || url.isEmpty()) return null
            return CatalogEntry(
                id = id,
                name = json.optString("name", id).ifBlank { id },
                version = json.optString("version", "1.0.0").ifBlank { "1.0.0" },
                author = json.optString("author"),
                description = json.optString("description"),
                url = url,
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
                sizeBytes = json.optLong("sizeBytes", 0L),
                voiceCount = json.optInt("voiceCount", 0),
            )
        }
    }
}
