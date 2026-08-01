package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.toHex

/**
 * A node's Ed25519 identity key (PARITY.md §6,
 * `ChangeIdentityKeyScreen` / `ManageIdentityKeyScreen`).
 *
 * PARITY calls this the highest-consequence screen in the app, and the
 * reason is worth stating precisely rather than gesturing at:
 *
 *  - **The key IS the node.** MeshCore identity is the Ed25519 public
 *    key. Replace the private key and, to every other node on the mesh,
 *    the old node stopped existing and a stranger appeared with the same
 *    name. Contacts must re-add it; stored paths to it are meaningless;
 *    signed adverts from the old key never verify again.
 *  - **It is exfiltratable in one command.** `get prv.key` returns it
 *    over the air — through repeaters, and in the clear if the link is
 *    TCP. Anyone who has it can *be* that repeater.
 *  - **There is no revocation.** Nothing in the protocol says "that key
 *    is retired". The only remedy is telling every human involved.
 *
 * So this file does two small things carefully: it validates key
 * material before it can be sent, and it says what a change costs. It
 * deliberately does NOT offer to remember a repeater's private key —
 * that would put another node's identity in this phone's keystore for
 * no benefit the user asked for.
 */
object IdentityKey {

    /** Ed25519 seed/private key: 32 bytes, 64 hex characters. */
    const val KEY_HEX_LENGTH = 64

    /**
     * Canonical lowercase hex, or null when [raw] is not a 32-byte key.
     *
     * Whitespace and a `0x` prefix are tolerated because people paste
     * these from all sorts of places; nothing else is. In particular a
     * short value is never zero-padded — silently padding a mistyped key
     * would hand the node an identity the user never chose.
     */
    fun canonicalHex(raw: String?): String? {
        val cleaned = raw?.trim()?.removePrefix("0x")?.removePrefix("0X")
            ?.filterNot { it == ' ' || it == ':' || it == '\n' || it == '\r' || it == '\t' }
            ?.lowercase()
            ?: return null
        if (cleaned.length != KEY_HEX_LENGTH) return null
        return cleaned.takeIf { k -> k.all { it in "0123456789abcdef" } }
    }

    fun isValidHex(raw: String?): Boolean = canonicalHex(raw) != null

    /**
     * True for keys that are structurally valid but obviously unsafe —
     * all-zero, all-ones, or a repeating single byte. A key like this
     * usually means a placeholder or a truncated paste, and accepting
     * one produces a node whose identity anybody can reproduce.
     */
    fun isDegenerate(hex: String?): Boolean {
        val key = canonicalHex(hex) ?: return false
        val bytes = key.chunked(2).map { it.toInt(16) }
        return bytes.distinct().size <= 1
    }

    /** A fresh random seed, as canonical hex. */
    fun generate(crypto: CryptoProvider): String = crypto.generateEd25519Seed().toHex()

    /**
     * The public key a seed produces, as hex — so a change can be shown
     * as "this node will become <key>" before it is made, rather than
     * discovered afterwards.
     *
     * Returns null where the platform can't derive it (iOS Phase 1
     * stubs return an empty array rather than throwing).
     */
    fun publicKeyHex(crypto: CryptoProvider, seedHex: String): String? {
        val seed = canonicalHex(seedHex) ?: return null
        val bytes = ByteArray(32) { seed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val pub = runCatching { crypto.ed25519PublicKey(bytes) }.getOrNull() ?: return null
        return if (pub.size != 32) null else pub.toHex()
    }

    /** The `set prv.key <hex>` command; throws on anything invalid. */
    fun setCommand(hex: String): String {
        val key = canonicalHex(hex)
            ?: throw IllegalArgumentException("not a 32-byte key")
        require(!isDegenerate(key)) { "refusing a degenerate key" }
        return "set prv.key $key"
    }

    /** The read command. Its REPLY contains the key — never log it. */
    fun getCommand(): String = "get prv.key"

    /**
     * What the user is agreeing to. Kept next to the code so the warning
     * and the behaviour can't drift apart.
     */
    val CHANGE_CONSEQUENCES: List<String> = listOf(
        "This node's identity is its key. Change it and, to everyone else, this node " +
            "vanishes and a new one appears wearing its name.",
        "Every contact that has it saved must add it again. Nobody is notified.",
        "Stored paths and routes to this node stop meaning anything.",
        "Old signed adverts from this node will never verify again.",
        "There is no revocation and no undo. If you don't have the current key written " +
            "down somewhere safe, it is gone the moment this is applied.",
    )

    /** Shown before revealing a key that is about to travel over the air. */
    const val REVEAL_CAVEAT =
        "Reading the key sends it back over the mesh — through every repeater on the " +
            "path, and in the clear if this link is TCP. Anyone who captures it can " +
            "impersonate this node permanently."
}
