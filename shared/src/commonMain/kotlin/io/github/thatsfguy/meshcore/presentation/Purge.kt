package io.github.thatsfguy.meshcore.presentation

/**
 * "Purge local data" (PARITY §1), worded once for both apps (2026-11-18).
 *
 * A purge removes this phone's copy only. The radio keeps its contact
 * list, channel slots and identity — and the wording must never imply
 * otherwise. Message history is the one thing that cannot be re-synced
 * afterwards, so the confirmation lists what goes and asks for a typed
 * word: "Are you sure?" is not consent when you can't see the list.
 */
object Purge {
    const val TITLE = "Purge local data"
    const val HINT = "Deletes this phone's copy of everything. It does NOT touch the radio: its " +
        "contact list, channel slots and identity are on the device and stay there."
    const val CONFIRM_WORD = "PURGE"

    val DELETES = listOf(
        "Every message, in every conversation",
        "The cached contact list and channel names",
        "Path history and the discovery inbox",
        "Nicknames, pins, mutes, and region labels",
    )
    val KEEPS = listOf(
        "Anything on the radio — contacts, channels and its identity stay",
        "Messages other people received from you",
    )
    const val NO_RECOVERY = "Your message history cannot be recovered — the radio does not keep a copy to re-sync from."
    const val SECRETS_TOGGLE = "Also forget stored keys and passwords"

    fun secretsWarning(what: String): String =
        "$what go too. Channels you have no other copy of the key for become unreadable."

    /** Whether the typed confirmation arms the Purge button. */
    fun armed(typed: String): Boolean = typed.trim().equals(CONFIRM_WORD, ignoreCase = true)

    /** What a purge removed, as facts. Null counts are stores this phone doesn't keep. */
    fun result(messages: Int, contacts: Int?, channels: Int?, alsoSecrets: Boolean): String = buildString {
        val parts = listOfNotNull("$messages message(s)", contacts?.let { "$it contact(s)" }, channels?.let { "$it channel(s)" })
        append("Purged ")
        append(parts.joinToString(", "))
        if (alsoSecrets) append(", and all stored keys and passwords")
        append(". The radio was not touched.")
    }
}
