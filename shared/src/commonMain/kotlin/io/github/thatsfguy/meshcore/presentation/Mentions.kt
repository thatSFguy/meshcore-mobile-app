package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * `@[Name]` — tagging somebody in a message.
 *
 * **A client convention, not a protocol feature**, and in exactly the
 * sense MESHCORE_PROTOCOL §14 uses for reactions: MeshCore has no
 * mention field, so this is plain text in an ordinary message body that
 * clients agree to render specially. It is a good deal more settled
 * than reactions are — reactions have two mutually unintelligible
 * formats, whereas `@[Name]` is one form, introduced by the official
 * MeshCore app (`@` opens an autocomplete over names seen on the
 * channel; Reply prefills `@[Sender]: `) and followed by the others.
 *
 * Two consequences of it being text, both of which the UI has to
 * respect rather than paper over:
 *
 *  - **A mention names a NAME, never a key.** There is no public key in
 *    the wire form, so "who was tagged" is a string comparison and
 *    nothing stronger. Two nodes may answer to it; none may.
 *  - **It is unauthenticated, like every channel sender name (§12).**
 *    Anyone can advertise any name, so anyone can be tagged as anyone,
 *    and anyone can *appear* to tag you. Highlighting a message that
 *    tags you is a reading aid. It is not evidence that the person who
 *    sent it is who the message says, and nothing here may be used to
 *    decide trust, identity, or who a reply goes to.
 *
 * The brackets are what makes the form parseable at all — a bare
 * `@name` cannot be told apart from an email address or from prose, and
 * names on this mesh routinely contain spaces (`Blue Base`), which a
 * whitespace-delimited token could never carry.
 */
object Mentions {

    /** One `@[Name]` in a message, with where it sits in the text. */
    data class Span(val range: IntRange, val name: String)

    /**
     * The result of accepting an autocomplete: the new text, and where
     * the caret goes.
     *
     * The caret is returned rather than left to the caller because
     * "after the inserted mention" is not a position the caller can
     * work out without re-implementing the insertion.
     */
    data class Completion(val text: String, val cursor: Int)

    /** How long a query may run before it has plainly stopped being a name. */
    private const val MAX_QUERY = Codes.MAX_NAME_SIZE

    /**
     * Every `@[Name]` in [text], in order.
     *
     * Deliberately literal: the name is everything between `@[` and the
     * FIRST `]`, with no escaping. There is no escape sequence in the
     * convention to implement, and inventing one would emit bytes no
     * other client reads.
     */
    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        while (i < text.length) {
            val at = text.indexOf("@[", i)
            if (at < 0) break
            val close = text.indexOf(']', at + 2)
            if (close < 0) break
            val name = text.substring(at + 2, close)
            // `@[]` is not a mention of anybody. Skip past the `@[` only,
            // so a following `@[Real]` inside the same stretch is found.
            if (name.isBlank()) {
                i = at + 2
                continue
            }
            out.add(Span(at..close, name))
            i = close + 1
        }
        return out
    }

    /** Just the names, deduplicated, preserving first appearance. */
    fun namesIn(text: String): List<String> =
        spans(text).map { it.name }.distinctBy { it.trim().lowercase() }

    /**
     * True when [text] tags [selfName].
     *
     * Case-insensitive and trimmed, because the name is retyped or
     * autocompleted by a person and the wire carries no key to fall
     * back on. See the class note: a true answer here means "this
     * message contains your name in brackets", and never "this message
     * is from someone who knows you".
     */
    fun tags(text: String, selfName: String?): Boolean {
        val me = selfName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return spans(text).any { it.name.trim().lowercase() == me }
    }

    /**
     * True when [name] can be written as a mention at all.
     *
     * A name holding `]` would terminate its own bracket, so the mention
     * would parse as a shorter name and tag somebody else — or nobody.
     * There is no escaping in the convention, so such a name is simply
     * not offered rather than emitted broken. Node names containing
     * brackets are legal on the wire, which is why this is checked
     * rather than assumed.
     */
    fun canMention(name: String): Boolean =
        name.isNotBlank() && ']' !in name && '[' !in name

    /** The wire form. */
    fun wire(name: String): String = "@[$name]"

    /**
     * What the composer is currently typing after an `@`, or null.
     *
     * Returns "" right after a bare `@`, which is what opens the picker
     * showing everything. Returns null when the caret is not in a
     * mention at all, or is inside one that is already complete —
     * re-suggesting into a finished `@[Blue Base]` would let a tap
     * corrupt it.
     *
     * The query may contain spaces. It has to: `Blue Base` is an
     * ordinary node name, and a whitespace-delimited token could never
     * match it. The cost is that an `@` typed in prose keeps a query
     * alive for a few words — which is harmless, because the picker is
     * shown only while something still matches.
     */
    fun activeQuery(text: String, cursor: Int): String? {
        if (cursor < 0 || cursor > text.length) return null
        val at = text.lastIndexOf('@', (cursor - 1).coerceAtLeast(0))
        if (at < 0 || at >= cursor) return null

        // An `@` glued to the end of a word is an address or an
        // ordinary character, not the start of a tag.
        val before = text.getOrNull(at - 1)
        if (before != null && (before.isLetterOrDigit() || before == '.')) return null

        var body = text.substring(at + 1, cursor)
        // A newline ends any tag: the picker must not survive the
        // message it was opened in.
        if ('\n' in body) return null
        if (body.length > MAX_QUERY) return null
        if (body.startsWith("[")) {
            // Already inside brackets. If it has been closed, the caret
            // is past a finished mention and there is nothing to
            // complete; if not, the text so far is the query.
            if (']' in body) return null
            body = body.substring(1)
        }
        return body
    }

    /**
     * Names from [names] that [query] is a prefix of, best first.
     *
     * Prefix, then substring: typing `ba` should reach `Blue Base`, but
     * a node actually called `Base` has the better claim to the top of
     * the list. Case-insensitive, deduplicated, and names that cannot
     * be written as a mention are dropped rather than offered and then
     * emitted wrong.
     */
    fun candidates(query: String, names: List<String>, limit: Int = 6): List<String> {
        val q = query.trim().lowercase()
        val usable = names
            .map { it.trim() }
            .filter { canMention(it) }
            .distinctBy { it.lowercase() }
        val ranked = if (q.isEmpty()) {
            usable
        } else {
            val prefix = usable.filter { it.lowercase().startsWith(q) }
            val contains = usable.filter { !it.lowercase().startsWith(q) && q in it.lowercase() }
            prefix + contains
        }
        return ranked.take(limit)
    }

    /**
     * Replace the query the caret sits in with a complete `@[Name] `.
     *
     * The trailing space is part of the insertion: without it the next
     * character typed lands against `]` and every message reads
     * `@[Blue Base]hello`. Returns [text] unchanged when the caret is
     * not in a query, so a stale tap cannot rewrite the message.
     */
    fun complete(text: String, cursor: Int, name: String): Completion {
        activeQuery(text, cursor) ?: return Completion(text, cursor)
        if (!canMention(name.trim())) return Completion(text, cursor)
        val at = text.lastIndexOf('@', (cursor - 1).coerceAtLeast(0))
        if (at < 0) return Completion(text, cursor)
        // The span being replaced is the `@` through the caret, whatever
        // the query turned out to be — measuring it from the returned
        // query instead would be off by one whenever the user had
        // already typed the opening bracket.
        val inserted = wire(name.trim()) + " "
        val head = text.substring(0, at)
        val tail = text.substring(cursor)
        return Completion(head + inserted + tail, at + inserted.length)
    }

    /**
     * What Reply seeds the composer with, or "" when there is nobody to
     * name.
     *
     * This is the official app's shape (`@[Sender]: `) and it is worth
     * being explicit that it is NOT this app's quote-reply: a quote
     * carries the text being answered and costs that many bytes out of
     * a ~150-byte frame, while this costs the name and a colon. They
     * answer different questions and the composer offers both.
     */
    fun replyPrefix(senderName: String?): String {
        val name = senderName?.trim().orEmpty()
        if (!canMention(name)) return ""
        return wire(name) + ": "
    }
}
