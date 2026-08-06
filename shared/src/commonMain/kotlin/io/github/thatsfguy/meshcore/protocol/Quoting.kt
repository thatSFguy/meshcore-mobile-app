package io.github.thatsfguy.meshcore.protocol

/**
 * Quote-reply text, which MeshCore carries as ordinary message text.
 *
 * A reply is sent as the quoted lines followed by the actual reply:
 *
 * ```
 * > Kaylee: are you heading out tomorrow
 * yes, leaving about six
 * ```
 *
 * There is no protocol field for this — it is a convention in the text —
 * so anything that renders a message has to split it, and anything that
 * *summarises* a message has to summarise the right half. The
 * conversation list used to `take(80)` the raw text, which meant every
 * reply previewed as the message being replied TO rather than what was
 * actually said.
 *
 * Lives in `shared` rather than beside the Compose code because three
 * callers now need it (the bubble, the conversation-list preview, the
 * reaction notification) and it must be testable without a device.
 */
object Quoting {

    /** How much of the target to inline when composing a quote prefix. */
    const val QUOTE_MAX_CHARS: Int = 40

    /**
     * Split [text] into its leading quote block and the reply body.
     *
     * Returns `null to text` when there is no quote. A line only counts
     * as quoted while it is part of the LEADING run — `5 > 3` further
     * down is arithmetic, not a quote.
     */
    fun split(text: String): Pair<String?, String> {
        if (!text.startsWith(">")) return null to text
        val lines = text.lines()
        val quoted = lines.takeWhile { it.startsWith(">") }
        if (quoted.isEmpty()) return null to text
        val body = lines.drop(quoted.size).joinToString("\n").trimStart('\n')
        return quoted.joinToString("\n") { it.removePrefix(">").trim() } to body
    }

    /**
     * The part of [text] worth showing in a one-line summary.
     *
     * For a reply that is the body — what the person actually said. A
     * message that is *only* a quote falls back to the quote, because a
     * blank row tells the reader less than the wrong text does.
     */
    fun previewBody(text: String): String {
        val (quoted, body) = split(text)
        return body.ifBlank { quoted ?: text }
    }

    /** Collapse to a single line and clip to [maxChars]. */
    fun oneLine(text: String, maxChars: Int): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length > maxChars) flat.take(maxChars).trimEnd() + "…" else flat
    }
}
