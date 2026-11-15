package app.noveltts.domain

import java.util.UUID

data class RuleGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val order: Int = 100,
) {
    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = RuleGroup(id = DEFAULT_ID, name = "默认")
    }
}

/** 规则集合：分组 + 条目。条目的 [RoleRule.groupId] / [ReplacementRule.groupId] 总能在 [groups] 中找到。 */
data class RuleSet<T>(
    val groups: List<RuleGroup> = listOf(RuleGroup.DEFAULT),
    val items: List<T> = emptyList(),
)

enum class RoleMatchMode {
    /** 文本任意位置命中正则（v1 行为）。 */
    REGEX_CONTAINS,
    REGEX_PREFIX,
    REGEX_SUFFIX,

    /** 正则需匹配整段文本。 */
    EXACT,
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
) {
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
)

data class PipelineIssue(
    val field: String,
    val message: String,
    val ruleId: String? = null,
)

data class PipelineSegment(
    val text: String,
    val isDialogue: Boolean,
    val voiceId: String?,
    val roleName: String? = null,
)

data class TextPipelineResult(
    val segments: List<PipelineSegment>,
    val transformedText: String,
    val issues: List<PipelineIssue> = emptyList(),
)
