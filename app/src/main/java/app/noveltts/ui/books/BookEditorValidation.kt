package app.noveltts.ui.books

import app.noveltts.data.config.BookLibraryJson
import app.noveltts.domain.BookCharacter

internal fun bookNameError(value: String, character: Boolean): String? {
    val name = value.trim()
    val label = if (character) "人物名" else "书名"
    val limit = if (character) 60 else 80
    return when {
        name.isEmpty() -> "请输入$label"
        name.length > limit -> "${label}最多 $limit 字"
        name.any { it.isISOControl() } -> "${label}不能包含换行或控制字符"
        else -> null
    }
}

internal fun bookAliases(text: String, name: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotBlank() && it != name.trim() }.distinct()

/** Mirrors repository normalization; persistence still validates under its configuration lock. */
internal fun bookCharacterErrors(name: String, aliases: String, id: String, others: List<BookCharacter>): Pair<String?, String?> {
    val normalized = bookAliases(aliases, name)
    val ownedNames = others.filterNot { it.id == id }.flatMap { it.names }.toSet()
    val nameError = bookNameError(name, character = true)
        ?: if (name.trim() in ownedNames) "本书已有人物使用此姓名或别名，请改名或合并" else null
    val aliasError = when {
        normalized.size > BookLibraryJson.MAX_ALIASES -> "最多 ${BookLibraryJson.MAX_ALIASES} 个别名"
        normalized.any { it.length > 60 || it.any(Char::isISOControl) } -> "每个别名最多 60 字，不能含控制字符"
        normalized.any { it in ownedNames } -> "别名「${normalized.first { it in ownedNames }}」已属于其他人物"
        else -> null
    }
    return nameError to aliasError
}
