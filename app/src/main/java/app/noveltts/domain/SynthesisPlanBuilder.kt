package app.noveltts.domain

import app.noveltts.data.voicepack.VoiceDescriptor
import app.noveltts.engine.PlannedSegment

data class BuiltSynthesisPlan(
    val pipeline: TextPipelineResult,
    val segments: List<PlannedSegment>,
)

object SynthesisPlanBuilder {
    fun build(
        text: String,
        narratorVoiceId: String?,
        dialogueVoiceId: String?,
        roles: RuleSet<RoleRule>,
        replacements: RuleSet<ReplacementRule>,
        filterPurePunctuation: Boolean,
        voices: List<VoiceDescriptor>,
    ): BuiltSynthesisPlan {
        val usable = voices.filter { it.usable }
        val byId = usable.associateBy { it.systemName }
        val fallback = usable.firstOrNull { it.localeTag.startsWith("zh", ignoreCase = true) }
            ?: usable.firstOrNull()
        val narrator = byId[narratorVoiceId] ?: fallback
        val dialogue = byId[dialogueVoiceId] ?: narrator

        val pipeline = TextPipeline.build(
            text = text,
            narratorVoiceId = narrator?.systemName,
            dialogueVoiceId = dialogue?.systemName,
            roles = roles.items,
            replacements = replacements.items,
            filterPurePunctuation = filterPurePunctuation,
            roleGroups = roles.groups,
            replacementGroups = replacements.groups,
        )
        val segments = pipeline.segments.mapNotNull { segment ->
            val fallbackVoice = if (segment.isDialogue) dialogue else narrator
            val voice = byId[segment.voiceId] ?: fallbackVoice ?: return@mapNotNull null
            PlannedSegment(segment, voice)
        }
        return BuiltSynthesisPlan(pipeline, segments)
    }
}
