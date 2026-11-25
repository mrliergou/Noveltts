package app.noveltts.data.config

import android.content.Context
import app.noveltts.data.voicepack.PackInput
import app.noveltts.domain.BookCharacter
import app.noveltts.domain.BookLibrary
import app.noveltts.domain.BookRoleProfile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Main-process repositories share the configuration lock; the rule worker receives snapshots only. */
class BookRolesRepository(private val directory: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "config"))
    private val file get() = File(directory, FILE)
    fun files(): List<File> = listOf(file, File(directory, "$FILE.bak"))
    fun readBackup(): BookLibrary = synchronized(CONFIGURATION_LOCK) { read(File(directory, "$FILE.bak")) }

    fun load(): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        if (!file.exists()) BookLibrary() else try { read(file) }
        catch (error: Exception) { throw RulesStoreException("书籍配置无法读取，原文件已保留；请从备份恢复", error) }
    }

    fun prepare(usableIds: Set<String>): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        val current = load()
        val book = current.activeBook ?: return@synchronized current
        val assigned = book.assignPending(usableIds)
        if (assigned == book) current else replace(current, assigned)
    }

    fun create(book: BookRoleProfile, select: Boolean = false): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        val current = load()
        check(current.books.none { it.id == book.id }) { "书籍已保存，请刷新后编辑" }
        require(book.characters.isEmpty()) { "新书籍请先保存方案，再添加人物" }
        write(current.copy(books = current.books + book.copy(name = book.name.trim(), revision = revision()),
            activeBookId = if (select) book.id else current.activeBookId))
    }

    fun select(bookId: String, expectedLibraryRevision: String, usableIds: Set<String>): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        val current = load()
        check(current.revision == expectedLibraryRevision) { "书籍列表已变化，请刷新后重新选择" }
        require(bookId.isEmpty() || current.books.any { it.id == bookId }) { "所选书籍已删除" }
        val next = current.copy(activeBookId = bookId)
        val active = next.activeBook
        val assigned = active?.assignPending(usableIds)
        if (assigned != null && assigned != active) replace(next, assigned)
        else if (next == current) current else write(next)
    }

    fun update(expected: BookRoleProfile, name: String, pool: List<String>, usableIds: Set<String>): BookLibrary =
        edit(expected) { it.copy(name = name.trim(), voicePool = pool.toList()).assignPending(usableIds) }

    fun saveCharacter(expected: BookRoleProfile, character: BookCharacter, usableIds: Set<String>): BookLibrary = edit(expected) { book ->
        val cleaned = character.copy(name = character.name.trim(),
            aliases = character.aliases.map { it.trim() }.filter { it.isNotBlank() && it != character.name.trim() }.distinct())
        val index = book.characters.indexOfFirst { it.id == cleaned.id }
        val characters = book.characters.toMutableList()
        if (index < 0) characters += cleaned else characters[index] = cleaned
        book.copy(characters = characters).assignPending(usableIds)
    }

    fun deleteCharacter(expected: BookRoleProfile, characterId: String): BookLibrary = edit(expected) { book ->
        check(book.characters.any { it.id == characterId }) { "人物已删除" }
        book.copy(characters = book.characters.filterNot { it.id == characterId })
    }

    fun mergeCharacters(expected: BookRoleProfile, sourceId: String, targetId: String): BookLibrary = edit(expected) { book ->
        require(sourceId != targetId) { "请选择另一个人物" }
        val source = book.characters.firstOrNull { it.id == sourceId } ?: error("来源人物已删除")
        val target = book.characters.firstOrNull { it.id == targetId } ?: error("目标人物已删除")
        val merged = target.copy(aliases = (target.aliases + source.names).distinct().filterNot { it == target.name })
        book.copy(characters = book.characters.filterNot { it.id == sourceId }.map { if (it.id == targetId) merged else it })
    }

    fun delete(expected: BookRoleProfile): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        val current = load()
        checked(current, expected)
        write(current.copy(books = current.books.filterNot { it.id == expected.id },
            activeBookId = current.activeBookId.takeUnless { it == expected.id }.orEmpty()))
    }

    /** Used within the enclosing application restore journal; old backups omit this store entirely. */
    fun restore(library: BookLibrary, expectedRevision: String?, repairCorrupt: Boolean = false): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        if (!repairCorrupt) check(load().revision == expectedRevision) { "书籍配置已变化，请重新预览备份" }
        if (repairCorrupt && file.exists()) Files.copy(file.toPath(), File(directory, "$FILE.before-recovery-${revision()}").toPath())
        write(library.copy(books = library.books.map { it.copy(revision = revision()) }), backupCurrent = !repairCorrupt)
    }

    fun recoveryFingerprint(): String = synchronized(CONFIGURATION_LOCK) {
        files().joinToString(":") { target ->
            if (!target.exists()) "missing" else {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                target.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    fun recoverBackup(expectedFingerprint: String): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        check(recoveryFingerprint() == expectedFingerprint) { "恢复来源已变化，请重新确认" }
        val saved = read(File(directory, "$FILE.bak"))
        if (file.exists()) Files.copy(file.toPath(), File(directory, "$FILE.before-recovery-${revision()}").toPath())
        write(saved.copy(books = saved.books.map { it.copy(revision = revision()) }), backupCurrent = false)
    }

    private fun edit(expected: BookRoleProfile, change: (BookRoleProfile) -> BookRoleProfile): BookLibrary = synchronized(CONFIGURATION_LOCK) {
        val current = load()
        val book = checked(current, expected)
        val next = change(book)
        if (next == book) current else replace(current, next)
    }

    private fun checked(current: BookLibrary, expected: BookRoleProfile): BookRoleProfile {
        val book = current.books.firstOrNull { it.id == expected.id }
        check(book != null && book.revision == expected.revision) { "本书已被修改或删除，草稿已保留；请刷新后重新编辑" }
        return book
    }

    private fun replace(current: BookLibrary, book: BookRoleProfile): BookLibrary =
        write(current.copy(books = current.books.map { if (it.id == book.id) book.copy(revision = revision()) else it }))

    private fun read(target: File): BookLibrary = target.inputStream().use {
        BookLibraryJson.decode(PackInput.readBytes(it, BookLibraryJson.MAX_BYTES.toLong()).toString(Charsets.UTF_8))
    }

    private fun write(library: BookLibrary, backupCurrent: Boolean = true): BookLibrary {
        val next = library.copy(revision = revision())
        val text = BookLibraryJson.encode(next)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建书籍配置目录" }
        val pending = File.createTempFile("book-roles-", ".tmp", directory)
        try {
            FileOutputStream(pending).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(read(pending) == next) { "书籍配置临时文件校验失败" }
            if (backupCurrent && file.exists()) Files.copy(file.toPath(), File(directory, "$FILE.bak").toPath(), REPLACE_EXISTING)
            try { Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending.toPath(), file.toPath(), REPLACE_EXISTING) }
            return next
        } finally { pending.delete() }
    }

    companion object {
        const val FILE = "book-roles.json"
        private fun revision() = UUID.randomUUID().toString()

        /** Conflicts are whole books by stable ID; same titles may belong to different books. */
        fun merge(current: BookLibrary, incoming: BookLibrary, strategy: RuleImportStrategy): BookLibrary {
            val books = when (strategy) {
                RuleImportStrategy.REPLACE_ALL -> incoming.books
                RuleImportStrategy.SKIP -> current.books + incoming.books.filter { book -> current.books.none { it.id == book.id } }
                RuleImportStrategy.OVERWRITE -> current.books.map { book -> incoming.books.firstOrNull { it.id == book.id } ?: book } +
                    incoming.books.filter { book -> current.books.none { it.id == book.id } }
            }
            // Import never silently selects a different book for an external reader.
            return current.copy(books = books, activeBookId = current.activeBookId.takeIf { id -> books.any { it.id == id } }.orEmpty())
                .also(BookLibraryJson::validate)
        }
    }
}
