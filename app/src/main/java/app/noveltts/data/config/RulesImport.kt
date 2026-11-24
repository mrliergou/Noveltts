package app.noveltts.data.config

import app.noveltts.data.stringOrDefault

import app.noveltts.data.JsonInput
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleMatchScope
import app.noveltts.domain.ReplacementScope
import app.noveltts.domain.RoleMatchMode
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.RuleSet
import app.noveltts.domain.VoiceSelectionMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate

/** 规则种类：决定导出文件名、JSON 的 `kind` 字段与导入目标页。 */
enum class RulesKind(val label: String, val filePrefix: String, val jsonKind: String) {
    ROLE("角色规则", "roles", "roles"),
    REPLACEMENT("替换规则", "replacements", "replacements"),
}

/** 导入条目与现有规则的关系。同名以去掉首尾空格后的名称比较。 */
enum class RuleImportConflict(val label: String) {
    NONE("新增"),
    SAME_ID("同 ID"),
    SAME_NAME("同名"),
}

/** 冲突处理策略；[REPLACE_ALL] 会整体替换当前规则与分组。 */
enum class RuleImportStrategy(val label: String) {
    SKIP("跳过冲突"),
    OVERWRITE("覆盖冲突"),
    REPLACE_ALL("全部覆盖"),
}

data class RuleImportEntry<T>(
    val rule: T,
    val groupName: String,
    val conflict: RuleImportConflict,
    /** 冲突时将被覆盖的现有规则名称。 */
    val existingName: String? = null,
)

data class RulesImportPreview<T>(
    val kind: RulesKind,
    val entries: List<RuleImportEntry<T>>,
    val groups: List<RuleGroup>,
    val newGroups: List<RuleGroup>,
    val existingGroups: List<RuleGroup>,
    val warnings: List<String>,
    val baseFingerprint: String = "",
    val sourceText: String? = null,
    val suggestedStrategy: RuleImportStrategy? = null,
) {
    val newCount: Int get() = entries.count { it.conflict == RuleImportConflict.NONE }
    val sameIdCount: Int get() = entries.count { it.conflict == RuleImportConflict.SAME_ID }
    val sameNameCount: Int get() = entries.count { it.conflict == RuleImportConflict.SAME_NAME }
    val conflictCount: Int get() = sameIdCount + sameNameCount
    val hasConflict: Boolean get() = conflictCount > 0
}

/**
 * 规则导入导出的纯数据层：解析、校验、冲突预览与合并，不依赖 Android 与 Compose，可直接单测。
 *
 * 文件格式与 [RulesRepository] 的 v2 结构一致（`{schema, groups, items}`），额外带
 * `kind` 与 `exportedAt` 便于诊断。编码/解码逻辑与仓库保持一致，但仓库里的实现是私有的，
 * 这里保留一份用于导入导出；修改字段时两处都要改。
 */
object RulesImport {
    /** 导入文件上限 1 MB。 */
    const val MAX_FILE_BYTES = 1_048_576

    /** 单次导入条目上限。 */
    const val MAX_ITEMS = 500

    /** JSON 嵌套层数上限（导出文件的自然深度是 4 层）。 */
    const val MAX_DEPTH = 5

    private const val SCHEMA = 2
    private const val APP_TAG = "NovelTTS"

    fun rolesFingerprint(set: RuleSet<RoleRule>): String = fingerprint(exportRoles(set))
    fun replacementsFingerprint(set: RuleSet<ReplacementRule>): String = fingerprint(exportReplacements(set))
    private fun fingerprint(json: String): String {
        val stable = JSONObject(json).apply { remove("exportedAt") }.toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    // ---- 导出 ---------------------------------------------------------------

    fun exportRoles(set: RuleSet<RoleRule>): String =
        exportJson(RulesKind.ROLE, set.groups, set.items.map(::encodeRole))

    fun exportReplacements(set: RuleSet<ReplacementRule>): String =
        exportJson(RulesKind.REPLACEMENT, set.groups, set.items.map(::encodeReplacement))

    fun exportFileName(kind: RulesKind, date: LocalDate = LocalDate.now()): String =
        "${kind.filePrefix}-export-${date.toString().replace("-", "")}.json"

    private fun exportJson(kind: RulesKind, groups: List<RuleGroup>, items: List<JSONObject>): String =
        JSONObject()
            .put("schema", SCHEMA)
            .put("kind", kind.jsonKind)
            .put("app", APP_TAG)
            .put("exportedAt", Instant.now().toString())
            .put("groups", encodeGroups(groups))
            .put("items", JSONArray().apply { items.forEach(::put) })
            .toString(2)

    private fun encodeGroups(groups: List<RuleGroup>) = JSONArray().apply {
        groups.forEach { group ->
            put(
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("enabled", group.enabled)
                    .put("order", group.order),
            )
        }
    }

    private fun encodeRole(rule: RoleRule) = JSONObject()
        .put("id", rule.id)
        .put("name", rule.name)
        .put("pattern", rule.pattern)
        .put("voiceIds", JSONArray(rule.voiceIds))
        .put("voiceId", rule.primaryVoiceId.orEmpty())
        .put("priority", rule.priority)
        .put("onlyDialogue", rule.onlyDialogue)
        .put("enabled", rule.enabled)
        .put("groupId", rule.groupId)
        .put("matchMode", rule.matchMode.name)
        .put("voiceSelectionMode", rule.voiceSelectionMode.name)
        .put("matchScope", rule.matchScope.name)
        .put("contextChars", rule.contextChars)
        .put("speechRate", rule.speechRate)
        .put("speechPitch", rule.speechPitch)
        .put("speechVolume", rule.speechVolume)
        .put("sortIndex", rule.sortIndex)

    private fun encodeReplacement(rule: ReplacementRule) = JSONObject()
        .put("id", rule.id)
        .put("name", rule.name)
        .put("find", rule.find)
        .put("replaceWith", rule.replaceWith)
        .put("isRegex", rule.isRegex)
        .put("scope", rule.scope.name)
        .put("targetRoleId", rule.targetRoleId)
        .put("enabled", rule.enabled)
        .put("order", rule.order)
        .put("sortIndex", rule.sortIndex)
        .put("groupId", rule.groupId)

    // ---- 解析与冲突预览 -----------------------------------------------------

    fun previewRoles(
        text: String,
        currentGroups: List<RuleGroup>,
        existing: List<RoleRule>,
        externalImport: Boolean = true,
    ): RulesImportPreview<RoleRule> = previewOf(
        kind = RulesKind.ROLE,
        text = text,
        externalImport = externalImport,
        currentGroups = currentGroups,
        existing = existing,
        decode = ::decodeRole,
        idOf = RoleRule::id,
        nameOf = RoleRule::name,
        groupOf = RoleRule::groupId,
        withId = { rule, id -> rule.copy(id = id) },
        withGroup = { rule, groupId -> rule.copy(groupId = groupId) },
    )

    fun previewReplacements(
        text: String,
        currentGroups: List<RuleGroup>,
        existing: List<ReplacementRule>,
        externalImport: Boolean = true,
    ): RulesImportPreview<ReplacementRule> = previewOf(
        kind = RulesKind.REPLACEMENT,
        text = text,
        externalImport = externalImport,
        currentGroups = currentGroups,
        existing = existing,
        decode = ::decodeReplacement,
        idOf = ReplacementRule::id,
        nameOf = ReplacementRule::name,
        groupOf = ReplacementRule::groupId,
        withId = { rule, id -> rule.copy(id = id) },
        withGroup = { rule, groupId -> rule.copy(groupId = groupId) },
    )

    private fun <T> previewOf(
        kind: RulesKind,
        text: String,
        externalImport: Boolean,
        currentGroups: List<RuleGroup>,
        existing: List<T>,
        decode: (JSONObject, Int, Int) -> T?,
        idOf: (T) -> String,
        nameOf: (T) -> String,
        groupOf: (T) -> String,
        withId: (T, String) -> T,
        withGroup: (T, String) -> T,
    ): RulesImportPreview<T> {
        val root = parseRoot(text, kind, externalImport)
        val schema = schemaOf(root)
        val rawItems = items(root)
        if (externalImport && rawItems.size > MAX_ITEMS) {
            throw IllegalArgumentException("文件包含 ${rawItems.size} 条规则，超过 ${MAX_ITEMS} 条上限，请拆分后再导入")
        }
        val importedGroups = normalizeRuleGroups(readGroups(root))
        val knownGroups = importedGroups.mapTo(HashSet()) { it.id }
        val seenIds = HashSet<String>()
        var dropped = 0
        val normalized = rawItems.mapIndexedNotNull { index, item ->
            val rule = decode(item, index, schema)
            if (rule == null) {
                dropped++
                null
            } else {
                rule
            }
        }.map { rule ->
            val unique = withId(rule, claimId(seenIds, idOf(rule)))
            if (groupOf(unique) in knownGroups) unique else withGroup(unique, RuleGroup.DEFAULT_ID)
        }
        val entries = normalized.map { rule -> buildEntry(rule, importedGroups, existing, idOf, nameOf, groupOf) }
        val newGroups = importedGroups.filter { group ->
            currentGroups.none { it.id == group.id || it.name.trim() == group.name.trim() }
        }
        val warnings = mutableListOf<String>()
        if (dropped > 0) warnings += "已跳过 $dropped 条缺少必要字段的规则"
        importedGroups.forEach { group ->
            val sameId = currentGroups.firstOrNull { it.id == group.id }
            val sameName = currentGroups.firstOrNull { it.id != group.id && it.name.trim() == group.name.trim() }
            when {
                sameId != null && sameId.name.trim() != group.name.trim() ->
                    warnings += "分组「${group.name}」将沿用现有名称「${sameId.name}」"

                sameName != null -> warnings += "分组「${group.name}」将并入现有同名分组"
            }
        }
        return RulesImportPreview(
            kind = kind,
            entries = entries,
            groups = importedGroups,
            newGroups = newGroups,
            existingGroups = importedGroups.filterNot { it in newGroups },
            warnings = warnings,
            sourceText = text,
            baseFingerprint = when (kind) {
                RulesKind.ROLE -> rolesFingerprint(RuleSet(currentGroups, existing.map { it as RoleRule }))
                RulesKind.REPLACEMENT -> replacementsFingerprint(RuleSet(currentGroups, existing.map { it as ReplacementRule }))
            },
        )
    }

    private fun <T> buildEntry(
        rule: T,
        importedGroups: List<RuleGroup>,
        existing: List<T>,
        idOf: (T) -> String,
        nameOf: (T) -> String,
        groupOf: (T) -> String,
    ): RuleImportEntry<T> {
        val sameId = existing.firstOrNull { idOf(it) == idOf(rule) }
        val sameName = existing.firstOrNull { nameOf(it).trim() == nameOf(rule).trim() }
        val conflict = when {
            sameId != null -> RuleImportConflict.SAME_ID
            sameName != null -> RuleImportConflict.SAME_NAME
            else -> RuleImportConflict.NONE
        }
        return RuleImportEntry(
            rule = rule,
            groupName = importedGroups.firstOrNull { it.id == groupOf(rule) }?.name ?: RuleGroup.DEFAULT.name,
            conflict = conflict,
            existingName = (sameId ?: sameName)?.let(nameOf),
        )
    }

    // ---- 合并 ---------------------------------------------------------------

    fun mergeRoles(
        preview: RulesImportPreview<RoleRule>,
        current: RuleSet<RoleRule>,
        strategy: RuleImportStrategy,
    ): RuleSet<RoleRule> = merge(
        preview = preview,
        current = current,
        strategy = strategy,
        idOf = RoleRule::id,
        nameOf = RoleRule::name,
        groupOf = RoleRule::groupId,
        withId = { rule, id -> rule.copy(id = id) },
        withGroup = { rule, groupId -> rule.copy(groupId = groupId) },
        withSort = { rule, sortIndex -> rule.copy(sortIndex = sortIndex) },
        sortIndexOf = RoleRule::sortIndex,
    )

    fun mergeReplacements(
        preview: RulesImportPreview<ReplacementRule>,
        current: RuleSet<ReplacementRule>,
        strategy: RuleImportStrategy,
    ): RuleSet<ReplacementRule> = merge(
        preview = preview,
        current = current,
        strategy = strategy,
        idOf = ReplacementRule::id,
        nameOf = ReplacementRule::name,
        groupOf = ReplacementRule::groupId,
        withId = { rule, id -> rule.copy(id = id) },
        withGroup = { rule, groupId -> rule.copy(groupId = groupId) },
        withSort = { rule, sortIndex -> rule.copy(sortIndex = sortIndex) },
        sortIndexOf = ReplacementRule::sortIndex,
    )

    private fun <T> merge(
        preview: RulesImportPreview<T>,
        current: RuleSet<T>,
        strategy: RuleImportStrategy,
        idOf: (T) -> String,
        nameOf: (T) -> String,
        groupOf: (T) -> String,
        withId: (T, String) -> T,
        withGroup: (T, String) -> T,
        withSort: (T, Int) -> T,
        sortIndexOf: (T) -> Int,
    ): RuleSet<T> {
        val currentGroups = normalizeRuleGroups(current.groups)
        if (strategy == RuleImportStrategy.REPLACE_ALL) {
            val groups = normalizeRuleGroups(if (preview.groups.isEmpty()) currentGroups else preview.groups)
            return RuleSet(
                groups = groups,
                items = reindex(preview.entries.map { it.rule }, groups, groupOf, withGroup, withSort, sortIndexOf),
            )
        }

        val groups = currentGroups.toMutableList()
        val groupRemap = mutableMapOf<String, String>()
        preview.groups.forEach { group ->
            val sameId = groups.firstOrNull { it.id == group.id }
            val sameName = groups.firstOrNull { it.name.trim() == group.name.trim() }
            when {
                sameId != null -> groupRemap[group.id] = sameId.id
                sameName != null -> groupRemap[group.id] = sameName.id
                else -> {
                    groups += group.copy(order = nextRuleGroupOrder(groups))
                    groupRemap[group.id] = group.id
                }
            }
        }

        val items = current.items.toMutableList()
        preview.entries.forEach { entry ->
            val targetGroup = groupRemap[groupOf(entry.rule)] ?: RuleGroup.DEFAULT_ID
            val rule = withGroup(entry.rule, targetGroup)
            when (buildEntry(rule, preview.groups, items, idOf, nameOf, groupOf).conflict) {
                RuleImportConflict.NONE -> items += rule
                RuleImportConflict.SAME_ID -> if (strategy == RuleImportStrategy.OVERWRITE) {
                    val index = items.indexOfFirst { idOf(it) == idOf(rule) }
                    if (index >= 0) items[index] = withId(rule, idOf(items[index]))
                }

                RuleImportConflict.SAME_NAME -> if (strategy == RuleImportStrategy.OVERWRITE) {
                    val index = items.indexOfFirst { nameOf(it).trim() == nameOf(rule).trim() }
                    if (index >= 0) items[index] = withId(rule, idOf(items[index]))
                }
            }
        }
        val mergedGroups = normalizeRuleGroups(groups)
        return RuleSet(
            groups = mergedGroups,
            items = reindex(items, mergedGroups, groupOf, withGroup, withSort, sortIndexOf),
        )
    }

    /** 按分组 order + 名称排好分组，再按组内 sortIndex 重排；未知分组回落到默认分组。 */
    private fun <T> reindex(
        items: List<T>,
        groups: List<RuleGroup>,
        groupOf: (T) -> String,
        withGroup: (T, String) -> T,
        withSort: (T, Int) -> T,
        sortIndexOf: (T) -> Int,
    ): List<T> {
        val order = groups.sortedWith(compareBy<RuleGroup> { it.order }.thenBy { it.name }).map { it.id }
        val known = order.toSet()
        val fixed = items.map { item -> if (groupOf(item) in known) item else withGroup(item, RuleGroup.DEFAULT_ID) }
        return order.flatMap { groupId ->
            fixed.filter { groupOf(it) == groupId }
                .sortedBy(sortIndexOf)
                .mapIndexed { index, item -> withSort(item, index) }
        }
    }

    // ---- 校验与解码 ---------------------------------------------------------

    private fun parseRoot(text: String, kind: RulesKind, externalImport: Boolean): JSONObject {
        if (text.isBlank()) throw IllegalArgumentException("文件是空的")
        if (text.toByteArray(Charsets.UTF_8).size > if (externalImport) MAX_FILE_BYTES else 32 * 1024 * 1024) throw IllegalArgumentException("导入文件超过 1 MB 上限，请拆分后再导入")
        JsonInput.checkDepth(text, MAX_DEPTH)
        val root = try {
            JSONObject(text.removePrefix("\uFEFF"))
        } catch (error: Exception) {
            throw IllegalArgumentException("不是有效的规则 JSON，请检查文件格式")
        }
        if (root.optJSONArray("items") == null) throw IllegalArgumentException("缺少 items 数组，这不是规则导出文件")
        val fileKind = root.stringOrDefault("kind")
        if (fileKind.isNotBlank() && fileKind != kind.jsonKind) {
            val label = RulesKind.entries.firstOrNull { it.jsonKind == fileKind }?.label ?: fileKind
            throw IllegalArgumentException("这个文件是${label}，不能导入到「${kind.label}」")
        }
        return root
    }

    private fun schemaOf(root: JSONObject): Int {
        if (!root.has("schema")) return 1
        val schema = root.optInt("schema", -1)
        if (schema < 1) throw IllegalArgumentException("文件的 schema 无效")
        if (schema > SCHEMA) throw IllegalArgumentException("文件来自更新版本（schema $schema），当前仅支持 $SCHEMA")
        return schema
    }

    private fun items(root: JSONObject): List<JSONObject> {
        val array = root.getJSONArray("items")
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: throw IllegalArgumentException("items[$index] 不是对象，文件结构不合法")
            item
        }
    }

    private fun readGroups(root: JSONObject): List<RuleGroup> {
        val array = root.optJSONArray("groups") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.stringOrDefault("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RuleGroup(
                id = id,
                name = item.stringOrDefault("name").ifBlank {
                    if (id == RuleGroup.DEFAULT_ID) RuleGroup.DEFAULT.name else id
                },
                enabled = item.optBoolean("enabled", true),
                order = item.optInt("order", 100),
            )
        }
    }

    internal fun decodeRoleDraft(item: JSONObject): RoleRule = requireNotNull(decodeRoleFields(item, 0, SCHEMA, true))
    internal fun decodeReplacementDraft(item: JSONObject): ReplacementRule = requireNotNull(decodeReplacementFields(item, 0, SCHEMA, true))

    private fun decodeRole(item: JSONObject, index: Int, schema: Int): RoleRule? = decodeRoleFields(item, index, schema, false)

    private fun decodeRoleFields(item: JSONObject, index: Int, schema: Int, incomplete: Boolean): RoleRule? {
        val pattern = item.stringOrDefault("pattern")
        if (!incomplete && pattern.isBlank()) return null
        val name = if (incomplete) item.stringOrDefault("name") else item.stringOrDefault("name").trim().ifBlank { pattern }
        val legacyVoice = item.stringOrDefault("voiceId").takeIf { it.isNotBlank() }
        val voiceIds = if (schema >= 2) {
            item.optJSONArray("voiceIds")?.toStringList() ?: listOfNotNull(legacyVoice)
        } else {
            listOfNotNull(legacyVoice)
        }
        return RoleRule(
            id = item.stringOrDefault("id").ifBlank { "import-role-$index" },
            name = name,
            pattern = pattern,
            voiceIds = voiceIds,
            priority = item.optInt("priority", 100),
            onlyDialogue = item.optBoolean("onlyDialogue", true),
            enabled = item.optBoolean("enabled", true),
            groupId = if (schema >= 2) item.stringOrDefault("groupId").ifBlank { RuleGroup.DEFAULT_ID } else RuleGroup.DEFAULT_ID,
            matchMode = item.optEnum("matchMode", RoleMatchMode.REGEX_CONTAINS),
            voiceSelectionMode = item.optEnum("voiceSelectionMode", VoiceSelectionMode.FIXED),
            matchScope = item.optEnum("matchScope", RoleMatchScope.SEGMENT),
            contextChars = item.optInt("contextChars", 200).coerceIn(1, 2000),
            speechRate = item.optDouble("speechRate", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f,
            speechPitch = item.optDouble("speechPitch", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f,
            speechVolume = item.optDouble("speechVolume", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
            sortIndex = item.optInt("sortIndex", index),
        )
    }

    private fun decodeReplacement(item: JSONObject, index: Int, schema: Int): ReplacementRule? = decodeReplacementFields(item, index, schema, false)

    private fun decodeReplacementFields(item: JSONObject, index: Int, schema: Int, incomplete: Boolean): ReplacementRule? {
        val find = item.stringOrDefault("find")
        if (!incomplete && find.isEmpty()) return null
        return ReplacementRule(
            id = item.stringOrDefault("id").ifBlank { "import-replacement-$index" },
            name = if (incomplete) item.stringOrDefault("name") else item.stringOrDefault("name").ifBlank { find },
            find = find,
            replaceWith = item.stringOrDefault("replaceWith"),
            isRegex = item.optBoolean("isRegex", false),
            scope = item.optEnum("scope", ReplacementScope.ALL),
            targetRoleId = item.stringOrDefault("targetRoleId"),
            enabled = item.optBoolean("enabled", true),
            order = item.optInt("order", 100),
            sortIndex = item.optInt("sortIndex", index),
            groupId = if (schema >= 2) item.stringOrDefault("groupId").ifBlank { RuleGroup.DEFAULT_ID } else RuleGroup.DEFAULT_ID,
        )
    }

    // ---- 通用工具 -----------------------------------------------------------

    /** 以流的方式读取导入文件，超过 [MAX_FILE_BYTES] 立即失败，避免把整块大文件读进内存。 */
    fun readText(input: InputStream, maximumBytes: Int = MAX_FILE_BYTES): String {
        val buffer = ByteArray(16 * 1024)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumBytes) throw IllegalArgumentException("导入文件超过 1 MB 上限，请拆分后再导入")
            output.write(buffer, 0, read)
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun claimId(seen: MutableSet<String>, id: String): String {
        val base = id.ifBlank { "imported" }
        if (seen.add(base)) return base
        var suffix = 2
        while (!seen.add("$base#$suffix")) suffix++
        return "$base#$suffix"
    }
}

/** 保证默认分组存在、分组 id 唯一、分组名非空；不排序，调用方决定展示顺序。 */
internal fun normalizeRuleGroups(groups: List<RuleGroup>): List<RuleGroup> {
    val distinct = groups.filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .map { group ->
            group.copy(
                name = group.name.ifBlank {
                    if (group.id == RuleGroup.DEFAULT_ID) RuleGroup.DEFAULT.name else group.id
                },
            )
        }
    return if (distinct.any { it.id == RuleGroup.DEFAULT_ID }) distinct else listOf(RuleGroup.DEFAULT) + distinct
}

/** 新分组排在现有分组之后。 */
internal fun nextRuleGroupOrder(groups: List<RuleGroup>): Int =
    (groups.maxOfOrNull { it.order } ?: RuleGroup.DEFAULT.order) + 100

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { stringOrDefault(it).takeIf { value -> value.isNotBlank() } }

private inline fun <reified T : Enum<T>> JSONObject.optEnum(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == stringOrDefault(key) } ?: default
