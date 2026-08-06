package io.github.thatsfguy.meshcore.protocol

/**
 * How a message reads in a notification.
 *
 * A quote-reply is one string on the wire — the quoted text, then a
 * newline, then the reply — so a notification that prints it raw shows
 * `>yeah good` and leaves the reader to work out which half is which.
 * Worse, the quote comes FIRST, so the collapsed one-line notification
 * shows the message being answered and hides the answer.
 *
 * Two forms, because a notification has two:
 *
 *  - [collapsed] is the single line Android shows before you expand it.
 *    That line is the **reply**. What someone just said to you is the
 *    thing worth the interruption; what they were answering is context
 *    you already have.
 *  - [expanded] keeps the context, marked, above the reply:
 *
 *    ```
 *    ↩ Kaylee: yeah
 *    good
 *    ```
 *
 * The list preview fix in [Quoting] fixed one caller of the same bug
 * and left this one, which is what a copy-per-caller split earns you.
 */
data class MessageNotice(val collapsed: String, val expanded: String) {

    companion object {
        /** Marks the quoted line as context rather than as the message. */
        const val QUOTE_MARK: String = "↩ "

        /** How much of the quoted message to keep in the expanded view. */
        const val QUOTE_MAX_CHARS: Int = 80

        /**
         * Split [text] into the two notification forms.
         *
         * Anything without a leading quote — an ordinary message, or an
         * already-formatted reaction notice — passes through unchanged
         * in both forms.
         */
        fun forMessage(text: String): MessageNotice {
            val (quoted, body) = Quoting.split(text)
            if (quoted == null) return MessageNotice(text, text)

            val context = QUOTE_MARK + Quoting.oneLine(quoted, QUOTE_MAX_CHARS)
            // A quote with nothing after it: the quote IS the message, so
            // show it rather than notifying about an empty string.
            if (body.isBlank()) return MessageNotice(context, context)

            return MessageNotice(collapsed = body, expanded = "$context\n$body")
        }
    }
}
