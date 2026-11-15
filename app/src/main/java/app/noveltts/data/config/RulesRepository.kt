package app.noveltts.data.config

import android.content.Context
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleMatchMode
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.RuleSet
import app.noveltts.domain.VoiceSelectionMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** 规则文件损坏、版本过新或无法写入。调用方不得把它当作「空配置」继续保存。 */
class RulesStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 角色与替换规则的 JSON 仓库（schema v2：`{schema, groups, items}`）。
 *
 * - v1 文件在首次读取时迁移并立即写回 v2，原 v1 内容留在 `.bak`；
 * - 损坏的文件复制为 `.corrupt` 后抛出 [RulesStoreException]，原文件不动；
 * - 写入走「唯一临时文件 → 重新解析校验 → 备份旧文件 → 原子替换」。
 */
class RulesRepository(private val directory: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "config"))

    init {
        directory.mkdirs()
    }

    fun loadRoles(): RuleSet<RoleRule> = synchronized(LOCK) {
        val root = readRoot(ROLES_FILE) ?: return RuleSet()
        val schema = schemaOf(root, ROLES_FILE)
        val groups = readGroups(root)
        val rules = items(root, ROLES_FILE).mapIndexedNotNull { index, item -> decodeRole(item, index, schema) }
        val ordered = if (schema == 1) {
            // v1 无 sortIndex：按旧的匹配顺序（优先级降序，同级保持文件顺序）生成排名。
            rules.sortedByDescending { it.priority }.reindexed { rule, rank -> rule.copy(sortIndex = rank) }
        } else {
            rules
        }
        val result = normalizeRoles(RuleSet(groups, ordered))
        if (schema < SCHEMA) writeRoles(result)
        result
    }

    fun saveRoles(ruleSet: RuleSet<RoleRule>) = synchronized(LOCK) {
        writeRoles(normalizeRoles(ruleSet))
    }

    fun loadReplacements(): RuleSet<ReplacementRule> = synchronized(LOCK) {
        val root = readRoot(REPLACEMENTS_FILE) ?: return RuleSet()
        val schema = schemaOf(root, REPLACEMENTS_FILE)
        val groups = readGroups(root)
        val rules = items(root, REPLACEMENTS_FILE).mapIndexedNotNull { index, item ->
            decodeReplacement(item, index, schema)
        }
        val ordered = if (schema == 1) {
            // v1 按 order 升序执行（稳定排序），迁移后用排名作为 sortIndex。
            rules.sortedBy { it.order }.reindexed { rule, rank -> rule.copy(sortIndex = rank) }
        } else {
            rules
        }
        val result = normalizeReplacements(RuleSet(groups, ordered))
        if (schema < SCHEMA) writeReplacements(result)
        result
    }

    fun saveReplacements(ruleSet: RuleSet<ReplacementRule>) = synchronized(LOCK) {
        writeReplacements(normalizeReplacements(ruleSet))
    }

    // ---- 解码 ---------------------------------------------------------------

    private fun decodeRole(item: JSONObject, index: Int, schema: Int): RoleRule? {
        val pattern = item.optString("pattern")
        if (pattern.isBlank()) return null
        val name = item.optString("name").trim()
        val legacyVoice = item.optString("voiceId").takeIf { it.isNotBlank() }
        val voiceIds = if (schema >= 2) item.optJSONArray("voiceIds")?.toStringList() else null
        val priority = item.optInt("priority", 100)
        return RoleRule(
            id = item.optString("id").ifBlank { name.ifBlank { "role-$index" } },
            name = name.ifBlank { pattern },
            pattern = pattern,
            voiceIds = voiceIds ?: listOfNotNull(legacyVoice),
            priority = priority,
            onlyDialogue = item.optBoolean("onlyDialogue", true),
            enabled = item.optBoolean("enabled", true),
            groupId = if (schema >= 2) item.optString("groupId") else RuleGroup.DEFAULT_ID,
            matchMode = item.optEnum("matchMode", RoleMatchMode.REGEX_CONTAINS),
            voiceSelectionMode = item.optEnum("voiceSelectionMode", VoiceSelectionMode.FIXED),
            sortIndex = item.optInt("sortIndex", index),
        )
    }

    private fun decodeReplacement(item: JSONObject, index: Int, schema: Int): ReplacementRule? {
        val find = item.optString("find")
        if (find.isEmpty()) return null
        return ReplacementRule(
            id = item.optString("id").ifBlank { "replacement-$index" },
            name = item.optString("name").ifBlank { find },
            find = find,
            replaceWith = item.optString("replaceWith"),
            isRegex = item.optBoolean("isRegex", false),
            enabled = item.optBoolean("enabled", true),
            order = item.optInt("order", 100),
            sortIndex = item.optInt("sortIndex", index),
            groupId = if (schema >= 2) item.optString("groupId") else RuleGroup.DEFAULT_ID,
        )
    }

    private fun readGroups(root: JSONObject): List<RuleGroup> {
        val array = root.optJSONArray("groups") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuleGroup(
                id = id,
                name = item.optString("name").ifBlank {
                    if (id == RuleGroup.DEFAULT_ID) RuleGroup.DEFAULT.name else id
                },
                enabled = item.optBoolean("enabled", true),
                order = item.optInt("order", 100),
            )
        }
    }

    // ---- 归一化：保证默认分组存在、分组 id 唯一、条目引用的分组都存在 -----------

    private fun normalizeGroups(groups: List<RuleGroup>): List<RuleGroup> {
        val distinct = groups.filter { it.id.isNotBlank() }.distinctBy { it.id }
        return if (distinct.any { it.id == RuleGroup.DEFAULT_ID }) distinct else listOf(RuleGroup.DEFAULT) + distinct
    }

    private fun normalizeRoles(set: RuleSet<RoleRule>): RuleSet<RoleRule> {
        val groups = normalizeGroups(set.groups)
        val known = groups.mapTo(HashSet()) { it.id }
        val ids = UniqueIds()
        return RuleSet(
            groups = groups,
            items = set.items.map { rule ->
                rule.copy(
                    id = ids.claim(rule.id),
                    groupId = if (rule.groupId in known) rule.groupId else RuleGroup.DEFAULT_ID,
                )
            },
        )
    }

    private fun normalizeReplacements(set: RuleSet<ReplacementRule>): RuleSet<ReplacementRule> {
        val groups = normalizeGroups(set.groups)
        val known = groups.mapTo(HashSet()) { it.id }
        val ids = UniqueIds()
        return RuleSet(
            groups = groups,
            items = set.items.map { rule ->
                rule.copy(
                    id = ids.claim(rule.id),
                    groupId = if (rule.groupId in known) rule.groupId else RuleGroup.DEFAULT_ID,
                )
            },
        )
    }

    /**
     * v1 在 id 缺失时回落为规则名，同名规则因此共享 id；界面列表以 id 为 key，
     * 重复会直接崩溃。后出现的重复项改名为 `id#2`、`id#3`…
     */
    private class UniqueIds {
        private val seen = HashSet<String>()

        fun claim(id: String): String {
            if (seen.add(id)) return id
            var suffix = 2
            while (!seen.add("$id#$suffix")) suffix++
            return "$id#$suffix"
        }
    }

    // ---- 编码 ---------------------------------------------------------------

    private fun writeRoles(set: RuleSet<RoleRule>) =
        writeAtomically(ROLES_FILE, encodeRoot(set.groups, set.items.map(::encodeRole)))

    private fun writeReplacements(set: RuleSet<ReplacementRule>) =
        writeAtomically(REPLACEMENTS_FILE, encodeRoot(set.groups, set.items.map(::encodeReplacement)))

    private fun encodeRoot(groups: List<RuleGroup>, items: List<JSONObject>) = JSONObject().apply {
        put("schema", SCHEMA)
        put(
            "groups",
            JSONArray().apply {
                groups.forEach { group ->
                    put(
                        JSONObject()
                            .put("id", group.id)
                            .put("name", group.name)
                            .put("enabled", group.enabled)
                            .put("order", group.order),
                    )
                }
            },
        )
        put("items", JSONArray().apply { items.forEach(::put) })
    }

    private fun encodeRole(rule: RoleRule) = JSONObject()
        .put("id", rule.id)
        .put("name", rule.name)
        .put("pattern", rule.pattern)
        .put("voiceIds", JSONArray(rule.voiceIds))
        // 仅为降级兼容保留：旧版本只认单值 voiceId。
        .put("voiceId", rule.primaryVoiceId.orEmpty())
        .put("priority", rule.priority)
        .put("onlyDialogue", rule.onlyDialogue)
        .put("enabled", rule.enabled)
        .put("groupId", rule.groupId)
        .put("matchMode", rule.matchMode.name)
        .put("voiceSelectionMode", rule.voiceSelectionMode.name)
        .put("sortIndex", rule.sortIndex)

    private fun encodeReplacement(rule: ReplacementRule) = JSONObject()
        .put("id", rule.id)
        .put("name", rule.name)
        .put("find", rule.find)
        .put("replaceWith", rule.replaceWith)
        .put("isRegex", rule.isRegex)
        .put("enabled", rule.enabled)
        .put("order", rule.order)
        .put("sortIndex", rule.sortIndex)
        .put("groupId", rule.groupId)

    // ---- 文件 IO ------------------------------------------------------------

    private fun readRoot(name: String): JSONObject? {
        val target = File(directory, name)
        if (!target.isFile) return null
        val root = try {
            JSONObject(target.readText())
        } catch (error: Exception) {
            preserveCorrupt(target, error)
        }
        if (root.optJSONArray("items") == null) {
            preserveCorrupt(target, IllegalStateException("缺少 items 数组"))
        }
        return root
    }

    private fun preserveCorrupt(target: File, cause: Exception): Nothing {
        val corrupt = File(directory, "${target.name}.corrupt")
        try {
            Files.copy(target.toPath(), corrupt.toPath(), REPLACE_EXISTING)
        } catch (copyError: Exception) {
            throw RulesStoreException("${target.name} 已损坏，且无法保存恢复副本", copyError)
        }
        throw RulesStoreException("${target.name} 已损坏，原文件保留，副本见 ${corrupt.name}", cause)
    }

    private fun schemaOf(root: JSONObject, name: String): Int {
        if (!root.has("schema")) return 1
        val schema = root.optInt("schema", -1)
        if (schema < 1) throw RulesStoreException("$name 的 schema 无效")
        if (schema > SCHEMA) throw RulesStoreException("$name 来自更新版本（schema $schema），当前仅支持 $SCHEMA")
        return schema
    }

    /** 非对象条目视为结构损坏：静默跳过会在下次保存时把它们永久丢掉。 */
    private fun items(root: JSONObject, name: String): List<JSONObject> {
        val array = root.getJSONArray("items")
        return (0 until array.length()).map { index ->
            array.optJSONObject(index) ?: preserveCorrupt(
                File(directory, name),
                IllegalStateException("items[$index] 不是对象"),
            )
        }
    }

    private fun writeAtomically(name: String, root: JSONObject) {
        val target = File(directory, name)
        val backup = File(directory, "$name.bak")
        directory.mkdirs()
        val temp = File.createTempFile("$name.", ".tmp", directory)
        try {
            temp.writeText(root.toString(2))
            val verified = JSONObject(temp.readText())
            check(verified.optInt("schema") == SCHEMA && verified.optJSONArray("items") != null) {
                "临时文件校验失败"
            }
            if (target.exists()) Files.copy(target.toPath(), backup.toPath(), REPLACE_EXISTING)
            try {
                Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            throw RulesStoreException("无法保存 $name：${error.message}", error)
        } finally {
            temp.delete()
        }
    }

    private companion object {
        /** 应用进程与系统 TTS 服务可能同时触发迁移/写入，按进程全局串行化。 */
        val LOCK = Any()
        const val SCHEMA = 2
        const val ROLES_FILE = "roles.json"
        const val REPLACEMENTS_FILE = "replacements.json"
    }
}

private inline fun <T> List<T>.reindexed(transform: (T, Int) -> T): List<T> =
    mapIndexed { index, item -> transform(item, index) }

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }

private inline fun <reified T : Enum<T>> JSONObject.optEnum(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == optString(key) } ?: default
