package io.github.thatsfguy.meshcore.protocol

/**
 * MeshCore reactions.
 *
 * MeshCore has no reaction field: a reaction is an ordinary text message
 * of the form `r:HHHH:II`, where `HHHH` identifies the message being
 * reacted to and `II` is a two-hex index into a FIXED emoji list. The
 * convention comes from the MeshCore Open client (`reaction_helper.dart`)
 * and is reproduced here exactly, because the only thing that makes a
 * reaction land on the right message in someone else's app is emitting
 * the same bytes they would.
 *
 * Three properties of that design are worth knowing, since they bound
 * what this app can promise:
 *
 *  - **The target id is a 16-bit hash** of `timestamp + [sender] + first
 *    5 characters of the text`. Collisions are entirely possible in a
 *    busy channel, so a matched reaction is a best guess, never proof.
 *  - **The hash function is Dart's `String.hashCode`** (see
 *    [DartStringHash]) — an implementation detail of another runtime that
 *    this code has to mirror.
 *  - **The emoji list is positional.** Inserting an emoji anywhere but
 *    the end would silently change what every existing index means, so
 *    [ALL] must only ever be appended to.
 *
 * A client that doesn't implement this shows the raw `r:1a2b:00` text,
 * which is why unmatched reactions are still worth rendering as
 * something human-readable rather than dropped.
 */
object Reactions {

    /** `r:` + 4 hex target + `:` + 2 hex emoji index. */
    private val WIRE = Regex("^r:([0-9a-f]{4}):([0-9a-f]{2})$")

    /** quickEmojis — 6 entries, order fixed by the wire index. */
    val QUICK: List<String> = listOf(
        "👍", "❤️", "😂", "🎉", "👏", "🔥",
    )

    /** smileys — 64 entries, order fixed by the wire index. */
    val SMILEYS: List<String> = listOf(
        "😀", "😃", "😄", "😁", "😅", "😂", "🤣", "😊",
        "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘",
        "😗", "😙", "😚", "😋", "😛", "😝", "😜", "🤪",
        "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "😏",
        "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖",
        "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡",
        "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰",
        "😥", "😓", "🤗", "🤔", "🤭", "🤫", "🤥", "😶",
    )

    /** gestures — 33 entries, order fixed by the wire index. */
    val GESTURES: List<String> = listOf(
        "👍", "👎", "👊", "✊", "🤛", "🤜", "🤞", "✌️",
        "🤟", "🤘", "👌", "🤌", "🤏", "👈", "👉", "👆",
        "👇", "☝️", "👋", "🤚", "🖐️", "✋", "🖖", "👏",
        "🙌", "👐", "🤲", "🤝", "🙏", "✍️", "💅", "🤳",
        "💪",
    )

    /** hearts — 32 entries, order fixed by the wire index. */
    val HEARTS: List<String> = listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
        "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗",
        "💖", "💘", "💝", "💟", "💌", "💢", "💥", "💫",
        "💦", "💨", "🕳️", "💬", "👁️‍🗨️", "🗨️", "🗯️", "💭",
    )

    /** objects — 49 entries, order fixed by the wire index. */
    val OBJECTS: List<String> = listOf(
        "🎉", "🎊", "🎈", "🎁", "🎀", "🪅", "🪆", "🏆",
        "🥇", "🥈", "🥉", "⚽", "⚾", "🥎", "🏀", "🏐",
        "🏈", "🏉", "🎾", "🥏", "🎳", "🏏", "🏑", "🏒",
        "🥍", "🏓", "🏸", "🥊", "🥋", "🥅", "⛳", "🔥",
        "⭐", "🌟", "✨", "⚡", "💡", "🔦", "🏮", "🪔",
        "📱", "💻", "⌚", "📷", "📺", "📻", "🎵", "🎶",
        "🚀",
    )
    /** Emoji in wire-index order: quick, then smileys, gestures, hearts, objects. */
    val ALL: List<String> = QUICK + SMILEYS + GESTURES + HEARTS + OBJECTS

    data class Reaction(val targetHash: String, val emoji: String)

    /** Parse a received message; null when it isn't a reaction at all. */
    fun parse(text: String): Reaction? {
        val m = WIRE.matchEntire(text.trim()) ?: return null
        val emoji = emojiForIndex(m.groupValues[2]) ?: return null
        return Reaction(targetHash = m.groupValues[1], emoji = emoji)
    }

    /** Build the wire text for reacting to [targetHash] with [emoji]. */
    fun encode(targetHash: String, emoji: String): String? {
        val index = ALL.indexOf(emoji).takeIf { it >= 0 } ?: return null
        return "r:$targetHash:" + index.toString(16).padStart(2, '0')
    }

    fun emojiForIndex(hexIndex: String): String? {
        val index = hexIndex.toIntOrNull(16) ?: return null
        return ALL.getOrNull(index)
    }

    /**
     * The 4-hex target id for a message.
     *
     * [senderName] is null for direct messages (the sender is implicit);
     * channel messages include it, matching the reference client. Note
     * that this makes the id depend on UNAUTHENTICATED display text — it
     * is a matching key, never an identity check.
     */
    fun targetHash(timestampSeconds: Long, senderName: String?, text: String): String {
        val first5 = if (text.length >= 5) text.substring(0, 5) else text
        val input = if (senderName != null) {
            "$timestampSeconds$senderName$first5"
        } else {
            "$timestampSeconds$first5"
        }
        val hash = DartStringHash.of(input) and 0xFFFF
        return hash.toString(16).padStart(4, '0')
    }
}
