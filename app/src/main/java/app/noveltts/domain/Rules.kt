package app.noveltts.domain

import java.util.UUID

data class RuleGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val order: Int = 100,
) : java.io.Serializable {
    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = RuleGroup(id = DEFAULT_ID, name = "默认")
    }
}

/** 规则集合：分组 + 条目。条目的 [RoleRule.groupId] / [ReplacementRule.groupId] 总能在 [groups] 中找到。 */
data class RuleSet<T>(
    val groups: List<RuleGroup> = listOf(RuleGroup.DEFAULT),
    val items: List<T> = emptyList(),
) : java.io.Serializable

enum class RoleMatchMode {
    /** 文本任意位置命中正则（v1 行为）。 */
    REGEX_CONTAINS,
    REGEX_PREFIX,
    REGEX_SUFFIX,

    /** 正则需匹配整段文本。 */
    EXACT,
    LITERAL_EXACT,
}

enum class RoleMatchScope {
    SEGMENT,
    PREVIOUS_NARRATION,
    NEXT_NARRATION,

    /** 按整句引语判定说话人，见 [SpeakerAttributor]；只作用于对话。 */
    AUTO,
}
enum class ReplacementScope { ALL, NARRATION, DIALOGUE, ROLE }

/** 一个朗读角色的音色与倍率；倍率叠加在全局参数与音色默认值之上。 */
data class RolePlaybackSettings(
    val voiceId: String = "",
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
) : java.io.Serializable {
    fun normalized(): RolePlaybackSettings = copy(
        rate = multiplier(rate, RATE_RANGE),
        pitch = multiplier(pitch, RATE_RANGE),
        volume = multiplier(volume, VOLUME_RANGE),
    )

    companion object {
        val RATE_RANGE = 0.25f..4f
        val VOLUME_RANGE = 0f..1f

        fun multiplier(value: Float, range: ClosedFloatingPointRange<Float>): Float =
            if (value.isFinite()) value.coerceIn(range) else 1f
    }
}

enum class BuiltInRole { NARRATOR, DIALOGUE }

/** 未命中任何角色规则时的兜底角色：旁白与对话。 */
data class BuiltInRoleSettings(
    val narrator: RolePlaybackSettings = RolePlaybackSettings(),
    val dialogue: RolePlaybackSettings = RolePlaybackSettings(),
) : java.io.Serializable {
    operator fun get(role: BuiltInRole): RolePlaybackSettings = when (role) {
        BuiltInRole.NARRATOR -> narrator
        BuiltInRole.DIALOGUE -> dialogue
    }

    fun with(role: BuiltInRole, settings: RolePlaybackSettings): BuiltInRoleSettings = when (role) {
        BuiltInRole.NARRATOR -> copy(narrator = settings)
        BuiltInRole.DIALOGUE -> copy(dialogue = settings)
    }

    fun withVoices(narratorVoiceId: String?, dialogueVoiceId: String?): BuiltInRoleSettings = copy(
        narrator = narrator.copy(voiceId = narratorVoiceId.orEmpty()),
        dialogue = dialogue.copy(voiceId = dialogueVoiceId.orEmpty()),
    )
}

enum class VoiceSelectionMode {
    /** 总是使用第一个音色（v1 行为）。 */
    FIXED,

    /** 同一次合成内，该规则每命中一次切换到下一个音色。 */
    ROUND_ROBIN,
}

data class RoleRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pattern: String,
    val voiceIds: List<String>,
    val priority: Int = 100,
    val onlyDialogue: Boolean = true,
    val enabled: Boolean = true,
    val groupId: String = RuleGroup.DEFAULT_ID,
    val matchMode: RoleMatchMode = RoleMatchMode.REGEX_CONTAINS,
    val voiceSelectionMode: VoiceSelectionMode = VoiceSelectionMode.FIXED,
    val sortIndex: Int = 0,
    val matchScope: RoleMatchScope = RoleMatchScope.SEGMENT,
    val contextChars: Int = 200,
    val speechRate: Float = 1f,
    val speechPitch: Float = 1f,
    val speechVolume: Float = 1f,
) : java.io.Serializable {
    val primaryVoiceId: String? get() = voiceIds.firstOrNull { it.isNotBlank() }
}

data class ReplacementRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val find: String,
    val replaceWith: String,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    /** v1 遗留的执行顺序；v2 起执行顺序由所在分组 order + [sortIndex] 决定。 */
    val order: Int = 100,
    val sortIndex: Int = 0,
    val groupId: String = RuleGroup.DEFAULT_ID,
    val scope: ReplacementScope = ReplacementScope.ALL,
    val targetRoleId: String = "",
) : java.io.Serializable

data class PipelineIssue(
    val field: String,
    val message: String,
    val ruleId: String? = null,
) : java.io.Serializable

data class PipelineSegment(
    val text: String,
    val isDialogue: Boolean,
    val voiceId: String?,
    val roleName: String? = null,
    val id: String = "",
    val roleId: String? = null,
    val requestedVoiceId: String? = voiceId,
    val fallbackReason: String? = null,
    val speechRate: Float = 1f,
    val speechPitch: Float = 1f,
    val speechVolume: Float = 1f,
    /** 这一段为什么由该角色朗读；旁白未命中规则时为空。 */
    val attribution: Attribution? = null,
) : java.io.Serializable

data class ReplacementTrace(val ruleId: String, val ruleName: String, val before: String, val after: String) : java.io.Serializable

data class TextPipelineResult(
    val segments: List<PipelineSegment>,
    val transformedText: String,
    val issues: List<PipelineIssue> = emptyList(),
    val replacements: List<ReplacementTrace> = emptyList(),
) : java.io.Serializable
