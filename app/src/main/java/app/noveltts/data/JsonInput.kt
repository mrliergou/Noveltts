package app.noveltts.data

import org.json.JSONObject

/** Bounded, nonrecursive structural preflight before org.json's recursive parser. */
internal object JsonInput {
    const val MAX_DEPTH = 16

    fun parseObject(text: String, maxDepth: Int = MAX_DEPTH): JSONObject {
        checkDepth(text, maxDepth)
        return JSONObject(text.removePrefix("\uFEFF"))
    }

    // Reject lenient quoting/comments so the preflight and parser agree on string boundaries.
    // Remaining JSON grammar is checked by org.json after the nesting limit is established.
    fun checkDepth(text: String, maxDepth: Int = MAX_DEPTH) {
        require(maxDepth > 0)
        val closing = CharArray(maxDepth)
        var depth = 0
        var inString = false
        var escaped = false
        var canStartString = true
        for ((index, char) in text.withIndex()) {
            if (index == 0 && char == '\uFEFF') continue
            if (inString) {
                require(char >= ' ') { "JSON string contains a control character" }
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> {
                        inString = false
                        canStartString = false
                    }
                }
                continue
            }
            when (char) {
                ' ', '\t', '\r', '\n' -> Unit
                '"' -> {
                    require(canStartString) { "Invalid JSON string boundary" }
                    inString = true
                }
                '{', '[' -> {
                    require(depth < maxDepth) { "JSON nesting exceeds $maxDepth levels" }
                    closing[depth++] = if (char == '{') '}' else ']'
                    canStartString = true
                }
                '}', ']' -> {
                    require(depth > 0 && closing[depth - 1] == char) { "Mismatched JSON containers" }
                    depth--
                    canStartString = false
                }
                ',', ':' -> canStartString = true
                else -> {
                    require(char in '0'..'9' || char in "-+.eEtruefalsn") { "Unsupported JSON syntax" }
                    canStartString = false
                }
            }
        }
        require(!inString && depth == 0) { "Unterminated JSON string or container" }
    }
}
