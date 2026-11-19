package io.github.thatsfguy.meshcore.presentation

/**
 * The links in a received message (2026-11-18, shared so iOS finds the
 * same ones Android does; Android's MessageLinks styles from these).
 *
 * Two kinds: `http(s)://…`, which LEAVES THE MESH — the address is the
 * sender's choice and opening it tells that server your IP, network and
 * that you're online, so a tap must confirm first ([LEAVE_TITLE],
 * [leaveText]) — and `meshcore://…`, which goes through the same
 * confirmation as a scanned QR. Whichever starts first wins an overlap:
 * a URL whose query holds `@[` stays one link (the mention inside it is
 * not styled), and a link that starts inside a mention is dropped.
 * Trailing sentence punctuation belongs to the prose, not the link.
 */
object MessageLinkSpans {

    class Link(
        /** UTF-16 offsets into the text, end exclusive. */
        val start: Int,
        val end: Int,
        val url: String,
        val isHttp: Boolean,
    )

    private val HTTP = Regex("""https?://[^\s<>"'\]]+""", RegexOption.IGNORE_CASE)
    private val MESHCORE = Regex("""meshcore://[^\s<>"'\]]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}>'\""

    const val LEAVE_TITLE = "Open link?"
    const val LEAVE_ACTION = "Open in browser"

    fun leaveText(url: String): String =
        "This opens in your browser and leaves the mesh. The site — chosen by the sender, not you — " +
            "will see your real IP address and network.\n\n$url"

    fun trimTrailingPunctuation(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in TRAILING) end--
        return url.substring(0, end)
    }

    fun find(text: String): List<Link> {
        val mentions = Mentions.spans(text).map { it.range }
        val raw = (HTTP.findAll(text).map { it.range to true } + MESHCORE.findAll(text).map { it.range to false })
            .sortedBy { it.first.first }
        val out = ArrayList<Link>()
        var cursor = 0
        for ((range, http) in raw) {
            if (range.first < cursor) continue
            if (mentions.any { m -> m.first < range.first && range.first <= m.last }) continue
            val url = trimTrailingPunctuation(text.substring(range.first, range.last + 1))
            if (url.length <= (if (http) "http://".length else "meshcore://".length)) continue
            out += Link(range.first, range.first + url.length, url, http)
            cursor = range.last + 1
        }
        return out
    }
}
