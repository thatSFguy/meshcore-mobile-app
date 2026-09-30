package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * Input rules for the add-channel choices. Pure, so the rules are pinned
 * by tests rather than found on a phone.
 *
 * A channel name lives in a 32-byte field the firmware NUL-terminates
 * (`StrHelper::strncpy(channel.name, …, 32)`, companion_radio/MyMesh.cpp:1723),
 * so 31 bytes is the most a name can be.
 */
object ChannelSetup {

    const val MAX_NAME_BYTES = 31

    /** The Public channel's conventional label. */
    const val PUBLIC_NAME = "Public"

    private val HASHTAG = Regex("^[a-z0-9-]+$")

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Problem(val message: String) : Result<Nothing>
    }

    /**
     * A hashtag channel name, returned as the stored label "#name".
     * Lowercased, because the key is the hash of the exact text and a
     * capital would put you in a different channel from everyone else.
     */
    fun hashtag(input: String): Result<String> {
        val tag = input.trim().removePrefix("#").lowercase()
        return when {
            tag.isEmpty() -> Result.Problem("Enter the channel's name.")
            !HASHTAG.matches(tag) -> Result.Problem("Only a–z, 0–9 and hyphens.")
            ("#$tag").encodeToByteArray().size > MAX_NAME_BYTES ->
                Result.Problem("Too long — at most ${MAX_NAME_BYTES - 1} characters.")
            else -> Result.Ok("#$tag")
        }
    }

    /**
     * A private channel's label. It may be anything the radio can hold —
     * except a leading '#', which would read as a hashtag channel that
     * anyone can join, and it is not one.
     */
    fun privateName(input: String): Result<String> {
        val name = input.trim().filter { !it.isISOControl() }
        return when {
            name.isEmpty() -> Result.Problem("Enter a name.")
            name.startsWith("#") ->
                Result.Problem("A name starting with # is for hashtag channels.")
            name.encodeToByteArray().size > MAX_NAME_BYTES ->
                Result.Problem("Too long for the radio — at most $MAX_NAME_BYTES bytes.")
            else -> Result.Ok(name)
        }
    }

    /**
     * A private channel's secret key, as 32 hex characters (spaces
     * ignored). All zeros is refused: it is the empty-slot marker, not a
     * key.
     */
    fun privateKey(input: String): Result<ByteArray> {
        val hex = input.filter { !it.isWhitespace() }
        if (hex.isEmpty()) return Result.Problem("Enter the secret key.")
        if (hex.length != 32) return Result.Problem("The key is 32 hex characters (${hex.length} entered).")
        val key = hexToBytesOrNull(hex) ?: return Result.Problem("Only 0–9 and a–f.")
        if (key.all { it.toInt() == 0 }) return Result.Problem("A key can't be all zeros.")
        return Result.Ok(key)
    }
}

/**
 * The conversation subtitle for a channel: what kind it is and who can
 * read it. Every kind says someone other than its members can read it,
 * one way or another — MeshCore channels are obfuscated, not secure
 * (AES-ECB, 2-byte MAC), and the two public kinds need no key at all.
 * [kind] null means the key couldn't be read, so no kind is claimed.
 */
fun channelSubtitle(kind: io.github.thatsfguy.meshcore.protocol.ChannelKind?, region: String?): String {
    val head = when (kind) {
        // Short: the app bar has room for about 30 characters beside the
        // radio chip, and the longer wording was cut off mid-sentence.
        io.github.thatsfguy.meshcore.protocol.ChannelKind.Public -> "Public · anyone can read it"
        io.github.thatsfguy.meshcore.protocol.ChannelKind.Hashtag -> "Hashtag · anyone can read it"
        io.github.thatsfguy.meshcore.protocol.ChannelKind.Private -> "Private · not secure"
        null -> "Obfuscated, not secure"
    }
    return if (region != null) "$head · #$region" else head
}
