package app.noveltts.domain

object RuleValidation {
    fun roleError(rule: RoleRule): String? = try {
        RuleRuntime.execute(RuleWork(roles = listOf(rule), validation = "role")).validation
    } catch (error: Exception) { error.message ?: "规则校验失败" }
    fun replacementError(rule: ReplacementRule): String? = try {
        RuleRuntime.execute(RuleWork(replacements = listOf(rule), validation = "replacement")).validation
    } catch (error: Exception) { error.message ?: "规则校验失败" }
    internal fun localRoleError(rule: RoleRule): String? = when {
        rule.name.isBlank() -> "请输入规则名称"
        rule.pattern.isBlank() -> "请输入匹配内容"
        rule.voiceIds.none { it.isNotBlank() } -> "请至少选择一个音色"
        rule.matchMode != RoleMatchMode.LITERAL_EXACT -> runCatching { Regex(rule.pattern) }
            .exceptionOrNull()?.let { "正则无效：${it.message}" }
        else -> null
    }

    internal fun localReplacementError(rule: ReplacementRule): String? {
        if (rule.find.isEmpty()) return "请输入查找内容"
        if (!rule.isRegex) return null
        return runCatching {
            val pattern = Regex(rule.find).toPattern()
            val groupCount = pattern.matcher("").groupCount()
            val names = Regex("\\(\\?<([A-Za-z][A-Za-z0-9]*)>")
                .findAll(rule.find).map { it.groupValues[1] }.toSet()
            var index = 0
            while (index < rule.replaceWith.length) {
                when (rule.replaceWith[index]) {
                    '\\' -> { require(++index < rule.replaceWith.length) { "替换内容末尾不能是单独的反斜杠" }; index++ }
                    '$' -> {
                        require(++index < rule.replaceWith.length) { "替换内容末尾不能是单独的 $" }
                        if (rule.replaceWith[index] == '{') {
                            val end = rule.replaceWith.indexOf('}', index)
                            require(end > index + 1) { "命名分组引用不完整" }
                            require(rule.replaceWith.substring(index + 1, end) in names) { "引用了不存在的命名分组" }
                            index = end + 1
                        } else {
                            require(rule.replaceWith[index] in '0'..'9') { "请使用 $0、$1 或命名分组引用" }
                            var group = rule.replaceWith[index++].digitToInt()
                            require(group <= groupCount) { "引用了不存在的分组 $group" }
                            while (index < rule.replaceWith.length && rule.replaceWith[index] in '0'..'9') {
                                val next = group * 10 + rule.replaceWith[index].digitToInt()
                                if (next > groupCount) break
                                group = next; index++
                            }
                        }
                    }
                    else -> index++
                }
            }
        }.exceptionOrNull()?.let { "替换规则无效：${it.message}" }
    }
}
