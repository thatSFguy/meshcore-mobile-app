package io.github.thatsfguy.meshcore.protocol

/**
 * emoji -> count, serialised as a small JSON object for the message row.
 *
 * Hand-rolled rather than pulling in a JSON library: the shape is one
 * flat object of string keys to non-negative integers, the keys come
 * from [Reactions.ALL] (no quotes or backslashes anywhere in that list),
 * and this has to work on both platforms.
 *
 * Decoding is TOTAL. The column is derived from mesh traffic and is read
 * on the render path, so a malformed or hostile blob yields an empty map
 * rather than an exception in the middle of a conversation.
 */
object ReactionCounts {

    /** Null when empty, so "no reactions" stays a NULL column. */
    fun encode(counts: Map<String, Int>): String? {
        val kept = counts.filterValues { it > 0 }
        if (kept.isEmpty()) return null
        return kept.entries.joinToString(",", prefix = "{", postfix = "}") { (emoji, count) ->
            "\"" + escape(emoji) + "\":" + count
        }
    }

    fun decode(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        val text = json.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        var i = 1
        val end = text.length - 1
        while (i < end) {
            when (text[i]) {
                ' ', '\t', '\n', '\r', ',' -> { i++; continue }
                '"' -> Unit
                else -> return out   // not a key where one belongs: stop
            }
            val key = StringBuilder()
            i++
            while (i < end && text[i] != '"') {
                if (text[i] == '\\' && i + 1 < end) {
                    i++
                    key.append(unescape(text[i]))
                } else {
                    key.append(text[i])
                }
                i++
            }
            if (i >= end) return out
            i++                                   // closing quote
            while (i < end && text[i].isWhitespace()) i++
            if (i >= end || text[i] != ':') return out
            i++
            while (i < end && text[i].isWhitespace()) i++
            val start = i
            if (i < end && text[i] == '-') i++
            while (i < end && text[i].isDigit()) i++
            val value = text.substring(start, i).toIntOrNull()
            if (value != null && value > 0 && key.isNotEmpty()) out[key.toString()] = value
        }
        return out
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c.code < 0x20 -> Unit          // drop control characters
                else -> append(c)
            }
        }
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'
        't' -> '\t'
        else -> c
    }
}
