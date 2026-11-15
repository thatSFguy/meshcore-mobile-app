package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * Who the "New message" picker on Chats offers.
 *
 * Before it existed, a direct message could only be started from the
 * Nodes tab, which is where repeaters, discovery and the stale-node sweep
 * live, and which Simple mode hides. So the picker lists PEOPLE only: chat
 * nodes. Repeaters and sensors take no chat, and a room needs a sign-in
 * first, which is the Nodes tab's job.
 *
 * Order: whoever you last talked to first, so the common case (carry on a
 * conversation) is the top row; then by name, so the rest is findable.
 */
object NewMessage {

    fun <T : NodeListItem> candidates(contacts: List<T>, typeOf: (T) -> Int, query: String): List<T> {
        val q = query.trim()
        return contacts
            .filter { typeOf(it) == Codes.ADV_TYPE_CHAT }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(
                compareByDescending<T> { it.lastMessageAt }
                    .thenBy { it.name.trim().lowercase() }
                    .thenBy { it.keyHex },
            )
    }
}
