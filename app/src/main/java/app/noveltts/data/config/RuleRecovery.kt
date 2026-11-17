package app.noveltts.data.config

import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleSet
import java.io.File

data class RuleRecoveryCandidate(val fileName: String, val kind: RulesKind, val modified: Long,
    val bytes: Long, val error: String? = null, val roles: RuleSet<RoleRule>? = null,
    val replacements: RuleSet<ReplacementRule>? = null) {
    val ruleCount: Int get() = roles?.items?.size ?: replacements?.items?.size ?: 0
    val valid: Boolean get() = error == null && (roles != null || replacements != null)
}
internal object RuleRecovery {
    val suffixes = listOf(".bak", ".restore.bak", ".recovery.bak", ".before-recovery.bak", ".corrupt")
    fun read(directory: File, name: String, kind: RulesKind): RuleRecoveryCandidate {
        val base = if (kind == RulesKind.ROLE) "roles.json" else "replacements.json"
        require(suffixes.any { name == base + it }) { "恢复来源无效" }
        val file = File(directory, name)
        val initial = RuleRecoveryCandidate(name, kind, file.lastModified(), file.length())
        return runCatching {
            val text = file.inputStream().use(RulesImport::readText)
            if (kind == RulesKind.ROLE) {
                val preview = RulesImport.previewRoles(text, emptyList(), emptyList())
                initial.copy(roles = RuleSet(preview.groups, preview.entries.map { it.rule }))
            } else {
                val preview = RulesImport.previewReplacements(text, emptyList(), emptyList())
                initial.copy(replacements = RuleSet(preview.groups, preview.entries.map { it.rule }))
            }
        }.getOrElse { initial.copy(error = it.message ?: "文件无法解析") }
    }
    fun list(directory: File): List<RuleRecoveryCandidate> = buildList {
        listOf(RulesKind.ROLE to "roles.json", RulesKind.REPLACEMENT to "replacements.json").forEach { (kind, base) ->
            suffixes.forEach { suffix -> if (File(directory, base + suffix).isFile) add(read(directory, base + suffix, kind)) }
        }
    }
}
