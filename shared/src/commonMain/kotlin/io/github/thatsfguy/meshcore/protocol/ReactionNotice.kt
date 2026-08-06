package io.github.thatsfguy.meshcore.protocol

/**
 * The one-line body of a "someone reacted to you" notification.
 *
 * A reaction is often the whole reply — a thumbs-up on "leaving about
 * six" means the same as typing "ok" — and until now it arrived
 * completely silently: reactions never become message rows, so they
 * never reached the notification path that ordinary messages take.
 *
 * The text quotes what was reacted to, because the emoji alone is
 * meaningless out of context. It quotes the *reply body* rather than
 * the raw text, so reacting to a reply doesn't produce a notification
 * about the message that reply was answering ([Quoting]).
 */
object ReactionNotice {

    /** Characters of the reacted-to message to include. */
    const val TARGET_MAX_CHARS: Int = 60

    /**
     * `👍 to "leaving about six"`, or just the emoji when the target
     * text is empty or is itself unrenderable (a reaction to a
     * reaction).
     */
    fun text(emoji: String, targetText: String): String {
        val body = Quoting.oneLine(Quoting.previewBody(targetText), TARGET_MAX_CHARS)
        // A reaction whose target is another reaction would otherwise
        // quote raw wire text ("r:1a2b:00") into a notification.
        val quotable = body.isNotBlank() && Reactions.parse(targetText) == null
        return if (quotable) "$emoji to \"$body\"" else emoji
    }
}
