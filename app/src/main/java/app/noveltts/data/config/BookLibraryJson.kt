package app.noveltts.data.config

import app.noveltts.data.JsonInput
import app.noveltts.domain.BookCharacter
import app.noveltts.domain.BookLibrary
import app.noveltts.domain.BookRoleProfile
import app.noveltts.domain.BookVoiceMode
import org.json.JSONArray
import org.json.JSONObject

/** Strict, bounded format shared by the local store and application backup. */
object BookLibraryJson {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_BOOKS = 100
    const val MAX_CHARACTERS = 500
    const val MAX_TOTAL_CHARACTERS = 5000
    const val MAX_POOL = 64
    const val MAX_ALIASES = 16
    private val idPattern = Regex("[A-Za-z0-9_-]{1,100}")

    fun validate(library: BookLibrary) {
        require(idPattern.matches(library.revision)) { "书籍配置版本标识无效" }
        require(library.books.size <= MAX_BOOKS) { "最多保存 $MAX_BOOKS 本书籍方案" }
        require(library.books.map { it.id }.distinct().size == library.books.size) { "书籍 ID 重复" }
        require(library.books.sumOf { it.characters.size } <= MAX_TOTAL_CHARACTERS) { "人物总数超过上限" }
        require(library.activeBookId.isEmpty() || library.activeBook != null) { "当前书籍不存在" }
        library.books.forEach { book ->
            require(idPattern.matches(book.id) && idPattern.matches(book.revision)) { "书籍标识无效" }
            label(book.name, 80, "书名")
            require(book.voicePool.size <= MAX_POOL && book.voicePool.distinct() == book.voicePool) { "声音池过大或有重复音色" }
            book.voicePool.forEach(::voice)
            require(book.characters.size <= MAX_CHARACTERS) { "每本书最多 $MAX_CHARACTERS 个人物" }
            require(book.characters.map { it.id }.distinct().size == book.characters.size) { "人物 ID 重复" }
            val names = HashSet<String>()
            book.characters.forEach { character ->
                require(idPattern.matches(character.id)) { "人物标识无效" }
                label(character.name, 60, "人物名")
                require(character.aliases.size <= MAX_ALIASES && character.aliases.distinct() == character.aliases &&
                    character.name !in character.aliases) { "人物别名重复或超过 $MAX_ALIASES 个" }
                character.aliases.forEach { label(it, 60, "别名") }
                require(character.names.all { names.add(it) }) { "本书已有同名人物或别名，请合并人物或调整称呼" }
                if (character.voiceId.isNotEmpty()) voice(character.voiceId)
                require(character.voiceMode != BookVoiceMode.FIXED || character.voiceId.isNotEmpty()) { "请指定人物音色" }
                require(character.voiceMode != BookVoiceMode.DEFAULT || character.voiceId.isEmpty()) { "默认音色模式不能包含固定绑定" }
            }
        }
    }

    fun encode(library: BookLibrary): String {
        validate(library)
        return JSONObject().put("schema", 1).put("revision", library.revision).put("activeBookId", library.activeBookId)
            .put("books", JSONArray().apply { library.books.forEach { book ->
                put(JSONObject().put("id", book.id).put("name", book.name).put("revision", book.revision)
                    .put("voicePool", JSONArray(book.voicePool)).put("characters", JSONArray().apply {
                        book.characters.forEach { character -> put(JSONObject().put("id", character.id)
                            .put("name", character.name).put("aliases", JSONArray(character.aliases))
                            .put("voiceId", character.voiceId).put("voiceMode", character.voiceMode.name)) }
                    }))
            } }).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "书籍配置超过 2 MB" } }
    }

    fun decode(text: String): BookLibrary {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "书籍配置超过 2 MB" }
        val root = JsonInput.parseObject(text, 6)
        require(root.get("schema") == 1) { "书籍配置版本过新或无效" }
        val books = root.getJSONArray("books")
        require(books.length() <= MAX_BOOKS) { "书籍方案过多" }
        return BookLibrary((0 until books.length()).map { index ->
            val book = books.getJSONObject(index)
            val characters = book.getJSONArray("characters")
            require(characters.length() <= MAX_CHARACTERS) { "人物过多" }
            BookRoleProfile(string(book, "id"), string(book, "name"), strings(book.getJSONArray("voicePool"), MAX_POOL),
                (0 until characters.length()).map { i ->
                    val character = characters.getJSONObject(i)
                    BookCharacter(string(character, "id"), string(character, "name"),
                        strings(character.getJSONArray("aliases"), MAX_ALIASES), string(character, "voiceId"),
                        BookVoiceMode.valueOf(string(character, "voiceMode")))
                }, string(book, "revision"))
        }, string(root, "activeBookId"), string(root, "revision")).also(::validate)
    }

    private fun strings(array: JSONArray, max: Int): List<String> {
        require(array.length() <= max) { "书籍配置条目过多" }
        return (0 until array.length()).map { index ->
            val value = array.get(index)
            require(value is String) { "书籍配置数组元素必须是字符串" }
            value
        }
    }
    // Android's JSONObject.getString coerces numbers and booleans; reject them explicitly.
    private fun string(json: JSONObject, key: String): String {
        val value = json.get(key)
        require(value is String) { "书籍配置 $key 必须是字符串" }
        return value
    }
    private fun label(value: String, max: Int, label: String) {
        require(value.isNotBlank() && value == value.trim() && value.length <= max && value.none { it.isISOControl() }) {
            "$label 不能为空、含控制字符或超过 $max 字符"
        }
    }
    private fun voice(id: String) {
        require(id.startsWith("multitts:") && id.length in 10..512 && id.none { it.isWhitespace() || it.isISOControl() }) { "音色 ID 无效" }
    }
}
