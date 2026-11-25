package app.noveltts.domain

import java.util.UUID

enum class BookVoiceMode { POOL, FIXED, DEFAULT }

data class BookCharacter(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val aliases: List<String> = emptyList(),
    val voiceId: String = "",
    val voiceMode: BookVoiceMode = BookVoiceMode.POOL,
) {
    val names: List<String> get() = (listOf(name) + aliases).distinct()
}

data class BookRoleProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val voicePool: List<String> = emptyList(),
    val characters: List<BookCharacter> = emptyList(),
    val revision: String = UUID.randomUUID().toString(),
) {
    fun roleId(characterId: String): String = "book:$id:$characterId"

    /** Bind only unassigned pool characters. Temporary availability never changes a saved binding. */
    fun assignPending(usableIds: Set<String>): BookRoleProfile {
        val available = voicePool.filter { it in usableIds }
        if (available.isEmpty()) return this
        val counts = characters.filter { it.voiceMode != BookVoiceMode.DEFAULT && it.voiceId.isNotBlank() }
            .groupingBy { it.voiceId }.eachCount().toMutableMap()
        val assigned = characters.map { character ->
            if (character.voiceMode != BookVoiceMode.POOL || character.voiceId.isNotBlank()) character else {
                val voice = available.minBy { counts[it] ?: 0 }
                counts[voice] = (counts[voice] ?: 0) + 1
                character.copy(voiceId = voice)
            }
        }
        return if (assigned == characters) this else copy(characters = assigned)
    }

    /** The synthetic group is always enabled and never saved in the legacy rules file. */
    fun withRules(global: RuleSet<RoleRule>, dialogueId: String?): RuleSet<RoleRule> {
        val groupId = "book:$id"
        val ownedNames = characters.flatMap { it.names }.toSet()
        val inherited = global.items.mapNotNull { role ->
            val aliases = if (role.matchScope == RoleMatchScope.AUTO) RoleAliases.decode(role.pattern) else null
            if (aliases == null || aliases.none { it in ownedNames }) role else {
                val remaining = aliases.filterNot { it in ownedNames }
                if (remaining.isEmpty()) null else role.copy(pattern = RoleAliases.encode(remaining))
            }
        }
        val local = characters.mapIndexed { index, character ->
            val voice = character.voiceId.takeIf { character.voiceMode != BookVoiceMode.DEFAULT && it.isNotBlank() }
                ?: dialogueId.orEmpty()
            RoleRule(id = roleId(character.id), name = character.name,
                pattern = RoleAliases.encode(character.names), voiceIds = listOf(voice),
                priority = Int.MAX_VALUE, groupId = groupId, sortIndex = index, matchScope = RoleMatchScope.AUTO)
        }
        return RuleSet(listOf(RuleGroup(groupId, name, order = Int.MIN_VALUE)) + global.groups, local + inherited)
    }
}

data class BookLibrary(
    val books: List<BookRoleProfile> = emptyList(),
    val activeBookId: String = "",
    val revision: String = "empty",
) {
    val activeBook: BookRoleProfile? get() = books.firstOrNull { it.id == activeBookId }
}
