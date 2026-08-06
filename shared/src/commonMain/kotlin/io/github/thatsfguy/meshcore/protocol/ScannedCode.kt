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
    }
}
