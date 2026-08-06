package io.github.thatsfguy.meshcore.presentation

/**
 * Whether an arriving message should raise an unread badge or interrupt
 * the user.
 *
 * Small rule, disproportionate consequences: get it wrong in one
 * direction and the app buzzes for a conversation already on screen;
 * wrong in the other and messages arrive in silence. It was five
 * hand-written `"$kind|$peerKey"` comparisons inside
 * `MessageRepository`, which is the shape that has produced six
 * separate defects in this codebase — a value built in several places
 * and updated in one.
 *
 * Pure, so iOS inherits the rule rather than re-deriving it. A second
 * implementation of "is this thread open" is a second chance to get it
 * subtly wrong, and the symptom — occasional spurious notifications —
 * is one nobody reports precisely.
 */
object Inbox {

    const val KIND_DM = "dm"
    const val KIND_CHANNEL = "ch"

    /**
     * The identity of one conversation, as the UI reports which one it
     * is showing. One function so the separator can never disagree with
     * itself.
     */
    fun threadKey(kind: String, peerKey: String): String = "$kind|$peerKey"

    /** Whether [activeThread] is the conversation currently on screen. */
    fun isOpen(activeThread: String?, kind: String, peerKey: String): Boolean =
        activeThread != null && activeThread == threadKey(kind, peerKey)

    /**
     * Whether an arrival should bump the thread's unread count.
     *
     * False when the user is already looking at it — a badge on the
     * screen you are reading is noise.
     */
    fun shouldBumpUnread(activeThread: String?, kind: String, peerKey: String): Boolean =
        !isOpen(activeThread, kind, peerKey)

    /**
     * Whether an arrival should raise a notification.
     *
     * [isDuplicate] covers the message that reaches us twice — once via
     * companion sync and once via the RX log — where the second copy is
     * bounced by the database and must not notify again.
     *
     * [isCliReply] is remote-admin console output arriving on the
     * message path. It is not conversation and must never interrupt;
     * notifying on it would make every `get freq` buzz the phone.
     */
    fun shouldNotify(
        activeThread: String?,
        kind: String,
        peerKey: String,
        isDuplicate: Boolean = false,
        isCliReply: Boolean = false,
    ): Boolean = when {
        isDuplicate -> false
        isCliReply -> false
        else -> !isOpen(activeThread, kind, peerKey)
    }
}
