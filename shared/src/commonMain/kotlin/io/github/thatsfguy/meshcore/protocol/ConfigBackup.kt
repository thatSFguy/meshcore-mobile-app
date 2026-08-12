package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.isHexString

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.crypto.pbkdf2HmacSha256
import io.github.thatsfguy.meshcore.util.toHex

/**
 * Config backup / restore (PARITY.md §1).
 *
 * ## What this is allowed to contain
 *
 * A backup is a file. It leaves the phone, the Keystore, and every
 * guarantee either of them provides — so the split here is the whole
 * design:
 *
 *  - **The plain section** carries only what is already public or
 *    harmless: preferences, contact public keys and names, channel
 *    *names*, region labels. Anyone who reads it learns who you talk to,
 *    which is not nothing — the UI says so — but it cannot impersonate
 *    you or read your channels.
 *  - **The sealed section** carries channel PSKs, repeater login
 *    passwords, community secrets and the identity seed. It exists only
 *    when the user supplies a passphrase, and it is AES-256-GCM under a
 *    PBKDF2-HMAC-SHA256 key. There is no third option: secrets are
 *    never written in the clear, at any verbosity, for any reason.
 *
 * The KDF parameters and format version are authenticated as GCM
 * additional data, so an attacker cannot edit the file to claim a
 * 1-iteration KDF and have us believe it.
 *
 * ## What restoring means
 *
 * Importing writes to the radio (channels, contacts) and to this phone.
 * That is not undoable, so [decode] only ever *parses*; deciding what to
 * apply is the caller's job and the UI previews it first.
 *
 * ## Format
 *
 * Line-oriented text rather than JSON: `org.json` isn't available in
 * shared unit tests (the same reason ReactionCounts moved here), and a
 * backup format that can't be tested off-device is a backup format that
 * silently rots. Keys are `section.field`, values are escaped.
 */
object ConfigBackup {

    const val MAGIC = "meshcore-hardened-config"
    const val VERSION = 1

    /**
     * PBKDF2 iterations. OWASP's 2023 floor for PBKDF2-HMAC-SHA256 is
     * 600k; a backup is an offline-attackable artefact with no rate
     * limit, so we sit on that floor rather than under it. Roughly
     * 0.3–1 s on the phones this app targets — paid once per
     * export/import, not per message.
     */
    const val KDF_ITERATIONS = 600_000

    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32

    /**
     * Shortest passphrase we will encrypt with. Not a serious defence —
     * it is a floor that stops "1234" — and the UI still has to say
     * plainly that the passphrase is the only thing protecting the file.
     */
    const val MIN_PASSPHRASE_LENGTH = 8

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /** A contact, as much of it as is safe to write down. */
    data class BackupContact(
        val keyHex: String,
        val name: String,
        val type: Int,
        val flags: Int,
    )

    /** A channel slot. [pskHex] lives in the sealed section, never here. */
    data class BackupChannel(val index: Int, val name: String)

    /** One sealed secret, keyed by the slot it came from. */
    data class BackupSecret(val slot: String, val valueHex: String)

    /**
     * The plain, always-present half of a backup.
     *
     * [settings] is deliberately a flat string map rather than a typed
     * struct: preferences change often, and an import that silently
     * drops a key it doesn't recognise is better than one that refuses
     * the whole file.
     */
    data class Plain(
        val version: Int = VERSION,
        val createdAt: Long = 0,
        val appVersion: String = "",
        /** Radio this backup came from — a restore onto a different
         *  radio is legitimate, but the UI should say it is happening. */
        val selfKeyHex: String = "",
        val settings: Map<String, String> = emptyMap(),
        val contacts: List<BackupContact> = emptyList(),
        val channels: List<BackupChannel> = emptyList(),
        val regions: List<String> = emptyList(),
        /** Channel slot → region label. */
        val channelRegions: Map<Int, String> = emptyMap(),
    )

    /** Parameters of the sealed section, as read off the file. */
    data class SealedSection(
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        /** The exact header bytes that were authenticated as GCM AAD. */
        val aad: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is SealedSection && iterations == other.iterations &&
                salt.contentEquals(other.salt) && nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext) && aad.contentEquals(other.aad)

        override fun hashCode(): Int = iterations * 31 + salt.contentHashCode()
    }

    /** A parsed backup file. [sealed] is null when none was included. */
    data class Parsed(val plain: Plain, val sealed: SealedSection?) {
        val hasSecrets: Boolean get() = sealed != null
    }

    /**
     * Whether a backup's channel regions can be applied to the radio
     * that is attached now.
     *
     * Channel regions are keyed by SLOT NUMBER, and a slot number only
     * identifies a channel on the radio the backup came from. Restoring
     * them anywhere else scopes whatever happens to occupy slot 2 — the
     * same slot-as-identity mistake that let a deleted channel hand its
     * region to the next one written to the freed slot.
     *
     * Two cases are safe: the same radio, or a restore that is also
     * rewriting the channel slots from this backup in the same pass.
     * An unknown current key is NOT safe — "we don't know which radio
     * this is" is not the same as "it's the right one".
     */
    fun channelRegionsApplyTo(
        backupSelfKeyHex: String,
        currentSelfKeyHex: String,
        restoringChannels: Boolean,
    ): Boolean {
        if (restoringChannels) return true
        if (currentSelfKeyHex.isEmpty() || backupSelfKeyHex.isEmpty()) return false
        return currentSelfKeyHex.equals(backupSelfKeyHex, ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    /**
     * Render [plain] plus, when [passphrase] is non-null, an encrypted
     * section holding [secrets].
     *
     * Throws when asked to encrypt without the platform's authenticated
     * cipher, or with a passphrase under [MIN_PASSPHRASE_LENGTH] —
     * failing is the correct outcome, since the alternative is a file
     * the user believes is protected.
     */
    fun encode(
        crypto: CryptoProvider,
        plain: Plain,
        secrets: List<BackupSecret> = emptyList(),
        passphrase: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("$MAGIC v$VERSION")
        sb.appendLine("created ${plain.createdAt}")
        sb.appendLine("app ${escape(plain.appVersion)}")
        sb.appendLine("self ${escape(plain.selfKeyHex)}")
        // sortedBy, not toSortedMap: the latter is java.util and is
        // JVM-only, so it compiles for Android and cannot compile for
        // Native. This single call is why the iOS build was red when its
        // CI was removed. Determinism is the requirement — a backup must
        // encode identically twice — and sorting the entries gives that
        // on every target.
        for ((k, v) in plain.settings.entries.sortedBy { it.key }.map { it.key to it.value }) {
            sb.appendLine("set ${escape(k)} ${escape(v)}")
        }
        for (c in plain.contacts) {
            sb.appendLine("contact ${escape(c.keyHex)} ${c.type} ${c.flags} ${escape(c.name)}")
        }
        for (c in plain.channels) {
            sb.appendLine("channel ${c.index} ${escape(c.name)}")
        }
        for (r in plain.regions) sb.appendLine("region ${escape(r)}")
        for ((idx, region) in plain.channelRegions.entries.sortedBy { it.key }
            .map { it.key to it.value }) {
            sb.appendLine("chregion $idx ${escape(region)}")
        }

        if (passphrase == null) {
            if (secrets.isNotEmpty()) {
                // Refusing beats silently dropping them: the user asked
                // for a backup containing secrets and would otherwise
                // discover at restore time that it doesn't.
                throw IllegalArgumentException(
                    "secrets were supplied without a passphrase — refusing to write them in the clear",
                )
            }
            return sb.toString()
        }

        require(crypto.supportsAuthenticatedEncryption) {
            "this platform has no authenticated cipher — refusing to write a backup that claims to be encrypted"
        }
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }

        val salt = crypto.randomBytes(SALT_BYTES)
        val nonce = crypto.randomBytes(NONCE_BYTES)
        val header = sealedHeader(KDF_ITERATIONS, salt, nonce)
        val key = pbkdf2HmacSha256(
            crypto, passphrase.encodeToByteArray(), salt, KDF_ITERATIONS, KEY_BYTES,
        )
        val body = secrets.joinToString("\n") { "${escape(it.slot)} ${escape(it.valueHex)}" }
        // The header is authenticated but not encrypted: editing the
        // iteration count or salt in the file breaks the tag.
        val ct = crypto.aesGcmSeal(key, nonce, body.encodeToByteArray(), header.encodeToByteArray())
        sb.appendLine(header)
        sb.appendLine("sealed ${ct.toHex()}")
        return sb.toString()
    }

    private fun sealedHeader(iterations: Int, salt: ByteArray, nonce: ByteArray): String =
        "kdf pbkdf2-hmac-sha256 $iterations ${salt.toHex()} ${nonce.toHex()} v$VERSION"

    // ------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------

    /**
     * Parse a backup file. Returns null when [text] isn't one.
     *
     * Every field is attacker-controlled — a backup file is something
     * the user was handed as easily as something they wrote — so
     * malformed lines are dropped rather than throwing, counts are
     * capped, and nothing here touches the radio.
     */
    fun decode(text: String): Parsed? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val first = lines.firstOrNull() ?: return null
        if (!first.startsWith("$MAGIC v")) return null
        val version = first.removePrefix("$MAGIC v").toIntOrNull() ?: return null
        // A newer file may hold fields we'd drop on a round trip, which
        // would quietly delete the user's data on the next export.
        if (version > VERSION) return null

        var createdAt = 0L
        var appVersion = ""
        var selfKey = ""
        val settings = LinkedHashMap<String, String>()
        val contacts = ArrayList<BackupContact>()
        val channels = ArrayList<BackupChannel>()
        val regions = LinkedHashSet<String>()
        val channelRegions = LinkedHashMap<Int, String>()
        var sealedSection: SealedSection? = null
        var kdfHeader: String? = null
        var kdfIterations = 0
        var kdfSalt: ByteArray? = null
        var kdfNonce: ByteArray? = null

        for (line in lines.drop(1)) {
            val space = line.indexOf(' ')
            if (space <= 0) continue
            val tag = line.substring(0, space)
            val rest = line.substring(space + 1)
            when (tag) {
                "created" -> createdAt = rest.toLongOrNull() ?: 0L
                "app" -> appVersion = unescape(rest).take(MAX_FIELD)
                "self" -> selfKey = unescape(rest).take(MAX_FIELD)
                "set" -> {
                    if (settings.size >= MAX_ROWS) continue
                    val parts = rest.split(' ', limit = 2)
                    if (parts.size == 2) {
                        settings[unescape(parts[0]).take(MAX_FIELD)] =
                            unescape(parts[1]).take(MAX_FIELD)
                    }
                }
                "contact" -> {
                    if (contacts.size >= MAX_ROWS) continue
                    val parts = rest.split(' ', limit = 4)
                    if (parts.size == 4) {
                        val key = unescape(parts[0]).lowercase()
                        val type = parts[1].toIntOrNull()
                        val flags = parts[2].toIntOrNull()
                        if (isKeyHex(key) && type != null && flags != null) {
                            contacts += BackupContact(
                                key, unescape(parts[3]).take(MAX_NAME), type, flags,
                            )
                        }
                    }
                }
                "channel" -> {
                    if (channels.size >= MAX_ROWS) continue
                    val parts = rest.split(' ', limit = 2)
                    val idx = parts.getOrNull(0)?.toIntOrNull()
                    if (idx != null && idx in 0..MAX_CHANNEL_INDEX) {
                        channels += BackupChannel(
                            idx, unescape(parts.getOrElse(1) { "" }).take(MAX_NAME),
                        )
                    }
                }
                "region" -> Regions.canonical(unescape(rest))?.let {
                    if (regions.size < MAX_ROWS) regions += it
                }
                "chregion" -> {
                    val parts = rest.split(' ', limit = 2)
                    val idx = parts.getOrNull(0)?.toIntOrNull()
                    val region = Regions.canonical(unescape(parts.getOrElse(1) { "" }))
                    if (idx != null && idx in 0..MAX_CHANNEL_INDEX && region != null) {
                        channelRegions[idx] = region
                    }
                }
                "kdf" -> {
                    val parts = rest.split(' ')
                    if (parts.size >= 4 && parts[0] == "pbkdf2-hmac-sha256") {
                        val iters = parts[1].toIntOrNull()
                        val salt = hexOrNull(parts[2])
                        val nonce = hexOrNull(parts[3])
                        // An absurd iteration count is a denial-of-service
                        // on import, not a stronger file.
                        if (iters != null && iters in 1..MAX_ITERATIONS &&
                            salt != null && nonce != null && nonce.size == NONCE_BYTES
                        ) {
                            kdfHeader = line
                            kdfIterations = iters
                            kdfSalt = salt
                            kdfNonce = nonce
                        }
                    }
                }
                "sealed" -> {
                    val ct = hexOrNull(rest)
                    val salt = kdfSalt
                    val nonce = kdfNonce
                    val header = kdfHeader
                    if (ct != null && salt != null && nonce != null && header != null) {
                        sealedSection = SealedSection(
                            kdfIterations, salt, nonce, ct, header.encodeToByteArray(),
                        )
                    }
                }
                else -> Unit // Unknown tag from a future writer: ignore.
            }
        }

        return Parsed(
            Plain(
                version = version,
                createdAt = createdAt,
                appVersion = appVersion,
                selfKeyHex = selfKey,
                settings = settings,
                contacts = contacts,
                channels = channels,
                regions = regions.toList(),
                channelRegions = channelRegions,
            ),
            sealedSection,
        )
    }

    /**
     * Decrypt the sealed section. Returns null on the wrong passphrase,
     * a tampered file, or a platform without the cipher — all three are
     * "you don't get the secrets", and telling them apart would only
     * help someone guessing.
     */
    fun openSecrets(
        crypto: CryptoProvider,
        sealed: SealedSection,
        passphrase: String,
    ): List<BackupSecret>? {
        if (!crypto.supportsAuthenticatedEncryption) return null
        // An empty passphrase can never be right (encode enforces a
        // floor), and it would otherwise throw out of the KDF — the
        // user tapping "unlock" on an empty field must get "no", not a
        // crash.
        if (passphrase.isEmpty()) return null
        val key = pbkdf2HmacSha256(
            crypto, passphrase.encodeToByteArray(), sealed.salt, sealed.iterations, KEY_BYTES,
        )
        val plain = crypto.aesGcmOpen(key, sealed.nonce, sealed.ciphertext, sealed.aad)
            ?: return null
        return plain.decodeToString()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(' ', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val slot = unescape(parts[0])
                val value = unescape(parts[1])
                if (slot.isEmpty() || !isHex(value)) null else BackupSecret(slot, value.lowercase())
            }
            .take(MAX_ROWS)
            .toList()
    }

    // ------------------------------------------------------------------
    // Escaping and guards
    // ------------------------------------------------------------------

    private const val MAX_FIELD = 512
    private const val MAX_NAME = 64
    private const val MAX_ROWS = 2048
    private const val MAX_CHANNEL_INDEX = 255
    private const val MAX_ITERATIONS = 10_000_000

    /** Spaces and newlines are the field/record separators, so they escape. */
    internal fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace(" ", "\\s")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .ifEmpty { "\\e" }

    internal fun unescape(s: String): String {
        if (s == "\\e") return ""
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.lastIndex) {
                out.append(c); i++; continue
            }
            when (s[i + 1]) {
                's' -> out.append(' ')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                'e' -> Unit
                else -> out.append(s[i + 1])
            }
            i += 2
        }
        return out.toString()
    }

    private fun isHex(s: String): Boolean =
        isHexString(s)

    private fun isKeyHex(s: String): Boolean = s.length == 64 && isHex(s)

    private fun hexOrNull(s: String): ByteArray? {
        if (!isHex(s)) return null
        return ByteArray(s.length / 2) {
            s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
