package io.github.thatsfguy.meshcore.protocol

/**
 * What a scanned QR actually is, decided by looking at it.
 *
 * MeshCore QRs come in two unrelated shapes: a `meshcore://` URI
 * (contact card, signed advert, or channel key) and a community JSON
 * blob. The app had a scanner for each, wired to a different parser,
 * so scanning the right code at the wrong button produced "Invalid
 * community code" for a perfectly good repeater card.
 *
 * That is a question the app can answer for itself. Classify first,
 * then dispatch — the user should not have to know which screen a code
 * belongs to before they can scan it.
 */
enum class ScannedCode {
    /** `meshcore://…` — hand to [ShareUri.decode]. */
    MeshCoreUri,

    /** A community invite JSON blob. */
    Community,

    /** Neither. */
    Unknown,

    ;

    companion object {
        private const val COMMUNITY_MARKER = "meshcore_community"

        /**
         * Classify [text] without parsing it properly.
         *
         * Deliberately shallow: this only decides which decoder gets a
         * look, and both of those validate their own input. Done with
         * string checks rather than a JSON parse so it is testable off
         * the device — `org.json` is not available in local unit tests,
         * which is the same reason ReactionCounts moved to shared.
         */
        fun classify(text: String): ScannedCode {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Unknown
            if (trimmed.startsWith(ShareUri.SCHEME, ignoreCase = true)) return MeshCoreUri
            if (trimmed.startsWith("{") && trimmed.contains(COMMUNITY_MARKER)) return Community
            return Unknown
        }

        /**
         * Pull a usable code out of [text], or null if there isn't one.
         *
         * A scanned QR is exactly a code and nothing else. Pasted text
         * is not: the commonest way a MeshCore contact travels between
         * clients is a `meshcore://` link on the clipboard — Liam
         * Cottle's client shares contacts by copying one, and does not
         * render a QR at all — and by the time it reaches someone it is
         * usually inside a sentence.
         *
         * So this finds the link within surrounding prose. It stops at
         * whitespace and nothing else: a percent-encoded name can end
         * in almost any punctuation, so trimming a trailing `.` or `)`
         * to be helpful would corrupt codes that were fine. A link
         * pasted mid-sentence with the full stop attached is the price,
         * and it fails visibly rather than silently importing the wrong
         * thing.
         */
        fun extract(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            // A community blob is JSON and has to arrive whole; a
            // `meshcore://` link is delimited by whitespace wherever it
            // sits. Returning early for anything that merely CLASSIFIED
            // meant the link-in-prose logic below never ran for the
            // commonest paste of all — the link first, the sentence
            // after it — so "meshcore://…&type=1 join us" reached the
            // decoder whole and came back "Malformed contact code".
            if (classify(trimmed) == Community) return trimmed
            val start = trimmed.indexOf(ShareUri.SCHEME, ignoreCase = true)
            if (start < 0) return null
            val rest = trimmed.substring(start)
            val end = rest.indexOfFirst { it.isWhitespace() }
            return if (end < 0) rest else rest.substring(0, end)
        }
    }
}
