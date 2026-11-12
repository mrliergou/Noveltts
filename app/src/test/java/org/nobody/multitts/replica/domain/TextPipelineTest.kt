package org.nobody.multitts.replica.domain

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
                RoleRule(name = "普通", pattern = "小红", voiceId = "role-low", priority = 1),
                RoleRule(name = "主角", pattern = "小红", voiceId = "role-high", priority = 99),
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
}
