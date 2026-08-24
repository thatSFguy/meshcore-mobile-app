package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.isHexDigits

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
 *  - **It is exfiltratable in one command.** `get prv.key` returns it —
 *    over USB serial only (see [READS_ARE_SERIAL_ONLY]), but it is one
 *    command and there is nothing else protecting it. Anyone who has it
 *    can *be* that repeater.
 *  - **There is no revocation.** Nothing in the protocol says "that key
 *    is retired". The only remedy is telling every human involved.
 *
 * So this file does two small things carefully: it validates key
 * material before it can be sent, and it says what a change costs. It
 * deliberately does NOT offer to remember a repeater's private key —
 * that would put another node's identity in this phone's keystore for
 * no benefit the user asked for.
 *
 * ## The wire form is 64 bytes, not 32
 *
 * This shipped wrong, and every key the app generated was rejected by
 * the node with `Error, bad key`. The firmware's reader is unambiguous:
 *
 *  - `uint8_t prv_key[PRV_KEY_SIZE]; fromHex(prv_key, PRV_KEY_SIZE, &config[8])`
 *    (`src/helpers/CommonCLI.cpp:510-512`)
 *  - `#define PRV_KEY_SIZE 64` (`src/MeshCore.h:9`)
 *  - `if (len != dest_size*2) return false;  // incorrect length`
 *    (`src/Utils.cpp:206-208`)
 *
 * So `set prv.key` takes **128 hex characters** — the *expanded* private
 * key, `SHA512(seed)` with standard Ed25519 clamping, which is
 * `[clamped scalar (32) || nonce prefix (32)]`. The app had been sending
 * the 32-byte seed as 64 hex characters, which `fromHex` rejects on
 * length before it looks at a single digit.
 *
 * The same 64-byte form comes back out: `get prv.key` writes
 * `PRV_KEY_SIZE` bytes (`CommonCLI.cpp:832-836` via
 * `LocalIdentity::writeTo`, `src/Identity.cpp:128-138`), so a reply
 * validated as 64 hex characters never matched either — the "Read key…"
 * button could only ever report that the node had refused.
 *
 * Both forms are accepted as *input* here, because a person restoring a
 * node has whichever one they wrote down, and the seed is the shorter
 * thing to write down. Only the 64-byte form is ever sent.
 */
object IdentityKey {

    /** Ed25519 seed: 32 bytes, 64 hex characters. */
    const val SEED_HEX_LENGTH = 64

    /**
     * The form `set prv.key` takes: `PRV_KEY_SIZE` = 64 bytes, 128 hex
     * characters. See the class docs for the firmware citations.
     */
    const val PRIVATE_KEY_HEX_LENGTH = 128

    /**
     * Canonical lowercase hex for either accepted form — a 32-byte seed
     * or the 64-byte expanded private key — or null for anything else.
     *
     * Whitespace and a `0x` prefix are tolerated because people paste
     * these from all sorts of places; nothing else is. In particular a
     * short value is never zero-padded — silently padding a mistyped key
     * would hand the node an identity the user never chose. Nor is a
     * 64-character value silently treated as a truncated 128-character
     * one: they are different keys, not the same key at two lengths.
     */
    fun canonicalHex(raw: String?): String? {
        val cleaned = raw?.trim()?.removePrefix("0x")?.removePrefix("0X")
            ?.filterNot { it == ' ' || it == ':' || it == '\n' || it == '\r' || it == '\t' }
            ?.lowercase()
            ?: return null
        if (cleaned.length != SEED_HEX_LENGTH && cleaned.length != PRIVATE_KEY_HEX_LENGTH) {
            return null
        }
        return cleaned.takeIf { isHexDigits(it) }
    }

    /** Canonical hex, but only for the 32-byte seed form. */
    fun canonicalSeedHex(raw: String?): String? =
        canonicalHex(raw)?.takeIf { it.length == SEED_HEX_LENGTH }

    /** Canonical hex, but only for the 64-byte form the firmware reads. */
    fun canonicalPrivateKeyHex(raw: String?): String? =
        canonicalHex(raw)?.takeIf { it.length == PRIVATE_KEY_HEX_LENGTH }

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

    /**
     * The firmware's own acceptance rule for the public key a private
     * key produces: `if (pub[0] == 0x00 || pub[0] == 0xFF) return false`
     * (`LocalIdentity::validatePrivateKey`, `src/Identity.cpp:71-72`).
     *
     * About one key in 128 fails this. Generating without checking it
     * means a rekey that fails for no reason the user can see, once in a
     * while, on the one screen where "try again" is the worst possible
     * advice — so [IdentityKeygen] never offers a key that fails here.
     */
    fun isAcceptablePublicKey(publicKeyHex: String?): Boolean {
        val pub = publicKeyHex?.lowercase() ?: return false
        if (pub.length != 64 || !isHexDigits(pub)) return false
        val first = pub.substring(0, 2)
        return first != "00" && first != "ff"
    }

    /** A fresh random seed, as canonical hex. */
    fun generate(crypto: CryptoProvider): String = crypto.generateEd25519Seed().toHex()

    /**
     * The 64-byte form of a 32-byte seed — `SHA512(seed)`, clamped.
     *
     * Pinned by the firmware's own known-good keypair: the scalar half
     * of `test_client_prv` (`src/Identity.cpp:75-84`) is clamped exactly
     * this way and scalar-multiplies to `test_client_pub`.
     */
    fun expandSeedHex(crypto: CryptoProvider, seedHex: String): String? {
        val seed = canonicalSeedHex(seedHex) ?: return null
        return MeshIdentity.expandSeed(crypto, hexBytes(seed)).toHex()
    }

    /**
     * What actually goes on the wire, from either accepted input form:
     * 128 hex characters. A seed is expanded; a 64-byte key is passed
     * through unchanged.
     */
    fun wireKeyHex(crypto: CryptoProvider, raw: String?): String? {
        val key = canonicalHex(raw) ?: return null
        return if (key.length == PRIVATE_KEY_HEX_LENGTH) key else expandSeedHex(crypto, key)
    }

    /**
     * The public key a **seed** produces, as hex — so a change can be
     * shown as "this node will become <key>" before it is made, rather
     * than discovered afterwards.
     *
     * Returns null for the 64-byte form: deriving a public key from an
     * expanded private key is a scalar multiplication by the base point,
     * and neither platform's Ed25519 exposes one (Bouncy Castle's
     * `Ed25519` takes a seed; CryptoKit takes a seed). The node reports
     * the resulting key itself — `"OK, reboot to apply! New pubkey: …"`
     * (`CommonCLI.cpp:517-519`) — so nothing is lost except the preview,
     * and inventing a preview we cannot compute would be worse.
     */
    fun publicKeyHex(crypto: CryptoProvider, seedHex: String): String? {
        val seed = canonicalSeedHex(seedHex) ?: return null
        val pub = runCatching { crypto.ed25519PublicKey(hexBytes(seed)) }.getOrNull() ?: return null
        return if (pub.size != 32) null else pub.toHex()
    }

    /**
     * The `set prv.key <128 hex>` command; throws on anything invalid.
     *
     * Takes [crypto] because a seed has to be expanded before it can be
     * sent — the builder is the only place that knows what the firmware
     * reads, so it is the only place that should be deciding this.
     */
    fun setCommand(crypto: CryptoProvider, hex: String): String {
        val key = canonicalHex(hex)
            ?: throw IllegalArgumentException("not a 32- or 64-byte key")
        require(!isDegenerate(key)) { "refusing a degenerate key" }
        val wire = wireKeyHex(crypto, key)
            ?: throw IllegalArgumentException("could not expand that key")
        require(!isDegenerate(wire)) { "refusing a degenerate key" }
        return "set prv.key $wire"
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
        "The node keeps running the old key until it reboots.",
        "There is no revocation and no undo. If you don't have the current key written " +
            "down somewhere safe, it is gone the moment this is applied.",
    )

    /** Shown before revealing a key that is about to travel over the air. */
    const val REVEAL_CAVEAT =
        "Reading the key sends it back over the mesh — through every repeater on the " +
            "path, and in the clear if this link is TCP. Anyone who captures it can " +
            "impersonate this node permanently."

    /**
     * The firmware answers `get prv.key` only when the request arrived
     * with `sender_timestamp == 0` — which is to say over the USB serial
     * console, never from a remote admin session
     * (`CommonCLI.cpp:832`, commented "from serial command line only").
     *
     * Worth saying out loud on the screen: without it, a remote read
     * looks like a connection problem, and the honest answer is that the
     * node is refusing on purpose and always will.
     */
    const val READS_ARE_SERIAL_ONLY =
        "A node only answers this over its USB serial console — never from a remote " +
            "admin session. Over the mesh it will refuse, however good the link is."

    /**
     * What a node said when asked to take a new key.
     *
     * The firmware's own words, decoded once. `set prv.key` is the rare
     * command that hands back something worth keeping — the node's new
     * public key — and until now the app printed the whole reply as a
     * note and threw the key away, which is why a rekeyed repeater then
     * had to be waited for.
     */
    sealed interface RekeyReply {
        /**
         * The node stored the key and reported the public half.
         *
         * It is still running the OLD identity at this point: the
         * firmware saves and says "reboot to apply".
         */
        data class Accepted(val newPublicKeyHex: String) : RekeyReply

        /** The node refused — bad length, or a key it will not hold. */
        data class Refused(val text: String) : RekeyReply

        /** Something came back that this parser does not recognise. */
        data class Unrecognised(val text: String) : RekeyReply

        /** Nothing came back at all. */
        data object NoAnswer : RekeyReply
    }

    /**
     * The exact prefix the firmware writes before the new public key:
     *
     * ```c
     * strcpy(reply, "OK, reboot to apply! New pubkey: ");
     * mesh::Utils::toHex(&reply[33], new_id.pub_key, PUB_KEY_SIZE);
     * ```
     * (`CommonCLI.cpp:518-519`)
     *
     * Thirty-three characters, which is where the hex is written — the
     * firmware indexes past its own string rather than appending, so
     * prefix and offset are the same fact stated twice and a test pins
     * them against each other.
     */
    const val REKEY_ACCEPTED_PREFIX = "OK, reboot to apply! New pubkey: "

    /** What the firmware says when it will not take the key. */
    const val REKEY_REFUSED = "Error, bad key"

    /**
     * Decode the reply to [setCommand].
     *
     * Deliberately strict about the key and forgiving about the frame
     * around it: the 64 hex characters either parse as a public key or
     * this is not an acceptance, because the entire value of reading
     * this reply is that the app can stop waiting for an advert — and a
     * half-read key would put a node in the contact list under a name
     * nothing on the mesh answers to.
     */
    fun parseRekeyReply(reply: String?): RekeyReply {
        val text = reply?.trim() ?: return RekeyReply.NoAnswer
        if (text.isEmpty()) return RekeyReply.NoAnswer
        if (text.startsWith(REKEY_REFUSED, ignoreCase = true)) return RekeyReply.Refused(text)
        if (!text.startsWith(REKEY_ACCEPTED_PREFIX, ignoreCase = true)) {
            return RekeyReply.Unrecognised(text)
        }
        // Trailing whitespace or a stray null from the C string are the
        // node's, not the key's. Anything else after 64 hex characters
        // means this is not the reply it looks like.
        val tail = text.substring(REKEY_ACCEPTED_PREFIX.length).trim().trimEnd('\u0000')
        val key = tail.lowercase()
        if (key.length != PUBLIC_KEY_HEX_LENGTH || !key.all { it in "0123456789abcdef" }) {
            return RekeyReply.Unrecognised(text)
        }
        return RekeyReply.Accepted(key)
    }

    /** A public key is 32 bytes on the wire, 64 characters in a reply. */
    const val PUBLIC_KEY_HEX_LENGTH = 64

    /** The command that applies a stored key. */
    const val REBOOT_COMMAND = "reboot"

    /**
     * There is no such thing as a confirmed reboot.
     *
     * `CommonCLI.cpp:185` is `_board->reboot(); // doesn't return` — the
     * reply buffer is never written, so no reply is sent. Nor is there
     * an ACK: a repeater acknowledges only `TXT_TYPE_PLAIN` messages
     * ("for legacy CLI", `MyMesh.cpp:717`) and every command this app
     * sends is `TXT_TYPE_CLI_DATA`. Compare `advert`, which delays 1500
     * ms explicitly to "give CLI response time to be sent first" — the
     * firmware knows the difference and does not try here.
     *
     * So the only honest report is that OUR radio transmitted it. The
     * screen must not promise more, and silence afterwards must never be
     * presented as a failure: silence is the expected outcome.
     */
    const val REBOOT_HAS_NO_ANSWER =
        "A node never answers a reboot — the firmware restarts before it can reply, and " +
            "sends no acknowledgement either. Silence here is what success looks like."

    /**
     * Why the new identity has to be written locally rather than waited
     * for.
     *
     * A repeater adverts on boot — `sendSelfAdvertisement(16000, false)`
     * (`examples/simple_repeater/main.cpp:119`) — but that `false` is
     * **zero-hop**: only nodes in direct radio range hear it. A repeater
     * reached over hops is not one of them. The next advert that
     * actually propagates is the flood advert, whose firmware default is
     * `flood_advert_interval = 47` hours.
     */
    const val NEW_IDENTITY_IS_NOT_ANNOUNCED =
        "A rebooted node announces itself only to radios in direct range, and its next " +
            "flooded advert can be up to 47 hours away. That is why the new identity is " +
            "written here rather than waited for."

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
