package app.noveltts.data.config

import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.RuleSet
import org.json.JSONObject

data class RuleEditorDraft<T>(val rule: T, val newGroupName: String, val fields: Map<String, String> = emptyMap())

/** Drafts may be incomplete and keep raw inputs; formal imports still validate and normalize. */
object RuleDrafts {
    fun encodeRole(rule: RoleRule, groups: List<RuleGroup>, newGroupName: String, fields: Map<String, String>): String =
        decorate(RulesImport.exportRoles(RuleSet(groups, listOf(rule))), newGroupName, fields)

    fun encodeReplacement(rule: ReplacementRule, groups: List<RuleGroup>, newGroupName: String, fields: Map<String, String>): String =
        decorate(RulesImport.exportReplacements(RuleSet(groups, listOf(rule))), newGroupName, fields)

    fun readRole(text: String, legacyGroupName: String = ""): RuleEditorDraft<RoleRule> {
        val root = JSONObject(text)
        return RuleEditorDraft(RulesImport.decodeRoleDraft(root.getJSONArray("items").getJSONObject(0)),
            root.optString("draftGroupName", legacyGroupName), fields(root))
    }

    fun readReplacement(text: String, legacyGroupName: String = ""): RuleEditorDraft<ReplacementRule> {
        val root = JSONObject(text)
        return RuleEditorDraft(RulesImport.decodeReplacementDraft(root.getJSONArray("items").getJSONObject(0)),
            root.optString("draftGroupName", legacyGroupName), fields(root))
    }

    private fun decorate(text: String, groupName: String, fields: Map<String, String>): String =
        JSONObject(text).put("draftGroupName", groupName).put("draftFields", JSONObject(fields)).toString()

    private fun fields(root: JSONObject): Map<String, String> = root.optJSONObject("draftFields")?.let { values ->
        values.keys().asSequence().associateWith { values.getString(it) }
    } ?: emptyMap()
}
