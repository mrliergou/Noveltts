package app.noveltts.domain

/**
 * 小说文本 -> 有序合成片段。
 *
 * 顺序固定为：替换规则 -> 按段落识别引号对话 -> 说话人归属 -> 标点分句 -> 角色规则匹配 ->
 * 纯标点过滤。说话人只在用户建好的角色里判定，判定不了的对话使用默认对话音色。
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
        unattributedPolicy: UnattributedPolicy = UnattributedPolicy.DEFAULT,
    ): TextPipelineResult = build(
        text = text,
        builtInRoles = BuiltInRoleSettings().withVoices(narratorVoiceId, dialogueVoiceId),
        roles = roles,
        replacements = replacements,
        filterPurePunctuation = filterPurePunctuation,
        roleGroups = roleGroups,
        replacementGroups = replacementGroups,
        maxSegmentChars = maxSegmentChars,
        unattributedPolicy = unattributedPolicy,
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
        unattributedPolicy: UnattributedPolicy = UnattributedPolicy.DEFAULT,
    ): TextPipelineResult {
        if (text.length > 1_000_000) throw PipelineLimitException("输入文本超过上限（1000000 字符）")
        return requireNotNull(RuleRuntime.execute(RuleWork(text, builtInRoles, roles.map { it.copy(voiceIds = it.voiceIds.toList()) }, replacements.toList(),
            filterPurePunctuation, roleGroups.toList(), replacementGroups.toList(), maxSegmentChars, unattributedPolicy)).pipeline)
    }

    internal fun buildLocal(
        text: String, builtInRoles: BuiltInRoleSettings, roles: List<RoleRule>, replacements: List<ReplacementRule>,
        filterPurePunctuation: Boolean, roleGroups: List<RuleGroup>, replacementGroups: List<RuleGroup>,
        maxSegmentChars: Int, unattributedPolicy: UnattributedPolicy,
    ): TextPipelineResult {
        if (text.length > 1_000_000) throw PipelineLimitException("输入文本超过上限（1000000 字符）")
        val budget = ReplacementBudget()
        val segmentLimit = maxSegmentChars.coerceIn(MIN_SEGMENT_CHARS, MAX_SEGMENT_LIMIT)
        val normalized = normalize(text)
        if (normalized.isBlank()) {
            return TextPipelineResult(segments = emptyList(), transformedText = "")
        }

        val issues = mutableListOf<PipelineIssue>()
        val traces = mutableListOf<ReplacementTrace>()
        val activeReplacements = activeReplacements(replacements, replacementGroups)
        activeReplacements.filter { it.scope == ReplacementScope.ROLE && roles.none { role -> role.id == it.targetRoleId } }
            .forEach { issues += PipelineIssue("replacement", "「${it.name}」目标角色缺失，已跳过；原引用已保留", it.id) }
        val transformed = applyReplacements(
            normalized,
            activeReplacements.filter { it.scope == ReplacementScope.ALL },
            issues,
            traces,
            budget,
        )
        val (autoRoles, scopedRoles) = activeRoles(roles, roleGroups).partition { it.matchScope == RoleMatchScope.AUTO }
        val scopedMatcher = RoleMatcher(scopedRoles, issues)
        val autoMatcher = RoleMatcher(autoRoles, issues)
        val attributor = SpeakerAttributor(autoMatcher.regexes, unattributedPolicy)
        val segments = mutableListOf<PipelineSegment>()

        val paragraphs = SpeakerAttributor.paragraphs(transformed, quotePairs)
        val units = paragraphs.flatten()
        val decisions = paragraphs.flatMap(attributor::paragraph)
        val context = NarrationContext(units)
        units.forEachIndexed { unitIndex, unit ->
            val scoped = applyReplacements(unit.text, activeReplacements.filter {
                it.scope == if (unit.isDialogue) ReplacementScope.DIALOGUE else ReplacementScope.NARRATION
            }, issues, traces, budget)
            val decision = decisions[unitIndex]
            val autoMatch = decision?.roleIndex?.takeIf { it >= 0 }?.let(autoMatcher::pick)
            val previous = context.previous(unitIndex)
            val next = context.next(unitIndex)
            sentenceChunks(scoped, segmentLimit).forEachIndexed chunkLoop@ { chunkIndex, chunk ->
                var cleaned = cleanupChunk(chunk)
                if (cleaned.isEmpty()) return@chunkLoop
                if (filterPurePunctuation && cleaned.none { it.isLetterOrDigit() }) return@chunkLoop

                val scopedMatch = if (autoMatch == null) scopedMatcher.match(cleaned, unit.isDialogue, previous, next) else null
                val match = autoMatch ?: scopedMatch
                val attribution = if (scopedMatch != null) Attribution.LEGACY_RULE else decision?.attribution
                val builtIn = if (unit.isDialogue) builtInRoles.dialogue else builtInRoles.narrator
                val voiceId = match?.voiceId ?: builtIn.voiceId.takeIf { it.isNotBlank() }
                val role = match?.rule
                cleaned = applyReplacements(cleaned, activeReplacements.filter {
                    it.scope == ReplacementScope.ROLE && role != null && it.targetRoleId == role.id
                }, issues, traces, budget)
                val finalChunks = sentenceChunks(cleaned, segmentLimit).map(::cleanupChunk).filter {
                    it.isNotBlank() && (!filterPurePunctuation || it.any(Char::isLetterOrDigit))
                }
                finalChunks.forEachIndexed { childIndex, finalText ->
                    segments += PipelineSegment(
                    text = finalText,
                    isDialogue = unit.isDialogue,
                    voiceId = voiceId,
                    roleName = role?.name,
                    id = "segment:$unitIndex:$chunkIndex" + if (finalChunks.size > 1) ":$childIndex" else "",
                    roleId = role?.id,
                    speechRate = role?.speechRate ?: builtIn.rate,
                    speechPitch = role?.speechPitch ?: builtIn.pitch,
                    speechVolume = role?.speechVolume ?: builtIn.volume,
                    attribution = attribution,
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
        budget: ReplacementBudget,
    ): String {
        var result = text
        replacements.forEach { rule ->
            if (rule.find.isEmpty()) return@forEach
            val validation = budget.validationError(rule)
            if (validation != null) {
                if (issues.none { it.ruleId == rule.id }) issues += PipelineIssue("replacement", "「${rule.name}」$validation", rule.id)
                return@forEach
            }
            val before = result
            result = try { budget.replace(before, rule) }
            catch (error: PipelineLimitException) { throw error }
            catch (error: Exception) {
                if (issues.none { it.ruleId == rule.id }) issues += PipelineIssue("replacement", "「${rule.name}」替换失败", rule.id)
                before
            }
            if (before != result) traces += ReplacementTrace(rule.id, rule.name, before.take(200), result.take(200))
        }
        return result
    }
    /**
     * 非 AUTO 范围的角色规则使用的前后文：相邻旁白（含跨段）并成一块，
     * 「前一句旁白」「后一句旁白」分别取最近的一整块，与引入 AUTO 之前的行为一致。
     */
    private class NarrationContext(units: List<SpeechUnit>) {
        private val blockOf = IntArray(units.size)
        private val blocks = mutableListOf<String?>()

        init {
            val builders = mutableListOf<StringBuilder?>()
            units.forEachIndexed { index, unit ->
                if (index > 0 && !unit.isDialogue && !units[index - 1].isDialogue) {
                    builders.last()!!.append('\n').append(unit.text)
                } else {
                    builders += if (unit.isDialogue) null else StringBuilder(unit.text)
                }
                blockOf[index] = builders.lastIndex
            }
            blocks.addAll(builders.map { it?.toString() })
        }

        fun previous(unitIndex: Int): String =
            (blockOf[unitIndex] - 1 downTo 0).firstNotNullOfOrNull { blocks[it] }.orEmpty()

        fun next(unitIndex: Int): String =
            (blockOf[unitIndex] + 1 until blocks.size).firstNotNullOfOrNull { blocks[it] }.orEmpty()
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
        var start = 0
        val result = mutableListOf<String>()
        while (chunk.length - start > maxChars) {
            val limit = start + maxChars
            var split = -1
            for (index in limit - 1 downTo start + maxChars / 2) {
                if (chunk[index] in boundaries || chunk[index] in softBoundaries) {
                    split = index + 1
                    break
                }
            }
            if (split <= start) split = limit
            if (split < chunk.length && chunk[split - 1].isHighSurrogate() && chunk[split].isLowSurrogate()) split--
            result += chunk.substring(start, split).trim()
            start = split
            while (start < chunk.length && chunk[start].isWhitespace()) start++
        }
        if (start < chunk.length) result += chunk.substring(start)
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
        val regexes: List<Regex?> = rules.map { compile(it, issues) }
        private val hits = IntArray(rules.size)

        fun pick(index: Int): RoleMatch = RoleMatch(rules[index], pickVoice(index))

        fun match(text: String, isDialogue: Boolean, previous: String, next: String): RoleMatch? {
            val index = rules.indices.firstOrNull { i ->
                val candidate = when (rules[i].matchScope) {
                    RoleMatchScope.SEGMENT -> text
                    RoleMatchScope.PREVIOUS_NARRATION -> previous.takeLast(rules[i].contextChars.coerceIn(1, 2000))
                    RoleMatchScope.NEXT_NARRATION -> next.take(rules[i].contextChars.coerceIn(1, 2000))
                    RoleMatchScope.AUTO -> return@firstOrNull false
                }
                (isDialogue || !rules[i].onlyDialogue) && matches(i, candidate)
            } ?: return null
            return pick(index)
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
            val source = when {
                // AUTO 只在旁白里找称呼，前缀/后缀/整段这些片段级模式对它没有意义。
                rule.matchScope == RoleMatchScope.AUTO && rule.matchMode != RoleMatchMode.LITERAL_EXACT -> rule.pattern
                rule.matchMode == RoleMatchMode.REGEX_SUFFIX -> "(?:${rule.pattern})\\z"
                rule.matchMode == RoleMatchMode.LITERAL_EXACT -> Regex.escape(rule.pattern)
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
}
