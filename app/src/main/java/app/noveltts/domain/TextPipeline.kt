package app.noveltts.domain

/**
 * 小说文本 -> 有序合成片段。
 *
 * 顺序固定为：替换规则 -> 引号对话识别 -> 标点分句 -> 显式角色匹配 ->
 * 纯标点过滤。未匹配到的对话只使用默认对话音色，不做自动角色分配。
 */
object TextPipeline {
    /** 单段最大字符数的默认值；[build] 的 `maxSegmentChars` 可覆盖。 */
    const val DEFAULT_MAX_SEGMENT_CHARS = 500
    const val MIN_SEGMENT_CHARS = 80
    const val MAX_SEGMENT_LIMIT = 2_000
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
        maxSegmentChars: Int = DEFAULT_MAX_SEGMENT_CHARS,
    ): TextPipelineResult = build(
        text = text,
        builtInRoles = BuiltInRoleSettings().withVoices(narratorVoiceId, dialogueVoiceId),
        roles = roles,
        replacements = replacements,
        filterPurePunctuation = filterPurePunctuation,
        roleGroups = roleGroups,
        replacementGroups = replacementGroups,
        maxSegmentChars = maxSegmentChars,
    )

    fun build(
        text: String,
        builtInRoles: BuiltInRoleSettings,
        roles: List<RoleRule>,
        replacements: List<ReplacementRule>,
        filterPurePunctuation: Boolean,
        roleGroups: List<RuleGroup> = emptyList(),
        replacementGroups: List<RuleGroup> = emptyList(),
        maxSegmentChars: Int = DEFAULT_MAX_SEGMENT_CHARS,
    ): TextPipelineResult {
        val segmentLimit = maxSegmentChars.coerceIn(MIN_SEGMENT_CHARS, MAX_SEGMENT_LIMIT)
        val normalized = normalize(text)
        if (normalized.isBlank()) {
            return TextPipelineResult(segments = emptyList(), transformedText = "")
        }

        val issues = mutableListOf<PipelineIssue>()
        val traces = mutableListOf<ReplacementTrace>()
        val activeReplacements = activeReplacements(replacements, replacementGroups)
        val transformed = applyReplacements(
            normalized,
            activeReplacements.filter { it.scope == ReplacementScope.ALL },
            issues,
            traces,
        )
        val roleMatcher = RoleMatcher(activeRoles(roles, roleGroups), issues)
        val segments = mutableListOf<PipelineSegment>()

        val spans = quotedSpans(transformed)
        spans.forEachIndexed { spanIndex, span ->
            val scoped = applyReplacements(span.text, activeReplacements.filter {
                it.scope == if (span.isDialogue) ReplacementScope.DIALOGUE else ReplacementScope.NARRATION
            }, issues, traces)
            val previous = spans.take(spanIndex).lastOrNull { !it.isDialogue }?.text.orEmpty()
            val next = spans.drop(spanIndex + 1).firstOrNull { !it.isDialogue }?.text.orEmpty()
            sentenceChunks(scoped, segmentLimit).forEachIndexed chunkLoop@ { chunkIndex, chunk ->
                var cleaned = cleanupChunk(chunk)
                if (cleaned.isEmpty()) return@chunkLoop
                if (filterPurePunctuation && cleaned.none { it.isLetterOrDigit() }) return@chunkLoop

                val match = roleMatcher.match(cleaned, span.isDialogue, previous, next)
                val builtIn = if (span.isDialogue) builtInRoles.dialogue else builtInRoles.narrator
                val voiceId = match?.voiceId ?: builtIn.voiceId.takeIf { it.isNotBlank() }
                val role = match?.rule
                cleaned = applyReplacements(cleaned, activeReplacements.filter {
                    it.scope == ReplacementScope.ROLE && role != null && it.targetRoleId == role.id
                }, issues, traces)
                val finalChunks = sentenceChunks(cleaned, segmentLimit).map(::cleanupChunk).filter {
                    it.isNotBlank() && (!filterPurePunctuation || it.any(Char::isLetterOrDigit))
                }
                finalChunks.forEachIndexed { childIndex, finalText ->
                    segments += PipelineSegment(
                    text = finalText,
                    isDialogue = span.isDialogue,
                    voiceId = voiceId,
                    roleName = role?.name,
                    id = "segment:$spanIndex:$chunkIndex" + if (finalChunks.size > 1) ":$childIndex" else "",
                    roleId = role?.id,
                    speechRate = role?.speechRate ?: builtIn.rate,
                    speechPitch = role?.speechPitch ?: builtIn.pitch,
                    speechVolume = role?.speechVolume ?: builtIn.volume,
                )
                }
            }
        }

        return TextPipelineResult(
            segments = segments,
            transformedText = transformed,
            issues = issues,
            replacements = traces,
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
        traces: MutableList<ReplacementTrace>,
    ): String {
        var result = text
        replacements.forEach { rule ->
            if (rule.find.isEmpty()) return@forEach
            val validation = RuleValidation.replacementError(rule)
            if (validation != null) {
                if (issues.none { it.ruleId == rule.id }) issues += PipelineIssue("replacement", "「${rule.name}」$validation", rule.id)
                return@forEach
            }
            val before = result
            if (!rule.isRegex) {
                result = result.replace(rule.find, rule.replaceWith)
                if (before != result) traces += ReplacementTrace(rule.id, rule.name, before.take(200), result.take(200))
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
            result = runCatching { regex.replace(result, rule.replaceWith) }.getOrElse { error ->
                if (issues.none { it.ruleId == rule.id }) issues += PipelineIssue("replacement", "「${rule.name}」替换失败：${error.message}", rule.id)
                before
            }
            if (before != result) traces += ReplacementTrace(rule.id, rule.name, before.take(200), result.take(200))
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

    private fun sentenceChunks(text: String, limit: Int): List<String> {
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
        return chunks.flatMap { splitLongChunk(it, limit) }
    }

    private fun splitLongChunk(chunk: String, maxChars: Int): List<String> {
        var remaining = chunk
        val result = mutableListOf<String>()
        while (remaining.length > maxChars) {
            val limit = maxChars
            var split = -1
            for (index in limit - 1 downTo limit / 2) {
                if (remaining[index] in boundaries || remaining[index] in softBoundaries) {
                    split = index + 1
                    break
                }
            }
            if (split <= 0) split = limit
            if (split < remaining.length && remaining[split - 1].isHighSurrogate() && remaining[split].isLowSurrogate()) split--
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

        fun match(text: String, isDialogue: Boolean, previous: String, next: String): RoleMatch? {
            val index = rules.indices.firstOrNull { i ->
                val candidate = when (rules[i].matchScope) {
                    RoleMatchScope.SEGMENT -> text
                    RoleMatchScope.PREVIOUS_NARRATION -> previous.takeLast(rules[i].contextChars.coerceIn(1, 2000))
                    RoleMatchScope.NEXT_NARRATION -> next.take(rules[i].contextChars.coerceIn(1, 2000))
                }
                (isDialogue || !rules[i].onlyDialogue) && matches(i, candidate)
            } ?: return null
            return RoleMatch(rules[index], pickVoice(index))
        }

        private fun matches(index: Int, text: String): Boolean {
            val regex = regexes[index] ?: return false
            return when (rules[index].matchMode) {
                RoleMatchMode.REGEX_CONTAINS, RoleMatchMode.REGEX_SUFFIX -> regex.containsMatchIn(text)
                RoleMatchMode.REGEX_PREFIX -> regex.toPattern().matcher(text).lookingAt()
                RoleMatchMode.EXACT -> regex.matches(text)
                RoleMatchMode.LITERAL_EXACT -> text == rules[index].pattern
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
                RoleMatchMode.LITERAL_EXACT -> Regex.escape(rule.pattern)
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
