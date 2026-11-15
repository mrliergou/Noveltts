package app.noveltts.domain

/**
 * 小说文本 -> 有序合成片段。
 *
 * 顺序固定为：替换规则 -> 引号对话识别 -> 标点分句 -> 显式角色匹配 ->
 * 纯标点过滤。未匹配到的对话只使用默认对话音色，不做自动角色分配。
 */
object TextPipeline {
    private const val MAX_SEGMENT_CHARS = 500
    private val quotePairs = mapOf(
        '“' to '”',
        '「' to '」',
        '『' to '』',
        '"' to '"',
    )
    private val boundaries = setOf('。', '！', '？', '!', '?', '；', ';', '…')
    private val softBoundaries = setOf('，', ',', '、', '：', ':', ' ')

    fun build(
        text: String,
        narratorVoiceId: String?,
        dialogueVoiceId: String?,
        roles: List<RoleRule>,
        replacements: List<ReplacementRule>,
        filterPurePunctuation: Boolean,
        roleGroups: List<RuleGroup> = emptyList(),
        replacementGroups: List<RuleGroup> = emptyList(),
    ): TextPipelineResult {
        val normalized = normalize(text)
        if (normalized.isBlank()) {
            return TextPipelineResult(segments = emptyList(), transformedText = "")
        }

        val issues = mutableListOf<PipelineIssue>()
        val transformed = applyReplacements(
            normalized,
            activeReplacements(replacements, replacementGroups),
            issues,
        )
        val roleMatcher = RoleMatcher(activeRoles(roles, roleGroups), issues)
        val segments = mutableListOf<PipelineSegment>()

        quotedSpans(transformed).forEach { span ->
            sentenceChunks(span.text).forEach { chunk ->
                val cleaned = cleanupChunk(chunk)
                if (cleaned.isEmpty()) return@forEach
                if (filterPurePunctuation && cleaned.none { it.isLetterOrDigit() }) return@forEach

                val match = roleMatcher.match(cleaned, span.isDialogue)
                val voiceId = when {
                    match != null -> match.voiceId
                    span.isDialogue -> dialogueVoiceId?.takeIf { it.isNotBlank() }
                    else -> narratorVoiceId?.takeIf { it.isNotBlank() }
                }
                val role = match?.rule
                segments += PipelineSegment(
                    text = cleaned,
                    isDialogue = span.isDialogue,
                    voiceId = voiceId,
                    roleName = role?.name,
                )
            }
        }

        return TextPipelineResult(
            segments = segments,
            transformedText = transformed,
            issues = issues,
        )
    }

    fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        .replace(Regex("[\\t\\u000B\\u000C]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex(" {2,}"), " ")
        .trim()

    private fun applyReplacements(
        text: String,
        replacements: List<ReplacementRule>,
        issues: MutableList<PipelineIssue>,
    ): String {
        var result = text
        replacements.forEach { rule ->
            if (rule.find.isEmpty()) return@forEach
            if (!rule.isRegex) {
                result = result.replace(rule.find, rule.replaceWith)
                return@forEach
            }
            val regex = runCatching { Regex(rule.find) }.getOrElse { error ->
                issues += PipelineIssue(
                    field = "replacement",
                    message = "「${rule.name}」正则无效：${error.message}",
                    ruleId = rule.id,
                )
                return@forEach
            }
            result = regex.replace(result, rule.replaceWith)
        }
        return result
    }

    private fun quotedSpans(text: String): List<RawSpan> {
        val spans = mutableListOf<RawSpan>()
        var cursor = 0
        var dialogueStart = -1
        var closingQuote: Char? = null
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (dialogueStart < 0) {
                val close = quotePairs[char]
                if (close != null) {
                    if (index > cursor) {
                        spans += RawSpan(text.substring(cursor, index), isDialogue = false)
                    }
                    dialogueStart = index + 1
                    closingQuote = close
                }
                index++
                continue
            }

            if (char == closingQuote) {
                if (index > dialogueStart) {
                    spans += RawSpan(text.substring(dialogueStart, index), isDialogue = true)
                }
                cursor = index + 1
                dialogueStart = -1
                closingQuote = null
            }
            index++
        }
        if (dialogueStart >= 0) {
            spans += RawSpan(text.substring(dialogueStart), isDialogue = true)
            cursor = text.length
        }
        if (cursor < text.length) {
            spans += RawSpan(text.substring(cursor), isDialogue = false)
        }
        return spans.filter { it.text.isNotBlank() }
    }

    private fun sentenceChunks(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val boundary = char == '\n' ||
                char in boundaries ||
                (char == '.' && (index + 1 == text.length || text[index + 1].isWhitespace()))
            if (!boundary) {
                index++
                continue
            }
            val end = when {
                char == '\n' -> index
                char == '…' -> {
                    var cursor = index + 1
                    while (cursor < text.length && text[cursor] == '…') cursor++
                    cursor
                }
                else -> index + 1
            }
            if (end > start) chunks += text.substring(start, end)
            start = end
            index = end
            while (start < text.length && text[start].isWhitespace()) {
                start++
                index = start
            }
        }
        if (start < text.length) chunks += text.substring(start)
        return chunks.flatMap(::splitLongChunk)
    }

    private fun splitLongChunk(chunk: String): List<String> {
        var remaining = chunk
        val result = mutableListOf<String>()
        while (remaining.length > MAX_SEGMENT_CHARS) {
            val limit = MAX_SEGMENT_CHARS
            var split = -1
            for (index in limit - 1 downTo limit / 2) {
                if (remaining[index] in boundaries || remaining[index] in softBoundaries) {
                    split = index + 1
                    break
                }
            }
            if (split <= 0) split = limit
            result += remaining.substring(0, split).trim()
            remaining = remaining.substring(split).trimStart()
        }
        if (remaining.isNotEmpty()) result += remaining
        return result.filter { it.isNotBlank() }
    }

    private fun cleanupChunk(text: String): String =
        text.trim().trim('“', '”', '「', '」', '『', '』', '"').trim()

    /** 启用且所在分组未停用的替换规则，按 (分组 order, sortIndex) 排序。未知分组按默认分组处理。 */
    private fun activeReplacements(
        rules: List<ReplacementRule>,
        groups: List<RuleGroup>,
    ): List<ReplacementRule> {
        val byId = groups.associateBy { it.id }
        return rules
            .filter { it.enabled && byId[it.groupId]?.enabled != false }
            .sortedWith(
                compareBy<ReplacementRule> { byId[it.groupId]?.order ?: RuleGroup.DEFAULT.order }
                    .thenBy { it.sortIndex },
            )
    }

    /** 启用、所在分组未停用且至少有一个音色的角色规则，按优先级降序，再按 (分组 order, sortIndex)。 */
    private fun activeRoles(rules: List<RoleRule>, groups: List<RuleGroup>): List<RoleRule> {
        val byId = groups.associateBy { it.id }
        return rules
            .filter { it.enabled && it.primaryVoiceId != null && byId[it.groupId]?.enabled != false }
            .sortedWith(
                compareByDescending<RoleRule> { it.priority }
                    .thenBy { byId[it.groupId]?.order ?: RuleGroup.DEFAULT.order }
                    .thenBy { it.sortIndex },
            )
    }

    private class RoleMatch(val rule: RoleRule, val voiceId: String)

    /**
     * 单次 [build] 内的角色匹配器。正则在构造时一次性编译（无效正则只报告一次），
     * 轮转计数按规则在列表中的位置记录，不依赖 id 唯一。
     */
    private class RoleMatcher(
        private val rules: List<RoleRule>,
        issues: MutableList<PipelineIssue>,
    ) {
        private val regexes: List<Regex?> = rules.map { compile(it, issues) }
        private val hits = IntArray(rules.size)

        fun match(text: String, isDialogue: Boolean): RoleMatch? {
            val index = rules.indices.firstOrNull { i ->
                (isDialogue || !rules[i].onlyDialogue) && matches(i, text)
            } ?: return null
            return RoleMatch(rules[index], pickVoice(index))
        }

        private fun matches(index: Int, text: String): Boolean {
            val regex = regexes[index] ?: return false
            return when (rules[index].matchMode) {
                RoleMatchMode.REGEX_CONTAINS, RoleMatchMode.REGEX_SUFFIX -> regex.containsMatchIn(text)
                RoleMatchMode.REGEX_PREFIX -> regex.toPattern().matcher(text).lookingAt()
                RoleMatchMode.EXACT -> regex.matches(text)
            }
        }

        private fun pickVoice(index: Int): String {
            val rule = rules[index]
            val voices = rule.voiceIds.filter { it.isNotBlank() }
            if (rule.voiceSelectionMode == VoiceSelectionMode.FIXED || voices.size == 1) return voices.first()
            return voices[hits[index]++ % voices.size]
        }

        private fun compile(rule: RoleRule, issues: MutableList<PipelineIssue>): Regex? {
            val source = when (rule.matchMode) {
                RoleMatchMode.REGEX_SUFFIX -> "(?:${rule.pattern})\\z"
                else -> rule.pattern
            }
            return runCatching { Regex(source) }.getOrElse { error ->
                issues += PipelineIssue(
                    field = "role",
                    message = "「${rule.name}」正则无效：${error.message}",
                    ruleId = "role:${rule.id}",
                )
                null
            }
        }
    }

    private data class RawSpan(val text: String, val isDialogue: Boolean)
}
