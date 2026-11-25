package app.noveltts.domain

import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.PlannedSegment

data class BuiltSynthesisPlan(
    val pipeline: TextPipelineResult,
    val segments: List<PlannedSegment>,
)

object SynthesisPlanBuilder {
    data class Snapshot(val text: String, val builtInRoles: BuiltInRoleSettings,
        val roles: RuleSet<RoleRule>, val replacements: RuleSet<ReplacementRule>, val punctuation: Boolean,
        val voices: List<VoiceDescriptor>, val maxChars: Int, val policy: UnattributedPolicy,
        val book: BookRoleProfile? = null) {
        fun build() = SynthesisPlanBuilder.build(text, builtInRoles, roles, replacements, punctuation, voices, maxChars, policy, book)
    }
    fun build(
        text: String,
        builtInRoles: BuiltInRoleSettings,
        roles: RuleSet<RoleRule>,
        replacements: RuleSet<ReplacementRule>,
        filterPurePunctuation: Boolean,
        voices: List<VoiceDescriptor>,
        maxSegmentChars: Int = TextPipeline.DEFAULT_MAX_SEGMENT_CHARS,
        unattributedPolicy: UnattributedPolicy = UnattributedPolicy.DEFAULT,
        book: BookRoleProfile? = null,
    ): BuiltSynthesisPlan {
        val usable = voices.filter { it.usable }
        val byId = usable.associateBy { it.systemName }
        val fallback = usable.firstOrNull { it.localeTag.startsWith("zh", ignoreCase = true) }
            ?: usable.firstOrNull()
        val narrator = byId[builtInRoles.narrator.voiceId] ?: fallback
        val dialogue = byId[builtInRoles.dialogue.voiceId] ?: narrator
        val effectiveRoles = book?.withRules(roles, dialogue?.systemName) ?: roles
        val bookRoleIds = book?.characters?.map { book.roleId(it.id) }?.toSet().orEmpty()

        val pipeline = TextPipeline.build(
            text = text,
            builtInRoles = builtInRoles.withVoices(narrator?.systemName, dialogue?.systemName),
            roles = effectiveRoles.items,
            replacements = replacements.items,
            filterPurePunctuation = filterPurePunctuation,
            roleGroups = effectiveRoles.groups,
            replacementGroups = replacements.groups,
            maxSegmentChars = maxSegmentChars,
            unattributedPolicy = unattributedPolicy,
        )
        val segments = pipeline.segments.mapNotNull { segment ->
            val fallbackVoice = if (segment.isDialogue) dialogue else narrator
            val voice = byId[segment.voiceId] ?: fallbackVoice ?: return@mapNotNull null
            PlannedSegment(segment.copy(voiceId = voice.systemName,
                fallbackReason = if (segment.voiceId != voice.systemName)
                    "指定音色不可用，本段使用 ${voice.displayName}" else null), voice,
                standbyVoice = if (segment.roleId in bookRoleIds) dialogue else null,
                useDefaultStandby = segment.roleId in bookRoleIds)
        }
        val resolved = segments.associate { it.segment.id to it.segment }
        return BuiltSynthesisPlan(pipeline.copy(segments = pipeline.segments.map {
            resolved[it.id] ?: it.copy(fallbackReason = "没有可用音色")
        }), segments)
    }
}
