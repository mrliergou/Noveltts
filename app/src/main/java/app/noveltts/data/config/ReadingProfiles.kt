package app.noveltts.data.config

import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.domain.BuiltInRoleSettings
import app.noveltts.domain.RolePlaybackSettings
import app.noveltts.domain.RuleGroup
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Profiles reference existing groups/voices; they do not duplicate any rule. */
data class ReadingProfile(
    val id: String = UUID.randomUUID().toString(), val name: String,
    val narratorVoice: String, val dialogueVoice: String, val systemVoice: String,
    val roleGroups: List<String>, val replacementGroups: List<String>,
    val speed: Int = 50, val pitch: Int = 25, val volume: Int = 50,
    val punctuationFilter: Boolean = true, val silenceMs: Int = 80, val maxSegmentChars: Int = 500,
    val voiceHints: Map<String, String> = emptyMap(), val groupHints: Map<String, String> = emptyMap(),
    val narratorRate: Float = 1f, val narratorPitch: Float = 1f, val narratorVolume: Float = 1f,
    val dialogueRate: Float = 1f, val dialoguePitch: Float = 1f, val dialogueVolume: Float = 1f,
) {
    val builtInRoles: BuiltInRoleSettings get() = BuiltInRoleSettings(
        RolePlaybackSettings(narratorVoice, narratorRate, narratorPitch, narratorVolume).normalized(),
        RolePlaybackSettings(dialogueVoice, dialogueRate, dialoguePitch, dialogueVolume).normalized())
    fun remapVoices(mapping: Map<String, String>): ReadingProfile = copy(
        narratorVoice = mapping[narratorVoice] ?: narratorVoice, dialogueVoice = mapping[dialogueVoice] ?: dialogueVoice,
        systemVoice = mapping[systemVoice] ?: systemVoice)
}
data class ProfileSwitchPreview(val profile: ReadingProfile, val missingVoices: List<String>,
    val missingRoleGroups: List<String>, val missingReplacementGroups: List<String>)

object ReadingProfiles {
    const val KEY = "readingProfiles"
    fun preview(profile: ReadingProfile, voices: List<VoiceDescriptor>, roles: List<RuleGroup>, replacements: List<RuleGroup>): ProfileSwitchPreview {
        val usable = voices.filter { it.usable }.map { it.systemName }.toSet()
        return ProfileSwitchPreview(profile,
            listOf(profile.narratorVoice, profile.dialogueVoice, profile.systemVoice).filter { it.isNotBlank() && it !in usable }.distinct(),
            profile.roleGroups.filter { id -> roles.none { it.id == id } },
            profile.replacementGroups.filter { id -> replacements.none { it.id == id } })
    }
    fun encode(profiles: List<ReadingProfile>): String = toArray(profiles).toString()
    fun decode(text: String?): List<ReadingProfile> = if (text.isNullOrBlank()) emptyList() else fromArray(JSONArray(text))
    fun toArray(profiles: List<ReadingProfile>): JSONArray {
        require(profiles.size <= 32) { "最多保存 32 个阅读方案" }
        return JSONArray().apply { profiles.forEach { profile ->
            put(JSONObject().put("id", profile.id).put("name", profile.name).put("narratorVoice", profile.narratorVoice)
                .put("dialogueVoice", profile.dialogueVoice).put("systemVoice", profile.systemVoice)
                .put("roleGroups", JSONArray(profile.roleGroups)).put("replacementGroups", JSONArray(profile.replacementGroups))
                .put("speed", profile.speed).put("pitch", profile.pitch).put("volume", profile.volume)
                .put("punctuationFilter", profile.punctuationFilter).put("silenceMs", profile.silenceMs)
                .put("maxSegmentChars", profile.maxSegmentChars).put("voiceHints", JSONObject(profile.voiceHints))
                .put("groupHints", JSONObject(profile.groupHints)).also { json ->
                    val roles = profile.builtInRoles
                    json.put("narratorRate", roles.narrator.rate.toDouble()).put("narratorPitch", roles.narrator.pitch.toDouble())
                        .put("narratorVolume", roles.narrator.volume.toDouble()).put("dialogueRate", roles.dialogue.rate.toDouble())
                        .put("dialoguePitch", roles.dialogue.pitch.toDouble()).put("dialogueVolume", roles.dialogue.volume.toDouble())
                })
        } }
    }
    fun fromArray(array: JSONArray): List<ReadingProfile> {
        require(array.length() <= 32) { "阅读方案超过 32 个上限" }
        val seen = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id").take(100)
            val name = item.getString("name").trim().take(60)
            require(id.isNotBlank() && seen.add(id) && name.isNotBlank()) { "阅读方案标识或名称无效" }
            ReadingProfile(id, name, item.optString("narratorVoice"), item.optString("dialogueVoice"), item.optString("systemVoice"),
                strings(item.optJSONArray("roleGroups")), strings(item.optJSONArray("replacementGroups")),
                item.optInt("speed", 50).coerceIn(0, 100), item.optInt("pitch", 25).coerceIn(0, 100), item.optInt("volume", 50).coerceIn(0, 100),
                item.optBoolean("punctuationFilter", true), item.optInt("silenceMs", 80).coerceIn(0, 2000),
                item.optInt("maxSegmentChars", 500).coerceIn(100, 1000), hints(item.optJSONObject("voiceHints")), hints(item.optJSONObject("groupHints")),
                multiplier(item, "narratorRate", RolePlaybackSettings.RATE_RANGE), multiplier(item, "narratorPitch", RolePlaybackSettings.RATE_RANGE),
                multiplier(item, "narratorVolume", RolePlaybackSettings.VOLUME_RANGE), multiplier(item, "dialogueRate", RolePlaybackSettings.RATE_RANGE),
                multiplier(item, "dialoguePitch", RolePlaybackSettings.RATE_RANGE), multiplier(item, "dialogueVolume", RolePlaybackSettings.VOLUME_RANGE))
        }
    }
    private fun multiplier(item: JSONObject, key: String, range: ClosedFloatingPointRange<Float>): Float =
        RolePlaybackSettings.multiplier(item.optDouble(key, 1.0).toFloat(), range)
    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        require(array.length() <= 500) { "方案分组引用过多" }
        return (0 until array.length()).map { array.getString(it).take(100) }.distinct()
    }
    private fun hints(json: JSONObject?): Map<String, String> = json?.keys()?.asSequence()?.take(1000)
        ?.associateWith { json.getString(it).take(160) } ?: emptyMap()
}
