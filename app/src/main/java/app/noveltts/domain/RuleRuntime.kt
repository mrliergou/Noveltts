package app.noveltts.domain

import java.io.Serializable

internal data class RuleWork(
    val text: String = "", val builtIn: BuiltInRoleSettings = BuiltInRoleSettings(),
    val roles: List<RoleRule> = emptyList(), val replacements: List<ReplacementRule> = emptyList(),
    val punctuation: Boolean = false, val roleGroups: List<RuleGroup> = emptyList(),
    val replacementGroups: List<RuleGroup> = emptyList(), val maxSegmentChars: Int = 500,
    val policy: UnattributedPolicy = UnattributedPolicy.DEFAULT,
    val validation: String? = null,
) : Serializable
internal data class RuleReply(val pipeline: TextPipelineResult? = null, val validation: String? = null,
    val error: String? = null) : Serializable

/** Android installs a remote executor at Application startup. JVM tests use the pure implementation. */
object RuleRuntime {
    internal var remote: ((RuleWork, Boolean) -> RuleReply)? = null
    private val previewMode = ThreadLocal.withInitial { false }
    fun <T> preview(block: () -> T): T {
        val previous = previewMode.get()
        previewMode.set(true)
        return try { block() } finally { previewMode.set(previous == true) }
    }
    internal fun execute(work: RuleWork): RuleReply {
        val reply = remote?.invoke(work, (previewMode.get() == true) || work.validation != null) ?: local(work)
        reply.error?.let { throw PipelineLimitException(it) }
        return reply
    }
    internal fun local(work: RuleWork): RuleReply = when (work.validation) {
        "role" -> RuleReply(validation = RuleValidation.localRoleError(work.roles.single()))
        "replacement" -> RuleReply(validation = RuleValidation.localReplacementError(work.replacements.single()))
        else -> RuleReply(pipeline = TextPipeline.buildLocal(work.text, work.builtIn, work.roles, work.replacements,
            work.punctuation, work.roleGroups, work.replacementGroups, work.maxSegmentChars, work.policy))
    }
}