package app.noveltts.data.config

import app.noveltts.data.stringOrDefault

import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.RuleSet
import org.json.JSONObject

data class RuleEditorDraft<T>(val rule: T, val newGroupName: String, val fields: Map<String, String> = emptyMap(),
    val expected: T? = null, val hasBaseline: Boolean = false) {
    // Legacy drafts lack a baseline: retain conflict protection instead of silently overwriting newer rules.
    val saveBaseline: T? get() = if (hasBaseline) expected else rule
}

/** Draft form values and the original persisted rule must survive independently. */
object RuleDrafts {
    fun finiteMultiplier(text: String): Float = text.toFloatOrNull()?.takeIf { it.isFinite() } ?: 1f

    fun encodeRole(rule: RoleRule, groups: List<RuleGroup>, newGroupName: String, fields: Map<String, String>,
        expected: RoleRule? = null): String = decorate(
        RulesImport.exportRoles(RuleSet(groups, listOf(rule))), newGroupName, fields,
        expected?.let { JSONObject(RulesImport.exportRoles(RuleSet(groups, listOf(it)))).getJSONArray("items").getJSONObject(0) })

    fun encodeReplacement(rule: ReplacementRule, groups: List<RuleGroup>, newGroupName: String, fields: Map<String, String>,
        expected: ReplacementRule? = null): String = decorate(
        RulesImport.exportReplacements(RuleSet(groups, listOf(rule))), newGroupName, fields,
        expected?.let { JSONObject(RulesImport.exportReplacements(RuleSet(groups, listOf(it)))).getJSONArray("items").getJSONObject(0) })

    fun readRole(text: String, legacyGroupName: String = ""): RuleEditorDraft<RoleRule> {
        val root = JSONObject(text)
        return RuleEditorDraft(RulesImport.decodeRoleDraft(root.getJSONArray("items").getJSONObject(0)),
            root.stringOrDefault("draftGroupName", legacyGroupName), fields(root),
            root.optJSONObject("draftBaseline")?.let(RulesImport::decodeRoleDraft), root.has("draftBaseline"))
    }

    fun readReplacement(text: String, legacyGroupName: String = ""): RuleEditorDraft<ReplacementRule> {
        val root = JSONObject(text)
        return RuleEditorDraft(RulesImport.decodeReplacementDraft(root.getJSONArray("items").getJSONObject(0)),
            root.stringOrDefault("draftGroupName", legacyGroupName), fields(root),
            root.optJSONObject("draftBaseline")?.let(RulesImport::decodeReplacementDraft), root.has("draftBaseline"))
    }

    private fun decorate(text: String, groupName: String, fields: Map<String, String>, baseline: JSONObject?): String =
        JSONObject(text).put("draftGroupName", groupName).put("draftFields", JSONObject(fields))
            .put("draftBaseline", baseline ?: JSONObject.NULL).toString()

    private fun fields(root: JSONObject): Map<String, String> = root.optJSONObject("draftFields")?.let { values ->
        values.keys().asSequence().associateWith { values.getString(it) }
    } ?: emptyMap()
}