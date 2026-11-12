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
    ): TextPipelineResult {
        val normalized = normalize(text)
        if (normalized.isBlank()) {
            return TextPipelineResult(segments = emptyList(), transformedText = "")
        }

        val issues = mutableListOf<PipelineIssue>()
        val transformed = applyReplacements(normalized, replacements, issues)
        val segments = mutableListOf<PipelineSegment>()

        quotedSpans(transformed).forEach { span ->
            sentenceChunks(span.text).forEach { chunk ->
                val cleaned = cleanupChunk(chunk)
                if (cleaned.isEmpty()) return@forEach
                if (filterPurePunctuation && cleaned.none { it.isLetterOrDigit() }) return@forEach

                val role = if (span.isDialogue) {
                    matchRole(cleaned, roles, issues)
                } else {
                    matchNarrationRole(cleaned, roles, issues)
                }
                val voiceId = when {
                    !role?.voiceId.isNullOrBlank() -> role?.voiceId
                    span.isDialogue -> dialogueVoiceId?.takeIf { it.isNotBlank() }
                    else -> narratorVoiceId?.takeIf { it.isNotBlank() }
                }
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
        replacements.filter { it.enabled }.sortedBy { it.order }.forEach { rule ->
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

    private fun matchRole(
        text: String,
        rules: List<RoleRule>,
        issues: MutableList<PipelineIssue>,
    ): RoleRule? = rules
        .filter { it.enabled && it.voiceId.isNotBlank() }
        .sortedWith(compareByDescending<RoleRule> { it.priority })
        .firstOrNull { rule ->
            compile(rule, issues)?.containsMatchIn(text) == true
        }

    private fun matchNarrationRole(
        text: String,
        rules: List<RoleRule>,
        issues: MutableList<PipelineIssue>,
    ): RoleRule? = matchRole(text, rules.filter { !it.onlyDialogue }, issues)

    private fun compile(rule: RoleRule, issues: MutableList<PipelineIssue>): Regex? =
        runCatching { Regex(rule.pattern) }.getOrElse { error ->
            val id = "role:${rule.id}"
            if (issues.none { it.ruleId == id }) {
                issues += PipelineIssue(
                    field = "role",
                    message = "「${rule.name}」正则无效：${error.message}",
                    ruleId = id,
                )
            }
            null
        }

    private data class RawSpan(val text: String, val isDialogue: Boolean)
}
