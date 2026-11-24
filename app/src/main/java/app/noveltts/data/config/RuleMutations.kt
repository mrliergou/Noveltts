package app.noveltts.data.config

import app.noveltts.domain.*

/** All read/modify/write operations share the repository's configuration lock. */
class RuleMutations(private val repository: RulesRepository) {
    fun roles(change: (RuleSet<RoleRule>) -> RuleSet<RoleRule>): RuleSet<RoleRule> =
        repository.withConfigurationLock {
            change(repository.loadRoles()).also(repository::saveRoles)
        }

    fun replacements(change: (RuleSet<ReplacementRule>) -> RuleSet<ReplacementRule>): RuleSet<ReplacementRule> =
        repository.withConfigurationLock {
            change(repository.loadReplacements()).also(repository::saveReplacements)
        }

    fun applyRoles(preview: RulesImportPreview<RoleRule>, strategy: RuleImportStrategy): RulesImportApplyResult<RoleRule> =
        repository.withConfigurationLock {
            val current = repository.loadRoles()
            if (preview.baseFingerprint != RulesImport.rolesFingerprint(current)) {
                RulesImportApplyResult.PreviewChanged(RulesImport.previewRoles(
                    preview.sourceText ?: RulesImport.exportRoles(RuleSet(preview.groups, preview.entries.map { it.rule })),
                    current.groups, current.items,
                ).changed(strategy))
            } else {
                val merged = RulesImport.mergeRoles(preview, current, strategy)
                repository.saveRoles(merged)
                RulesImportApplyResult.Applied(merged)
            }
        }

    fun applyReplacements(preview: RulesImportPreview<ReplacementRule>, strategy: RuleImportStrategy): RulesImportApplyResult<ReplacementRule> =
        repository.withConfigurationLock {
            val current = repository.loadReplacements()
            if (preview.baseFingerprint != RulesImport.replacementsFingerprint(current)) {
                RulesImportApplyResult.PreviewChanged(RulesImport.previewReplacements(
                    preview.sourceText ?: RulesImport.exportReplacements(RuleSet(preview.groups, preview.entries.map { it.rule })),
                    current.groups, current.items,
                ).changed(strategy))
            } else {
                val merged = RulesImport.mergeReplacements(preview, current, strategy)
                repository.saveReplacements(merged)
                RulesImportApplyResult.Applied(merged)
            }
        }

    private fun <T> RulesImportPreview<T>.changed(strategy: RuleImportStrategy) = copy(
        suggestedStrategy = strategy,
        warnings = listOf("现有配置已变化，预览已刷新。请核对后再次确认（上次选择：${strategy.label}）。") + warnings,
    )

    data class MoveAnchor(val id: String?, val after: Boolean)
    companion object {
        /** Drag indices include the moving row; remove that row before choosing a stable neighbour. */
        fun anchor(ids: List<String>, movingId: String, requestedIndex: Int): MoveAnchor? {
            val current = ids.indexOf(movingId)
            val requested = requestedIndex.coerceAtLeast(0)
            if (current >= 0 && (requested == current || requested == current + 1)) return null
            val others = ids.filterNot { it == movingId }
            val adjusted = if (current >= 0 && current < requested) requested - 1 else requested
            val index = adjusted.coerceIn(0, others.size)
            return if (index == others.size) MoveAnchor(others.lastOrNull(), true) else MoveAnchor(others[index], false)
        }
        fun <T> saveGroup(set: RuleSet<T>, draft: RuleGroup, base: RuleGroup?): RuleSet<T> {
            val latest = set.groups.firstOrNull { it.id == draft.id }
            if (base != null && latest == null) throw RulesStoreException("分组已删除，请刷新后重试")
            val saved = if (latest == null) draft.copy(order = nextRuleGroupOrder(set.groups))
            else if (base == null) latest
            else latest.copy(
                name = if (draft.name != base.name) draft.name else latest.name,
                enabled = if (draft.enabled != base.enabled) draft.enabled else latest.enabled,
            )
            return set.copy(groups = set.groups.filterNot { it.id == saved.id } + saved)
        }

        fun <T> move(set: RuleSet<T>, id: String, groupId: String, anchorId: String?, after: Boolean,
            idOf: (T) -> String, groupOf: (T) -> String, sortOf: (T) -> Int,
            place: (T, String, Int) -> T): RuleSet<T> {
            val rule = set.items.firstOrNull { idOf(it) == id } ?: throw RulesStoreException("规则已删除，请刷新后重试")
            if (set.groups.none { it.id == groupId }) throw RulesStoreException("目标分组已删除，请刷新后重试")
            val target = set.items.filter { groupOf(it) == groupId && idOf(it) != id }.sortedBy(sortOf).toMutableList()
            val position = if (anchorId == null) target.size else {
                val index = target.indexOfFirst { idOf(it) == anchorId }
                if (index < 0) throw RulesStoreException("排序位置已变化，请刷新后重试")
                index + if (after) 1 else 0
            }
            target.add(position, rule)
            val placed = target.mapIndexed { index, item -> place(item, groupId, index) }
            return set.copy(items = set.items.filterNot { idOf(it) == id || groupOf(it) == groupId } + placed)
        }
    }
}

sealed interface RulesImportApplyResult<T> {
    data class Applied<T>(val configuration: RuleSet<T>) : RulesImportApplyResult<T>
    data class PreviewChanged<T>(val preview: RulesImportPreview<T>) : RulesImportApplyResult<T>
}