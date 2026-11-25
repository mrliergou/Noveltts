package app.noveltts.data.backup

import app.noveltts.data.stringOrDefault

import app.noveltts.data.JsonInput
import app.noveltts.data.config.RulesImport
import app.noveltts.data.config.RulesImportPreview
import app.noveltts.data.config.ReadingProfile
import app.noveltts.data.config.ReadingProfiles
import app.noveltts.data.settings.SettingsStore
import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.RuleSet
import app.noveltts.domain.BookLibrary
import app.noveltts.data.config.BookLibraryJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate

/** 备份文件里的敏感凭据；默认不导出，必须由用户显式勾选。 */
data class BackupCredentials(
    val license: String? = null,
    val forwardToken: String? = null,
) {
    val isEmpty: Boolean
        get() = license.isNullOrBlank() && forwardToken.isNullOrBlank()
}

/** 解析后的备份文件。 */
data class ParsedBackup(
    val exportedAt: String,
    val appVersion: String,
    /** 重新组装成 [RulesImport] 认识的 `{schema:2, kind:"roles", groups, items}` 文档。 */
    val rolesJson: String,
    val replacementsJson: String,
    val roleCount: Int,
    val replacementCount: Int,
    /** 白名单内的设置项；未知 key 在解析时就被丢弃。 */
    val settings: Map<String, Any>,
    val credentials: BackupCredentials?,
    val profiles: List<ReadingProfile> = emptyList(),
    val voiceHints: Map<String, String> = emptyMap(),
    val includesProfiles: Boolean = true,
    val voiceOverrides: Map<String, app.noveltts.data.voicepack.VoiceOverride>? = null,
    val bookLibrary: BookLibrary? = null,
) {
    val settingsCount: Int get() = settings.size
}

/** 还原预览：冲突计算与阶段 2 的规则导入完全一致，复用的是同一套 data 层逻辑。 */
data class BackupRestorePreview(
    val backup: ParsedBackup,
    val roles: RulesImportPreview<RoleRule>,
    val replacements: RulesImportPreview<ReplacementRule>,
    val bookRevision: String? = null,
    val bookFingerprint: String? = null,
) {
    val roleNewCount: Int get() = roles.newCount
    val roleConflictCount: Int get() = roles.conflictCount
    val replacementNewCount: Int get() = replacements.newCount
    val replacementConflictCount: Int get() = replacements.conflictCount
    val hasConflict: Boolean get() = roles.hasConflict || replacements.hasConflict
    val isEmpty: Boolean get() = roles.entries.isEmpty() && replacements.entries.isEmpty() && backup.bookLibrary?.books.isNullOrEmpty()
}

/**
 * 应用备份的导出与解析。
 *
 * 这里只有纯 JVM 逻辑（没有 Context）：MainViewModel 负责 SAF 读写、落盘与回滚，
 * 单元测试可以直接调用 [exportJson] / [parse] / [previewRestore]。
 */
object BackupManager {
    /** 当前备份格式版本；还原时高于它的文件一律拒绝。 */
    const val SCHEMA = 2

    /** 备份文件上限，避免一次读进超大文件。 */
    const val MAX_FILE_BYTES = 4 * 1024 * 1024

    const val FILE_NAME_PREFIX = "noveltts-backup-"

    private const val RULES_SCHEMA = 2
    private const val MAX_DEPTH = 7
    private const val MAX_LICENSE_CHARS = 8192
    private const val MAX_TOKEN_CHARS = 512

    /** 可以随备份迁移的设置项；许可证与访问令牌单独放在 credentials 里。 */
    private enum class SettingType { BOOLEAN, INT, FLOAT, STRING }

    private val SETTINGS_WHITELIST: List<Pair<String, SettingType>> = listOf(
        "punctuationFilter" to SettingType.BOOLEAN,
        "keepAwake" to SettingType.BOOLEAN,
        "narratorVoice" to SettingType.STRING,
        "dialogueVoice" to SettingType.STRING,
        "systemVoice" to SettingType.STRING,
        "compatibilityProxy" to SettingType.BOOLEAN,
        "themeMode" to SettingType.STRING,
        "unattributedDialogue" to SettingType.STRING,
        "maxRetryCount" to SettingType.INT,
        "requestTimeoutSec" to SettingType.INT,
        "maxSegmentChars" to SettingType.INT,
        "silenceBetweenSegmentsMs" to SettingType.INT,
        "cacheEnabled" to SettingType.BOOLEAN,
        "cacheMaxMb" to SettingType.INT,
        "bgmEnabled" to SettingType.BOOLEAN,
        "bgmVolume" to SettingType.INT,
        "bgmLoop" to SettingType.BOOLEAN,
        "bgmDuck" to SettingType.BOOLEAN,
    ) + SettingsStore.BUILT_IN_MULTIPLIER_RANGES.keys.map { it to SettingType.FLOAT }

    /** 默认文件名：noveltts-backup-yyyyMMdd.json。 */
    fun exportFileName(date: LocalDate = LocalDate.now()): String =
        FILE_NAME_PREFIX + date.toString().replace("-", "") + ".json"

    /**
     * 生成备份 JSON。
     *
     * [settings] 里不在白名单内的 key（例如 bgmUri、forwardToken）会被丢弃；
     * [credentials] 为 null 时不会写 credentials 字段，敏感凭据默认不外泄。
     */
    fun exportJson(
        roleSet: RuleSet<RoleRule>,
        replacementSet: RuleSet<ReplacementRule>,
        settings: Map<String, Any?>,
        credentials: BackupCredentials? = null,
        exportedAt: Instant = Instant.now(),
        appVersion: String = "1.0.0",
        profiles: List<ReadingProfile> = emptyList(),
        voiceHints: Map<String, String> = emptyMap(),
        voiceOverrides: Map<String, app.noveltts.data.voicepack.VoiceOverride>? = null,
        bookLibrary: BookLibrary? = null,
    ): String {
        val roles = JSONObject(RulesImport.exportRoles(roleSet))
        val replacements = JSONObject(RulesImport.exportReplacements(replacementSet))
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("exportedAt", exportedAt.toString())
            .put("appVersion", appVersion)
            .put("roleGroups", copyArray(roles, "groups"))
            .put("roles", copyArray(roles, "items"))
            .put("replacementGroups", copyArray(replacements, "groups"))
            .put("replacements", copyArray(replacements, "items"))
            .put("settings", filteredSettings(settings))
            .put("profiles", ReadingProfiles.toArray(profiles))
            .put("voiceHints", JSONObject(voiceHints))
        if (voiceOverrides != null) root.put("voiceOverrides", JSONObject(app.noveltts.data.voicepack.VoiceOverrides.encode(voiceOverrides)))
        if (bookLibrary != null) root.put("bookLibrary", JSONObject(BookLibraryJson.encode(bookLibrary)))
        if (credentials != null && !credentials.isEmpty) {
            val json = JSONObject()
            credentials.license?.takeIf { it.isNotBlank() }?.let { json.put("license", it.take(MAX_LICENSE_CHARS)) }
            credentials.forwardToken?.takeIf { it.isNotBlank() }
                ?.let { json.put("forwardToken", it.take(MAX_TOKEN_CHARS)) }
            if (json.length() > 0) root.put("credentials", json)
        }
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "备份超过 4 MB 上限，请减少规则或方案后重试" } }
    }

    /**
     * 解析备份文件。
     *
     * @throws IllegalArgumentException 结构不合法、版本过高或内容超限时抛出。
     */
    fun parse(text: String): ParsedBackup {
        require(text.isNotBlank()) { "备份文件是空的" }
        require(text.length <= MAX_FILE_BYTES) { "备份文件超过 4 MB 上限" }
        JsonInput.checkDepth(text, MAX_DEPTH)
        val root = try {
            JSONObject(text.removePrefix("\uFEFF"))
        } catch (error: Exception) {
            throw IllegalArgumentException("不是有效的备份 JSON，请检查文件格式")
        }
        require(root.has("schema")) { "缺少 schema，这不是 NovelTTS 的备份文件" }
        val schema = root.optInt("schema", -1)
        require(schema >= 1) { "备份文件的 schema 无效：$schema" }
        require(schema <= SCHEMA) { "备份来自更新版本（schema $schema），当前仅支持 $SCHEMA" }

        val roles = root.optJSONArray("roles") ?: throw IllegalArgumentException("备份缺少 roles 数组")
        val replacements = root.optJSONArray("replacements")
            ?: throw IllegalArgumentException("备份缺少 replacements 数组")
        val roleGroups = root.optJSONArray("roleGroups") ?: JSONArray()
        val replacementGroups = root.optJSONArray("replacementGroups") ?: JSONArray()

        return ParsedBackup(
            exportedAt = root.stringOrDefault("exportedAt"),
            appVersion = root.stringOrDefault("appVersion"),
            rolesJson = rulesDocument("roles", roleGroups, roles),
            replacementsJson = rulesDocument("replacements", replacementGroups, replacements),
            roleCount = roles.length(),
            replacementCount = replacements.length(),
            settings = readSettings(root.optJSONObject("settings")),
            credentials = readCredentials(root.optJSONObject("credentials")),
            profiles = root.optJSONArray("profiles")?.let(ReadingProfiles::fromArray) ?: emptyList(),
            includesProfiles = root.has("profiles"),
            voiceOverrides = root.optJSONObject("voiceOverrides")?.let { app.noveltts.data.voicepack.VoiceOverrides.decode(it.toString()) },
            bookLibrary = if (root.has("bookLibrary")) BookLibraryJson.decode(root.getJSONObject("bookLibrary").toString()) else null,
            voiceHints = root.optJSONObject("voiceHints")?.let { hints ->
                hints.keys().asSequence().take(1000).associateWith { hints.stringOrDefault(it).take(160) } } ?: emptyMap(),
        )
    }

    fun missingVoiceIds(preview: BackupRestorePreview, usableIds: Set<String>, includeSettings: Boolean = true): List<String> = buildList {
        preview.roles.entries.forEach { addAll(it.rule.voiceIds) }
        if (includeSettings) listOf("narratorVoice", "dialogueVoice", "systemVoice").forEach { (preview.backup.settings[it] as? String)?.let(::add) }
        preview.backup.profiles.forEach { add(it.narratorVoice); add(it.dialogueVoice); add(it.systemVoice) }
        preview.backup.bookLibrary?.books?.forEach { book -> addAll(book.voicePool); book.characters.forEach { add(it.voiceId) } }
    }.filter { it.isNotBlank() && it !in usableIds }.distinct()

    fun remapVoices(preview: BackupRestorePreview, mapping: Map<String, String>): BackupRestorePreview {
        val roles = preview.roles.copy(entries = preview.roles.entries.map { entry ->
            entry.copy(rule = entry.rule.copy(voiceIds = entry.rule.voiceIds.map { mapping[it] ?: it }.distinct())) })
        val settings = preview.backup.settings.mapValues { (key, value) ->
            if (key in setOf("narratorVoice", "dialogueVoice", "systemVoice")) mapping[value] ?: value else value }
        return preview.copy(roles = roles, backup = preview.backup.copy(settings = settings,
            profiles = preview.backup.profiles.map { it.remapVoices(mapping) },
            bookLibrary = preview.backup.bookLibrary?.let { library -> library.copy(books = library.books.map { book ->
                book.copy(voicePool = book.voicePool.map { mapping[it] ?: it }.distinct(), characters = book.characters.map { character ->
                    character.copy(voiceId = mapping[character.voiceId] ?: character.voiceId)
                })
            }) }))
    }

    /** 用当前规则计算还原冲突，复用阶段 2 的 [RulesImport] 预览。 */
    fun previewRestore(
        backup: ParsedBackup,
        currentRoleGroups: List<RuleGroup>,
        currentRoles: List<RoleRule>,
        currentReplacementGroups: List<RuleGroup>,
        currentReplacements: List<ReplacementRule>,
        bookRevision: String? = null,
        bookFingerprint: String? = null,
    ): BackupRestorePreview = BackupRestorePreview(
        backup = backup,
        bookRevision = bookRevision,
        bookFingerprint = bookFingerprint,
        roles = RulesImport.previewRoles(backup.rolesJson, currentRoleGroups, currentRoles, externalImport = false),
        replacements = RulesImport.previewReplacements(
            backup.replacementsJson,
            currentReplacementGroups,
            currentReplacements,
            externalImport = false,
        ),
    )

    /** 读取上限内的 UTF-8 文本。 */
    fun readText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_FILE_BYTES) throw IllegalArgumentException("备份文件超过 4 MB 上限")
            output.write(chunk, 0, read)
        }
        return output.toString("UTF-8")
    }

    private fun copyArray(source: JSONObject, key: String): JSONArray =
        JSONArray(source.optJSONArray(key)?.toString() ?: "[]")

    private fun rulesDocument(kind: String, groups: JSONArray, items: JSONArray): String = JSONObject()
        .put("schema", RULES_SCHEMA)
        .put("kind", kind)
        .put("groups", JSONArray(groups.toString()))
        .put("items", JSONArray(items.toString()))
        .toString()

    private fun filteredSettings(values: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, type) in SETTINGS_WHITELIST) {
            val value = values[key] ?: continue
            val accepted: Any = when (type) {
                SettingType.BOOLEAN -> value as? Boolean ?: continue
                SettingType.INT -> (value as? Number)?.toInt() ?: continue
                SettingType.FLOAT -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: continue
                SettingType.STRING -> value as? String ?: continue
            }
            json.put(key, accepted)
        }
        return json
    }

    private fun readSettings(json: JSONObject?): Map<String, Any> {
        if (json == null) return emptyMap()
        val values = LinkedHashMap<String, Any>()
        for ((key, type) in SETTINGS_WHITELIST) {
            if (!json.has(key) || json.isNull(key)) continue
            when (type) {
                SettingType.BOOLEAN -> {
                    if (json.opt(key) is Boolean) values[key] = json.optBoolean(key)
                }

                SettingType.INT -> {
                    val number = json.opt(key) as? Number
                    if (number != null) values[key] = number.toInt()
                }

                SettingType.FLOAT -> {
                    val number = (json.opt(key) as? Number)?.toFloat()
                    val range = SettingsStore.BUILT_IN_MULTIPLIER_RANGES[key]
                    if (number != null && number.isFinite() && range != null) values[key] = number.coerceIn(range)
                }

                SettingType.STRING -> {
                    val text = json.opt(key) as? String
                    if (text != null) values[key] = text.take(MAX_LICENSE_CHARS)
                }
            }
        }
        return values
    }

    private fun readCredentials(json: JSONObject?): BackupCredentials? {
        if (json == null) return null
        val license = json.stringOrDefault("license").takeIf { it.isNotBlank() }?.take(MAX_LICENSE_CHARS)
        val token = json.stringOrDefault("forwardToken").takeIf { it.isNotBlank() }?.take(MAX_TOKEN_CHARS)
        if (license == null && token == null) return null
        return BackupCredentials(license, token)
    }
}
