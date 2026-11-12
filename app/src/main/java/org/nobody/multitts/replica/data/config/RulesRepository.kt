package org.nobody.multitts.replica.data.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.nobody.multitts.replica.domain.ReplacementRule
import org.nobody.multitts.replica.domain.RoleRule
import java.io.File

/**
 * 角色与替换规则的文件仓库。写入使用临时文件 + 备份 + 原子重命名，
 * 首版不引入 Room，但保留 schema 版本供后续迁移。
 */
class RulesRepository(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "config").apply { mkdirs() }

    fun loadRoles(): List<RoleRule> {
        val array = loadArray(ROLES_FILE) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val pattern = item.optString("pattern")
                val voiceId = item.optString("voiceId")
                if (pattern.isBlank() || voiceId.isBlank()) continue
                add(
                    RoleRule(
                        id = item.optString("id").ifBlank { name },
                        name = name.ifBlank { pattern },
                        pattern = pattern,
                        voiceId = voiceId,
                        priority = item.optInt("priority", 100),
                        onlyDialogue = item.optBoolean("onlyDialogue", true),
                        enabled = item.optBoolean("enabled", true),
                    ),
                )
            }
        }
    }

    fun saveRoles(rules: List<RoleRule>) {
        val array = JSONArray().apply {
            rules.forEach { rule ->
                put(
                    JSONObject().apply {
                        put("id", rule.id)
                        put("name", rule.name)
                        put("pattern", rule.pattern)
                        put("voiceId", rule.voiceId)
                        put("priority", rule.priority)
                        put("onlyDialogue", rule.onlyDialogue)
                        put("enabled", rule.enabled)
                    },
                )
            }
        }
        saveArray(ROLES_FILE, array)
    }

    fun loadReplacements(): List<ReplacementRule> {
        val array = loadArray(REPLACEMENTS_FILE) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val find = item.optString("find")
                if (find.isEmpty()) continue
                add(
                    ReplacementRule(
                        id = item.optString("id").ifBlank { "replacement-$index" },
                        name = item.optString("name").ifBlank { find },
                        find = find,
                        replaceWith = item.optString("replaceWith"),
                        isRegex = item.optBoolean("isRegex", false),
                        enabled = item.optBoolean("enabled", true),
                        order = item.optInt("order", 100),
                    ),
                )
            }
        }.sortedBy { it.order }
    }

    fun saveReplacements(rules: List<ReplacementRule>) {
        val array = JSONArray().apply {
            rules.sortedBy { it.order }.forEach { rule ->
                put(
                    JSONObject().apply {
                        put("id", rule.id)
                        put("name", rule.name)
                        put("find", rule.find)
                        put("replaceWith", rule.replaceWith)
                        put("isRegex", rule.isRegex)
                        put("enabled", rule.enabled)
                        put("order", rule.order)
                    },
                )
            }
        }
        saveArray(REPLACEMENTS_FILE, array)
    }

    private fun loadArray(name: String): JSONArray? {
        val file = File(directory, name)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            root.optJSONArray("items") ?: JSONArray()
        }.onFailure {
            Log.w(TAG, "读取 $name 失败，保留原文件并返回空配置", it)
        }.getOrNull()
    }

    private fun saveArray(name: String, items: JSONArray) {
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        val backup = File(directory, "$name.bak")
        val root = JSONObject().apply {
            put("schema", SCHEMA)
            put("items", items)
        }
        temp.writeText(root.toString(2))
        if (target.exists()) {
            target.copyTo(backup, overwrite = true)
        }
        if (!temp.renameTo(target)) {
            throw IllegalStateException("无法保存 $name")
        }
    }

    private companion object {
        const val TAG = "RulesRepository"
        const val SCHEMA = 1
        const val ROLES_FILE = "roles.json"
        const val REPLACEMENTS_FILE = "replacements.json"
    }
}
