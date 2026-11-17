package app.noveltts.data.config

import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.domain.PipelineSegment
import app.noveltts.engine.PlannedSegment
import org.json.JSONArray
import org.json.JSONObject

/** Persist the resolved text and identities so a later resume does not rerun edited rules. */
object ReadingSnapshot {
    fun encode(segments: List<PlannedSegment>): JSONArray = JSONArray().apply {
        segments.forEach { planned ->
            val segment = planned.segment
            put(JSONObject().put("id", segment.id).put("text", segment.text)
                .put("isDialogue", segment.isDialogue).put("voiceId", planned.voice.systemName)
                .put("roleName", segment.roleName).put("roleId", segment.roleId)
                .put("requestedVoiceId", segment.requestedVoiceId).put("fallbackReason", segment.fallbackReason)
                .put("speechRate", segment.speechRate).put("speechPitch", segment.speechPitch)
                .put("speechVolume", segment.speechVolume))
        }
    }

    fun decode(array: JSONArray, voices: List<VoiceDescriptor>, narratorId: String, dialogueId: String): List<PlannedSegment> {
        require(array.length() in 1..20_000) { "朗读快照段数无效" }
        val usable = voices.filter { it.usable }
        val byId = usable.associateBy { it.systemName }
        val narrator = byId[narratorId] ?: usable.firstOrNull { it.localeTag.startsWith("zh") } ?: usable.firstOrNull()
        val dialogue = byId[dialogueId] ?: narrator
        val seen = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val text = item.getString("text")
            require(id.isNotBlank() && seen.add(id) && text.isNotBlank()) { "朗读快照内容无效" }
            val isDialogue = item.getBoolean("isDialogue")
            val savedVoice = item.getString("voiceId")
            val voice = byId[savedVoice] ?: (if (isDialogue) dialogue else narrator) ?: error("快照没有可用音色")
            val fallback = if (voice.systemName == savedVoice) optional(item, "fallbackReason")
                else "快照音色不可用，本段使用 ${voice.displayName}"
            PlannedSegment(PipelineSegment(text, isDialogue, voice.systemName, optional(item, "roleName"), id,
                optional(item, "roleId"), optional(item, "requestedVoiceId") ?: savedVoice, fallback,
                finite(item, "speechRate", 0.25f, 4f), finite(item, "speechPitch", 0.25f, 4f),
                finite(item, "speechVolume", 0f, 1f)), voice)
        }
    }

    private fun optional(item: JSONObject, key: String): String? =
        if (item.isNull(key)) null else item.optString(key).takeIf { it.isNotBlank() }
    private fun finite(item: JSONObject, key: String, min: Float, max: Float): Float =
        item.optDouble(key, 1.0).toFloat().also { require(it.isFinite()) { "快照朗读参数无效" } }.coerceIn(min, max)
}
