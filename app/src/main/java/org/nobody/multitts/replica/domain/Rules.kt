package org.nobody.multitts.replica.domain

import java.util.UUID

data class RoleRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pattern: String,
    val voiceId: String,
    val priority: Int = 100,
    val onlyDialogue: Boolean = true,
    val enabled: Boolean = true,
)

data class ReplacementRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val find: String,
    val replaceWith: String,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    val order: Int = 100,
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
