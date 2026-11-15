package app.noveltts.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPipelineTest {
    @Test
    fun separatesNarrationAndChineseQuotes() {
        val result = TextPipeline.build(
            text = "小明说：“你好。”\n“我是小红！”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = emptyList(),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        assertEquals(listOf(false, true, true), result.segments.map { it.isDialogue })
        assertEquals(listOf("narrator", "dialogue", "dialogue"), result.segments.map { it.voiceId })
        assertEquals("你好。", result.segments[1].text)
    }

    @Test
    fun explicitRoleWinsByPriority() {
        val result = TextPipeline.build(
            text = "“我是小红！”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(
                RoleRule(name = "普通", pattern = "小红", voiceIds = listOf("role-low"), priority = 1),
                RoleRule(name = "主角", pattern = "小红", voiceIds = listOf("role-high"), priority = 99),
            ),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        assertEquals("role-high", result.segments.single().voiceId)
        assertEquals("主角", result.segments.single().roleName)
    }

    @Test
    fun appliesStringAndRegexReplacementsInOrder() {
        val result = TextPipeline.build(
            text = "我今年18岁。",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = emptyList(),
            replacements = listOf(
                ReplacementRule(
                    name = "年龄",
                    find = "(\\d+)岁",
                    replaceWith = "$1 周岁",
                    isRegex = true,
                    order = 1,
                ),
                ReplacementRule(
                    name = "数字",
                    find = "18",
                    replaceWith = "十八",
                    order = 2,
                ),
            ),
            filterPurePunctuation = true,
        )

        assertEquals("我今年十八 周岁。", result.transformedText)
    }

    @Test
    fun purePunctuationCanBeFiltered() {
        val enabled = TextPipeline.build(
            text = "……",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = emptyList(),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )
        val disabled = TextPipeline.build(
            text = "……",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = emptyList(),
            replacements = emptyList(),
            filterPurePunctuation = false,
        )

        assertTrue(enabled.segments.isEmpty())
        assertEquals(1, disabled.segments.size)
    }

    @Test
    fun roundRobinCyclesVoicesWithinOneBuild() {
        val result = TextPipeline.build(
            text = "“小红。”“小红！”“小红？”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(
                RoleRule(
                    name = "小红",
                    pattern = "小红",
                    voiceIds = listOf("voice-a", "voice-b"),
                    voiceSelectionMode = VoiceSelectionMode.ROUND_ROBIN,
                ),
            ),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        assertEquals(listOf("voice-a", "voice-b", "voice-a"), result.segments.map { it.voiceId })
    }

    @Test
    fun invalidRegexIsReportedOnceAcrossSegments() {
        val result = TextPipeline.build(
            text = "“甲。”“乙。”“丙。”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(RoleRule(name = "坏", pattern = "(", voiceIds = listOf("v"))),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        assertEquals(1, result.issues.size)
        assertEquals(listOf("dialogue", "dialogue", "dialogue"), result.segments.map { it.voiceId })
    }

    @Test
    fun rulesSharingAnIdKeepSeparateRegexAndRotation() {
        val result = TextPipeline.build(
            text = "“小红。”“小绿。”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(
                RoleRule(id = "dup", name = "红", pattern = "小红", voiceIds = listOf("red")),
                RoleRule(id = "dup", name = "绿", pattern = "小绿", voiceIds = listOf("green")),
            ),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        assertEquals(listOf("red", "green"), result.segments.map { it.voiceId })
    }

    @Test
    fun unknownGroupSortsLikeDefaultGroup() {
        val result = TextPipeline.build(
            text = "A",
            narratorVoiceId = null,
            dialogueVoiceId = null,
            roles = emptyList(),
            replacements = listOf(
                ReplacementRule(name = "后", find = "B", replaceWith = "C", groupId = "late"),
                ReplacementRule(name = "未知", find = "A", replaceWith = "B", groupId = "ghost"),
            ),
            filterPurePunctuation = false,
            replacementGroups = listOf(RuleGroup.DEFAULT, RuleGroup(id = "late", name = "后", order = 200)),
        )

        assertEquals("C", result.transformedText)
    }

    @Test
    fun fixedModeAlwaysUsesFirstVoice() {
        val result = TextPipeline.build(
            text = "“小红。”“小红！”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(RoleRule(name = "小红", pattern = "小红", voiceIds = listOf("voice-a", "voice-b"))),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        assertEquals(listOf("voice-a", "voice-a"), result.segments.map { it.voiceId })
    }

    @Test
    fun disabledRoleGroupDisablesItsRules() {
        val result = TextPipeline.build(
            text = "“小红。”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(RoleRule(name = "小红", pattern = "小红", voiceIds = listOf("role"), groupId = "off")),
            replacements = emptyList(),
            filterPurePunctuation = true,
            roleGroups = listOf(RuleGroup(id = "off", name = "停用", enabled = false)),
        )

        assertEquals("dialogue", result.segments.single().voiceId)
    }

    @Test
    fun ruleWithoutVoiceIsSkipped() {
        val result = TextPipeline.build(
            text = "“小红。”",
            narratorVoiceId = "narrator",
            dialogueVoiceId = "dialogue",
            roles = listOf(RoleRule(name = "小红", pattern = "小红", voiceIds = listOf(""))),
            replacements = emptyList(),
            filterPurePunctuation = true,
        )

        assertEquals("dialogue", result.segments.single().voiceId)
        assertEquals(null, result.segments.single().roleName)
    }

    @Test
    fun replacementGroupOrderAndEnabledStateControlExecution() {
        val result = TextPipeline.build(
            text = "A",
            narratorVoiceId = null,
            dialogueVoiceId = null,
            roles = emptyList(),
            replacements = listOf(
                ReplacementRule(name = "后组", find = "B", replaceWith = "C", groupId = "later"),
                ReplacementRule(name = "前组", find = "A", replaceWith = "B", groupId = "earlier"),
                ReplacementRule(name = "停用组", find = "C", replaceWith = "D", groupId = "off"),
            ),
            filterPurePunctuation = false,
            replacementGroups = listOf(
                RuleGroup(id = "later", name = "后", order = 2),
                RuleGroup(id = "earlier", name = "前", order = 1),
                RuleGroup(id = "off", name = "停用", enabled = false, order = 0),
            ),
        )

        assertEquals("C", result.transformedText)
    }

    @Test
    fun supportsPrefixSuffixAndExactRoleMatching() {
        fun voiceFor(pattern: String, mode: RoleMatchMode, text: String): String? =
            TextPipeline.build(
                text = "“$text”",
                narratorVoiceId = "narrator",
                dialogueVoiceId = "dialogue",
                roles = listOf(RoleRule(name = "匹配", pattern = pattern, voiceIds = listOf("hit"), matchMode = mode)),
                replacements = emptyList(),
                filterPurePunctuation = true,
            ).segments.single().voiceId

        assertEquals("hit", voiceFor("我是", RoleMatchMode.REGEX_PREFIX, "我是小红"))
        assertEquals("dialogue", voiceFor("小红", RoleMatchMode.REGEX_PREFIX, "我是小红"))
        assertEquals("hit", voiceFor("小红|小绿", RoleMatchMode.REGEX_SUFFIX, "我是小红"))
        assertEquals("dialogue", voiceFor("我是", RoleMatchMode.REGEX_SUFFIX, "我是小红"))
        assertEquals("hit", voiceFor("我是小红", RoleMatchMode.EXACT, "我是小红"))
        assertEquals("dialogue", voiceFor("小红", RoleMatchMode.EXACT, "我是小红"))
        assertEquals("hit", voiceFor("小红", RoleMatchMode.REGEX_CONTAINS, "我是小红呀"))
    }
}
