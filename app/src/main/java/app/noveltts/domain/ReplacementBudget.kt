package app.noveltts.domain

class PipelineLimitException(message: String) : IllegalArgumentException(message)

/** Checks generated UTF-16 units before appending, including expanded capture groups. */
internal class ReplacementBudget(private val maximum: Int = 2_000_000, private val workMaximum: Long = 16_000_000) {
    private var work = 0L
    private var replacementCredit = 0
    private val patterns = HashMap<String, java.util.regex.Pattern>()
    private val validations = HashMap<ReplacementRule, String?>()
    fun validationError(rule: ReplacementRule): String? {
        if (!validations.containsKey(rule)) validations[rule] = RuleValidation.localReplacementError(rule)
        return validations[rule]
    }
    private fun generated(out: StringBuilder, text: String, start: Int = 0, end: Int = text.length) {
        val count = end - start
        val free = minOf(replacementCredit, count)
        charge(count - free)
        replacementCredit -= free
        append(out, text, start, end)
    }
    fun charge(count: Int) {
        if (count < 0 || count.toLong() > workMaximum - work) throw PipelineLimitException("规则累计处理量超过上限")
        work += count
    }
    private fun append(out: StringBuilder, text: String, start: Int = 0, end: Int = text.length) {
        val count = end - start
        if (count < 0 || count > maximum - out.length) throw PipelineLimitException("规则展开文本超过上限（$maximum 字符）")
        out.append(text, start, end)
    }
    fun replace(text: String, rule: ReplacementRule): String {
        val out = StringBuilder(minOf(text.length, maximum))
        var copied = 0
        if (!rule.isRegex) {
            var index = text.indexOf(rule.find)
            while (index >= 0) {
                append(out, text, copied, index)
                replacementCredit = rule.find.length
                generated(out, rule.replaceWith)
                copied = index + rule.find.length
                index = text.indexOf(rule.find, copied)
            }
        } else {
            val matcher = patterns.getOrPut(rule.find) { java.util.regex.Pattern.compile(rule.find) }.matcher(text)
            while (matcher.find()) {
                append(out, text, copied, matcher.start())
                replacementCredit = matcher.end() - matcher.start()
                val replacement = rule.replaceWith
                var i = 0
                while (i < replacement.length) {
                    when (replacement[i]) {
                        '\\' -> { i++; require(i < replacement.length); generated(out, replacement, i, i + 1); i++ }
                        '$' -> {
                            i++
                            val group = if (replacement[i] == '{') {
                                val end = replacement.indexOf('}', i)
                                val value = matcher.group(replacement.substring(i + 1, end))
                                i = end + 1
                                value
                            } else {
                                var number = replacement[i++].digitToInt()
                                while (i < replacement.length && replacement[i].isDigit() && number * 10 + replacement[i].digitToInt() <= matcher.groupCount()) {
                                    number = number * 10 + replacement[i++].digitToInt()
                                }
                                matcher.group(number)
                            }
                            if (group != null) generated(out, group)
                        }
                        else -> { val start = i++; while (i < replacement.length && replacement[i] != '$' && replacement[i] != '\\') i++; generated(out, replacement, start, i) }
                    }
                }
                copied = matcher.end()
            }
        }
        append(out, text, copied)
        return out.toString()
    }
}