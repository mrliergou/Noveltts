package app.noveltts.data.config

import app.noveltts.domain.ReplacementRule
import app.noveltts.domain.RoleMatchMode
import app.noveltts.domain.RoleRule
import app.noveltts.domain.RuleGroup
import app.noveltts.domain.RuleSet
import app.noveltts.domain.TextPipeline
import app.noveltts.domain.VoiceSelectionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RulesRepositoryMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = temporaryFolder.newFolder("config")
    }

    private fun file(name: String) = File(directory, name)

    private val v1Roles = """
        {"schema":1,"items":[
          {"id":"low","name":"路人","pattern":"小红","voiceId":"voice-low","priority":1},
          {"id":"high","name":"主角","pattern":"小红","voiceId":"voice-high","priority":99,"onlyDialogue":false},
          {"id":"mid","name":"配角","pattern":"小绿","voiceId":"voice-mid","priority":50,"enabled":false}
        ]}
    """.trimIndent()

    @Test
    fun migratesV1RolesPreservingCountOrderAndMatches() {
        file("roles.json").writeText(v1Roles)
        val text = "小红走来。“小红在这里！”“小绿来了。”"
        val legacy = TextPipeline.build(
            text = text,
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(
                RoleRule(id = "low", name = "路人", pattern = "小红", voiceIds = listOf("voice-low"), priority = 1),
                RoleRule(
                    id = "high", name = "主角", pattern = "小红", voiceIds = listOf("voice-high"),
                    priority = 99, onlyDialogue = false,
                ),
                RoleRule(
                    id = "mid", name = "配角", pattern = "小绿", voiceIds = listOf("voice-mid"),
                    priority = 50, enabled = false,
                ),
            ),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        val roles = RulesRepository(directory).loadRoles()

        assertEquals(listOf("high", "mid", "low"), roles.items.map { it.id })
        assertEquals(mapOf("high" to 0, "mid" to 1, "low" to 2), roles.items.associate { it.id to it.sortIndex })
        assertTrue(roles.items.all { it.groupId == RuleGroup.DEFAULT_ID })
        assertTrue(roles.items.all { it.matchMode == RoleMatchMode.REGEX_CONTAINS })
        assertTrue(roles.items.all { it.voiceSelectionMode == VoiceSelectionMode.FIXED })
        assertEquals(listOf(RuleGroup.DEFAULT), roles.groups)

        val migrated = TextPipeline.build(
            text = text,
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = roles.items,
            replacements = emptyList(),
            filterPurePunctuation = true,
            roleGroups = roles.groups,
        )
        assertEquals(legacy.segments, migrated.segments)
    }

    @Test
    fun mapsSingleVoiceIdToVoiceIdsList() {
        file("roles.json").writeText(
            """{"schema":1,"items":[
                {"id":"a","name":"甲","pattern":"甲","voiceId":"multitts:pack:edge:zh-CN-XiaoxiaoNeural"},
                {"id":"b","name":"乙","pattern":"乙","voiceId":""}
            ]}""",
        )

        val roles = RulesRepository(directory).loadRoles()

        assertEquals(listOf("multitts:pack:edge:zh-CN-XiaoxiaoNeural"), roles.items.first { it.id == "a" }.voiceIds)
        assertEquals(emptyList<String>(), roles.items.first { it.id == "b" }.voiceIds)
    }

    @Test
    fun migrationWritesV2AndKeepsV1InBackup() {
        file("roles.json").writeText(v1Roles)

        RulesRepository(directory).loadRoles()

        val written = JSONObject(file("roles.json").readText())
        assertEquals(2, written.getInt("schema"))
        assertEquals("default", written.getJSONArray("groups").getJSONObject(0).getString("id"))
        assertEquals(v1Roles, file("roles.json.bak").readText())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun migratesV1ReplacementsBySortedOrder() {
        file("replacements.json").writeText(
            """{"items":[
                {"id":"second","name":"二","find":"B","replaceWith":"C","order":20},
                {"id":"first","name":"一","find":"A","replaceWith":"B","order":10}
            ]}""",
        )

        val set = RulesRepository(directory).loadReplacements()

        assertEquals(mapOf("first" to 0, "second" to 1), set.items.associate { it.id to it.sortIndex })
        val output = TextPipeline.build(
            text = "A",
            narratorVoiceId = null,
            dialogueVoiceId = null,
            roles = emptyList(),
            replacements = set.items,
            filterPurePunctuation = false,
            replacementGroups = set.groups,
        ).transformedText
        assertEquals("C", output)
    }

    @Test
    fun duplicateIdsFromV1NameFallbackAreMadeUnique() {
        file("roles.json").writeText(
            """{"schema":1,"items":[
                {"name":"小红","pattern":"小红","voiceId":"a","priority":2},
                {"name":"小红","pattern":"红姐","voiceId":"b","priority":1}
            ]}""",
        )

        val roles = RulesRepository(directory).loadRoles()

        assertEquals(listOf("小红", "小红#2"), roles.items.map { it.id })
        assertEquals(listOf("小红", "小红#2"), RulesRepository(directory).loadRoles().items.map { it.id })
    }

    @Test
    fun unknownOrMissingGroupFallsBackToDefault() {
        file("roles.json").writeText(
            """{"schema":2,"groups":[{"id":"cast","name":"主要角色"}],"items":[
                {"id":"x","name":"甲","pattern":"甲","voiceIds":["v"],"groupId":"ghost"},
                {"id":"y","name":"乙","pattern":"乙","voiceIds":["v"]},
                {"id":"z","name":"丙","pattern":"丙","voiceIds":["v"],"groupId":"cast"}
            ]}""",
        )

        val roles = RulesRepository(directory).loadRoles()

        assertEquals(
            mapOf("x" to RuleGroup.DEFAULT_ID, "y" to RuleGroup.DEFAULT_ID, "z" to "cast"),
            roles.items.associate { it.id to it.groupId },
        )
        assertEquals(listOf(RuleGroup.DEFAULT_ID, "cast"), roles.groups.map { it.id })
    }

    @Test
    fun corruptJsonThrowsAndPreservesOriginal() {
        val broken = """{"schema":2,"items":[{"id":"""
        file("roles.json").writeText(broken)
        val repository = RulesRepository(directory)

        assertThrows(RulesStoreException::class.java) { repository.loadRoles() }

        assertEquals(broken, file("roles.json").readText())
        assertEquals(broken, file("roles.json.corrupt").readText())
        assertFalse(file("roles.json.bak").exists())
    }

    @Test
    fun missingItemsArrayIsTreatedAsCorrupt() {
        file("replacements.json").writeText("""{"schema":2,"items":"oops"}""")

        assertThrows(RulesStoreException::class.java) { RulesRepository(directory).loadReplacements() }
        assertTrue(file("replacements.json.corrupt").exists())
    }

    @Test
    fun nonObjectItemIsTreatedAsCorruptInsteadOfDropped() {
        val contents = """{"schema":1,"items":[{"id":"a","name":"甲","pattern":"甲","voiceId":"v"},"oops"]}"""
        file("roles.json").writeText(contents)

        assertThrows(RulesStoreException::class.java) { RulesRepository(directory).loadRoles() }
        assertEquals(contents, file("roles.json").readText())
        assertFalse(file("roles.json.bak").exists())
    }

    @Test
    fun futureSchemaIsRejectedWithoutTouchingFile() {
        val future = """{"schema":3,"groups":[],"items":[]}"""
        file("roles.json").writeText(future)

        assertThrows(RulesStoreException::class.java) { RulesRepository(directory).loadRoles() }

        assertEquals(future, file("roles.json").readText())
        assertFalse(file("roles.json.bak").exists())
        assertFalse(file("roles.json.corrupt").exists())
    }

    @Test
    fun missingFilesYieldEmptySetWithDefaultGroup() {
        val repository = RulesRepository(directory)

        assertEquals(RuleSet<RoleRule>(), repository.loadRoles())
        assertEquals(RuleSet<ReplacementRule>(), repository.loadReplacements())
        assertFalse(file("roles.json").exists())
    }

    @Test
    fun roundTripsV2FieldsAndWritesLegacyVoiceId() {
        val repository = RulesRepository(directory)
        val group = RuleGroup(id = "cast", name = "主要角色", enabled = false, order = 7)
        val role = RoleRule(
            id = "r",
            name = "主角",
            pattern = "小红",
            voiceIds = listOf("voice-a", "voice-b"),
            priority = 80,
            onlyDialogue = false,
            enabled = false,
            groupId = group.id,
            matchMode = RoleMatchMode.REGEX_SUFFIX,
            voiceSelectionMode = VoiceSelectionMode.ROUND_ROBIN,
            sortIndex = 4,
        )
        val replacement = ReplacementRule(
            id = "p", name = "替换", find = "旧", replaceWith = "新",
            isRegex = true, enabled = false, order = 8, sortIndex = 2, groupId = group.id,
        )

        repository.saveRoles(RuleSet(listOf(group), listOf(role)))
        repository.saveReplacements(RuleSet(listOf(group), listOf(replacement)))

        val roles = repository.loadRoles()
        assertEquals(listOf(role), roles.items)
        assertEquals(listOf(RuleGroup.DEFAULT, group), roles.groups)
        assertEquals(listOf(replacement), repository.loadReplacements().items)
        val stored = JSONObject(file("roles.json").readText()).getJSONArray("items").getJSONObject(0)
        assertEquals("voice-a", stored.getString("voiceId"))
    }

    @Test
    fun saveKeepsPreviousVersionInBackup() {
        val repository = RulesRepository(directory)
        val first = RoleRule(id = "a", name = "甲", pattern = "甲", voiceIds = listOf("v1"))
        repository.saveRoles(RuleSet(items = listOf(first)))
        repository.saveRoles(RuleSet(items = listOf(first.copy(name = "甲改"))))

        val backup = JSONObject(file("roles.json.bak").readText())
        assertEquals("甲", backup.getJSONArray("items").getJSONObject(0).getString("name"))
        assertEquals("甲改", repository.loadRoles().items.single().name)
    }
}
