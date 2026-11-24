package app.noveltts.domain

enum class AttributionKind {
    /** 引语前的旁白以冒号或逗号引出，如「许山问：“……”」。 */
    LEAD_IN,

    /** 引语后的旁白点明说话人，如「“……”易心诚恳道」。 */
    TRAILING,

    /** 引语前一句旁白提到了角色，但不是引出句式。 */
    NEARBY,

    /** 没写是谁说的，沿用上一个说话人。 */
    CONTINUED,

    /** 由非 AUTO 范围的角色规则命中。 */
    LEGACY_RULE,
    UNKNOWN,
}

enum class Confidence { HIGH, MEDIUM, LOW }

data class Attribution(
    val kind: AttributionKind,
    val confidence: Confidence,
    /** 作为依据的那句旁白，最多 [EVIDENCE_CHARS] 字。 */
    val evidence: String = "",
) : java.io.Serializable {
    /** 已经配了角色，但依据不够确定，界面需要提示用户核对。 */
    val uncertain: Boolean get() = kind != AttributionKind.UNKNOWN && confidence != Confidence.HIGH

    companion object {
        const val EVIDENCE_CHARS = 20
        val UNKNOWN = Attribution(AttributionKind.UNKNOWN, Confidence.LOW)
        val LEGACY_RULE = Attribution(AttributionKind.LEGACY_RULE, Confidence.HIGH)
    }
}

/** 没写是谁说的对话怎么读。 */
enum class UnattributedPolicy {
    INHERIT,
    DEFAULT_VOICE;

    companion object {
        val DEFAULT = INHERIT
        fun fromStorage(value: String?): UnattributedPolicy =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** 角色的多个称呼存放在 [RoleRule.pattern] 里，写成「许山|师尊」这样的并列字面量，不另加字段。 */
object RoleAliases {
    private val META = Regex("""[\\^$.|?*+()\[\]{}]""")

    fun encode(aliases: List<String>): String = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .joinToString("|") { if (META.containsMatchIn(it)) Regex.escape(it) else it }

    /** 旧式匹配范围的角色能否直接改用 AUTO：只管对话，且匹配词是纯称呼。 */
    fun canSwitchToAuto(rule: RoleRule): Boolean =
        rule.matchScope != RoleMatchScope.AUTO && rule.onlyDialogue &&
            rule.matchMode == RoleMatchMode.REGEX_CONTAINS && decode(rule.pattern) != null

    /** 还原称呼列表；表达式不是纯字面量并列时返回 null，只能在高级规则里编辑。 */
    fun decode(pattern: String): List<String>? {
        val aliases = mutableListOf<String>()
        var rest = pattern
        while (true) {
            val alias: String
            if (rest.startsWith("\\Q")) {
                val end = rest.indexOf("\\E", 2)
                if (end < 0) return null
                alias = rest.substring(2, end)
                rest = rest.substring(end + 2)
            } else {
                alias = rest.substringBefore('|')
                if (META.containsMatchIn(alias)) return null
                rest = rest.substring(alias.length)
            }
            if (alias.isBlank()) return null
            aliases += alias
            if (rest.isEmpty()) return aliases
            if (!rest.startsWith("|")) return null
            rest = rest.substring(1)
        }
    }
}

data class SpeechUnit(val text: String, val isDialogue: Boolean)

/** [roleIndex] 指向传入 [SpeakerAttributor] 的正则列表；无法确定时为 -1。 */
class SpeakerDecision(val roleIndex: Int, val attribution: Attribution)

/**
 * 按段落判定每句引语的说话人。只使用用户建好的 AUTO 角色，不猜测新人物。
 *
 * 判定顺序：引出句式的前文 > 后文 > 前文提及 > 沿用上一人。引语内部出现的名字不算证据，
 * 开头被称呼的角色还会被排除，避免把「易心，当初……」读成易心自己的声音。
 *
 * @param regexes 每个 AUTO 角色的称呼正则，无效正则传 null。
 */
class SpeakerAttributor(
    private val regexes: List<Regex?>,
    private val policy: UnattributedPolicy,
) {
    private var last = NONE
    private var other = NONE
    private var previousParagraphHadDialogue = false

    /** 逐段调用，顺序必须与原文一致；旁白单元对应 null。 */
    fun paragraph(units: List<SpeechUnit>): List<SpeakerDecision?> {
        if (units.none { it.isDialogue }) {
            reset()
            return List(units.size) { null }
        }
        var dialogueSeen = false
        val decisions = units.mapIndexed { index, unit ->
            if (!unit.isDialogue) return@mapIndexed null
            val before = units.getOrNull(index - 1)?.takeUnless { it.isDialogue }?.text
            val after = units.getOrNull(index + 1)?.takeUnless { it.isDialogue }?.text
            val decision = decide(unit.text, before, after, canInherit = previousParagraphHadDialogue || dialogueSeen)
            dialogueSeen = true
            when {
                decision.roleIndex != NONE -> remember(decision.roleIndex)
                // 旁白交代了一个没建角色的说话人，后面的话不能再算到之前那位头上。
                before != null || after != null -> reset()
            }
            decision
        }
        previousParagraphHadDialogue = true
        return decisions
    }

    private fun decide(quote: String, before: String?, after: String?, canInherit: Boolean): SpeakerDecision {
        val barred = vocativeAtHead(quote)
        val lead = before?.trimEnd()
        if (lead != null && lead.lastOrNull() in LEAD_IN_ENDINGS) {
            leadInSpeaker(lastSentence(lead), barred)?.let { return it.decision(AttributionKind.LEAD_IN, Confidence.HIGH) }
        }
        if (after != null) {
            trailingSpeaker(firstSentence(after), barred)?.let { return it.decision(AttributionKind.TRAILING, Confidence.HIGH) }
        }
        if (lead != null) {
            leadInSpeaker(lastSentence(lead), barred)?.let { return it.decision(AttributionKind.NEARBY, Confidence.MEDIUM) }
        }
        if (policy == UnattributedPolicy.INHERIT && canInherit && before == null && after == null && last != NONE) {
            if (last != barred) return SpeakerDecision(last, Attribution(AttributionKind.CONTINUED, Confidence.MEDIUM))
            if (other != NONE) return SpeakerDecision(other, Attribution(AttributionKind.CONTINUED, Confidence.LOW))
        }
        return SpeakerDecision(NONE, Attribution.UNKNOWN)
    }

    private class Pick(val roleIndex: Int, val evidence: String, val ambiguous: Boolean) {
        fun decision(kind: AttributionKind, confidence: Confidence) = SpeakerDecision(
            roleIndex,
            Attribution(kind, if (ambiguous) Confidence.MEDIUM else confidence, evidence.take(Attribution.EVIDENCE_CHARS)),
        )
    }

    private class Hit(val roleIndex: Int, val start: Int, val end: Int)

    /** 从离引语最近的分句往前找，取该分句里最靠前的角色（主语）。 */
    private fun leadInSpeaker(sentence: String, barred: Int): Pick? {
        clauses(sentence).asReversed().forEach { clause ->
            val hits = hits(clause).filter { it.roleIndex != barred }
            if (hits.isNotEmpty()) return Pick(hits.first().roleIndex, sentence, hits.distinctRoles() > 1)
        }
        return null
    }

    /** 取引语后第一个提到角色的分句；「许山闻言一愣」这类写的是听者，不当作说话人。 */
    private fun trailingSpeaker(sentence: String, barred: Int): Pick? {
        clauses(sentence).forEach { clause ->
            val all = hits(clause)
            if (all.isEmpty()) return@forEach
            val speakers = all.filter { hit ->
                hit.roleIndex != barred && LISTENER_MARKERS.none { clause.startsWith(it, hit.end) }
            }
            // 只有「许山问」这样以说话动词收尾才算明确；「许山转身离开」只是动作，归给他但要提示核对。
            val vague = speakers.distinctRoles() > 1 || clause.trim().lastOrNull() !in SPEECH_VERBS
            return speakers.firstOrNull()?.let { Pick(it.roleIndex, sentence, vague) }
        }
        return null
    }

    private fun hits(text: String): List<Hit> = regexes.mapIndexedNotNull { index, regex ->
        regex?.find(text)?.takeIf { it.value.isNotEmpty() }?.let { Hit(index, it.range.first, it.range.last + 1) }
    }.sortedBy { it.start }

    private fun List<Hit>.distinctRoles(): Int = mapTo(HashSet()) { it.roleIndex }.size

    private fun vocativeAtHead(quote: String): Int {
        val text = quote.trimStart()
        var best = NONE
        var bestLength = 0
        regexes.forEachIndexed { index, regex ->
            val match = regex?.find(text) ?: return@forEachIndexed
            if (match.range.first == 0 && match.value.length > bestLength &&
                text.getOrNull(match.value.length) in VOCATIVE_SEPARATORS
            ) {
                best = index
                bestLength = match.value.length
            }
        }
        return best
    }

    private fun remember(roleIndex: Int) {
        if (roleIndex != last) other = last
        last = roleIndex
    }

    private fun reset() {
        last = NONE
        other = NONE
        previousParagraphHadDialogue = false
    }

    companion object {
        private const val NONE = -1
        private val SENTENCE_ENDS = setOf('。', '！', '？', '!', '?', '；', ';', '…')
        private val CLAUSE_BREAKS = charArrayOf('，', ',', '：', ':')
        private val LEAD_IN_ENDINGS = setOf('：', ':', '，', ',')
        private val VOCATIVE_SEPARATORS = setOf('，', ',', '！', '!', '？', '?', '：', ':')
        private val SPEECH_VERBS = setOf('道', '说', '問', '问', '答', '喊', '叫', '笑', '叹', '嘆', '吼', '喝', '骂', '罵', '曰', '說')
        private val LISTENER_MARKERS = listOf("闻言", "听了", "听罢", "听到", "听完", "听后", "聞言", "聽了", "聽罷", "聽到", "聽完", "聽後")

        /** 把正文切成段落，每段再切成旁白 / 引语单元；引号不跨段，未闭合的引语到段尾为止。 */
        fun paragraphs(text: String, quotePairs: Map<Char, Char>): List<List<SpeechUnit>> =
            text.split('\n').map { units(it, quotePairs) }.filter { it.isNotEmpty() }

        private fun units(paragraph: String, quotePairs: Map<Char, Char>): List<SpeechUnit> {
            val units = mutableListOf<SpeechUnit>()
            var cursor = 0
            var quoteStart = -1
            var closing: Char? = null
            paragraph.forEachIndexed { index, char ->
                if (quoteStart < 0) {
                    val close = quotePairs[char] ?: return@forEachIndexed
                    units += SpeechUnit(paragraph.substring(cursor, index), isDialogue = false)
                    quoteStart = index + 1
                    closing = close
                } else if (char == closing) {
                    units += SpeechUnit(paragraph.substring(quoteStart, index), isDialogue = true)
                    cursor = index + 1
                    quoteStart = -1
                }
            }
            units += if (quoteStart >= 0) SpeechUnit(paragraph.substring(quoteStart), isDialogue = true)
            else SpeechUnit(paragraph.substring(cursor), isDialogue = false)
            return units.filter { it.text.isNotBlank() }
        }

        private fun lastSentence(text: String): String {
            val trimmed = text.trimEnd().trimEnd { it in SENTENCE_ENDS }
            return trimmed.substring(trimmed.indexOfLast { it in SENTENCE_ENDS } + 1).trim()
        }

        private fun firstSentence(text: String): String {
            val trimmed = text.trimStart()
            val end = trimmed.indexOfFirst { it in SENTENCE_ENDS }
            return (if (end < 0) trimmed else trimmed.substring(0, end)).trim()
        }

        private fun clauses(sentence: String): List<String> =
            sentence.split(*CLAUSE_BREAKS).filter { it.isNotBlank() }
    }
}
