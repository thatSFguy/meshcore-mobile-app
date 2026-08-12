package io.github.thatsfguy.meshcore.android.storage

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.protocol.ConfigBackup
import io.github.thatsfguy.meshcore.util.toHex
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * Gathers a [ConfigBackup] out of this phone's state, and applies one
 * back (PARITY.md §1).
 *
 * The split between "plain" and "sealed" is enforced by the codec, not
 * here — this class's job is to hand it the right things. What it must
 * never do is put a secret in the plain half, so the two collections are
 * built by separate functions with no shared path.
 *
 * Applying is deliberately partial and explicit. Restoring contacts and
 * channels writes to the *radio*, which is not undoable, so
 * [ApplyOptions] exists to let the UI ask first and the user say no to
 * any part of it.
 */
class ConfigBackupRepository(
    private val prefs: Preferences,
    private val secrets: SecretsRepository,
    private val db: MeshCoreDatabase,
    private val crypto: CryptoProvider,
) {

    /** Which parts of a parsed backup to actually apply. */
    data class ApplyOptions(
        val settings: Boolean = true,
        val regions: Boolean = true,
        /** Writes channel slots on the radio — not undoable. */
        val channels: Boolean = false,
        /** Writes contacts to the radio — not undoable. */
        val contacts: Boolean = false,
        /** Restores passwords/PSKs/seed into the Keystore. */
        val secrets: Boolean = false,
    )

    data class ApplyResult(
        val settingsRestored: Int = 0,
        val regionsRestored: Int = 0,
        val channelRegionsRestored: Int = 0,
        val secretsRestored: Int = 0,
        val channelsQueued: Int = 0,
        val contactsQueued: Int = 0,
        /** Things we chose not to do, with the reason. Shown verbatim. */
        val skipped: List<String> = emptyList(),
    )

    // ------------------------------------------------------------------
    // Gather
    // ------------------------------------------------------------------

    /**
     * Build the plain half. Everything here is either already public
     * (contact pubkeys, names heard over the air) or a local preference.
     * No secret reaches this function.
     */
    suspend fun gatherPlain(selfKeyHex: String, appVersion: String, nowSeconds: Long): ConfigBackup.Plain {
        val contacts = if (selfKeyHex.isEmpty()) emptyList() else db.contacts().allOnce(selfKeyHex)
        val channels = if (selfKeyHex.isEmpty()) emptyList() else db.channels().allOnce(selfKeyHex)
        return ConfigBackup.Plain(
            createdAt = nowSeconds,
            appVersion = appVersion,
            selfKeyHex = selfKeyHex,
            settings = prefs.exportableSettings(),
            contacts = contacts.map {
                ConfigBackup.BackupContact(it.keyHex, it.name, it.type, it.flags)
            },
            // Names only. The PSK is the channel; it belongs in the
            // sealed half or nowhere.
            channels = channels.map { ConfigBackup.BackupChannel(it.idx, it.name) },
            regions = prefs.regions,
            channelRegions = prefs.channelRegions(),
        )
    }

    /**
     * Build the sealed half: channel PSKs, repeater/room login
     * passwords, community secrets, and the app-side identity seed.
     *
     * A secret the Keystore refuses to unseal is omitted rather than
     * guessed at — a backup missing one entry is recoverable, a backup
     * with a wrong one is a silent lockout later.
     */
    suspend fun gatherSecrets(selfKeyHex: String): List<ConfigBackup.BackupSecret> {
        val out = ArrayList<ConfigBackup.BackupSecret>()

        if (selfKeyHex.isNotEmpty()) {
            for (channel in db.channels().allOnce(selfKeyHex)) {
                val psk = secrets.unsealPsk(channel.pskSealed) ?: continue
                out += ConfigBackup.BackupSecret("$SLOT_CHANNEL_PSK${channel.idx}", psk.toHex())
            }
        }
        for (slot in prefs.sealedKeys("login_") + prefs.sealedKeys("guest_")) {
            val guest = slot.startsWith("guest_")
            val keyHex = slot.removePrefix(if (guest) "guest_" else "login_")
            val password = secrets.loginPassword(keyHex, guest) ?: continue
            out += ConfigBackup.BackupSecret(slot, password.encodeToByteArray().toHex())
        }
        for (id in secrets.communityIds()) {
            val secret = secrets.communitySecret(id) ?: continue
            out += ConfigBackup.BackupSecret("community_$id", secret.toHex())
        }
        secrets.identitySeed()?.let { out += ConfigBackup.BackupSecret(SLOT_IDENTITY, it.toHex()) }
        return out
    }

    /**
     * Render a backup file. [passphrase] null means the plain half only
     * — and then secrets are not gathered at all, so they never exist in
     * memory to be leaked by a later mistake.
     */
    suspend fun export(
        selfKeyHex: String,
        appVersion: String,
        nowSeconds: Long,
        passphrase: String? = null,
    ): String {
        val plain = gatherPlain(selfKeyHex, appVersion, nowSeconds)
        val secretList = if (passphrase == null) emptyList() else gatherSecrets(selfKeyHex)
        return ConfigBackup.encode(crypto, plain, secretList, passphrase)
    }

    // ------------------------------------------------------------------
    // Apply
    // ------------------------------------------------------------------

    /**
     * Apply the parts of [parsed] that [options] permits.
     *
     * Radio-side restores (channels, contacts) are returned as counts of
     * what the caller should write, not written here — this class has no
     * business holding a radio handle, and the engine call has to be
     * serialised with everything else the app is doing.
     */
    suspend fun apply(
        parsed: ConfigBackup.Parsed,
        options: ApplyOptions,
        passphrase: String? = null,
        currentSelfKeyHex: String = "",
    ): ApplyResult {
        val skipped = ArrayList<String>()
        var settingsRestored = 0
        var regionsRestored = 0
        var channelRegionsRestored = 0
        var secretsRestored = 0

        if (options.settings) {
            settingsRestored = prefs.importSettings(parsed.plain.settings)
            val unknown = parsed.plain.settings.size - settingsRestored
            if (unknown > 0) {
                skipped += "$unknown setting(s) this version doesn't recognise"
            }
        }

        if (options.regions) {
            for (region in parsed.plain.regions) {
                if (prefs.addRegion(region) != null) regionsRestored++
            }
            // A channel region is keyed by SLOT NUMBER, which only means
            // anything if the slots are the ones the backup was taken
            // from. Two cases satisfy that: the same radio, or a restore
            // that is also rewriting the channel slots in this pass.
            //
            // Otherwise slot 2 on this radio is some other channel, and
            // restoring the region silently scopes it — the same
            // slot-as-identity mistake that let a deleted channel hand
            // its region to the next one written to the freed slot.
            val slotsAreTheBackups = ConfigBackup.channelRegionsApplyTo(
                backupSelfKeyHex = parsed.plain.selfKeyHex,
                currentSelfKeyHex = currentSelfKeyHex,
                restoringChannels = options.channels,
            )
            if (slotsAreTheBackups) {
                for ((idx, region) in parsed.plain.channelRegions) {
                    prefs.setChannelRegion(idx, region)
                    channelRegionsRestored++
                }
            } else if (parsed.plain.channelRegions.isNotEmpty()) {
                skipped += "${parsed.plain.channelRegions.size} channel region(s) — this " +
                    "backup is from a different radio, so its slot numbers don't match " +
                    "yours. Restore channels too, or set them by hand."
            }
        }

        if (options.secrets) {
            val sealed = parsed.sealed
            when {
                sealed == null -> skipped += "no encrypted section in this file"
                passphrase == null -> skipped += "secrets need the passphrase"
                else -> {
                    val opened = ConfigBackup.openSecrets(crypto, sealed, passphrase)
                    if (opened == null) {
                        skipped += "the passphrase did not open the encrypted section"
                    } else {
                        secretsRestored = restoreSecrets(opened, skipped)
                    }
                }
            }
        }

        return ApplyResult(
            settingsRestored = settingsRestored,
            regionsRestored = regionsRestored,
            channelRegionsRestored = channelRegionsRestored,
            secretsRestored = secretsRestored,
            channelsQueued = if (options.channels) parsed.plain.channels.size else 0,
            contactsQueued = if (options.contacts) parsed.plain.contacts.size else 0,
            skipped = skipped,
        )
    }

    private suspend fun restoreSecrets(
        opened: List<ConfigBackup.BackupSecret>,
        skipped: MutableList<String>,
    ): Int {
        var restored = 0
        for (secret in opened) {
            val bytes = hexToBytesOrNull(secret.valueHex) ?: continue
            val ok = when {
                secret.slot == SLOT_IDENTITY ->
                    secrets.storeIdentitySeed(bytes)

                secret.slot.startsWith(SLOT_CHANNEL_PSK) -> {
                    // Channel PSKs are re-sealed when the channel itself
                    // is written to the radio; holding them here would
                    // mean a second copy outside the Keystore.
                    pendingChannelPsks[secret.slot.removePrefix(SLOT_CHANNEL_PSK).toIntOrNull()
                        ?: continue] = bytes
                    true
                }

                secret.slot.startsWith("login_") || secret.slot.startsWith("guest_") -> {
                    val guest = secret.slot.startsWith("guest_")
                    val keyHex = secret.slot.removePrefix(if (guest) "guest_" else "login_")
                    secrets.storeLoginPassword(keyHex, bytes.decodeToString(), guest)
                }

                secret.slot.startsWith("community_") ->
                    secrets.storeCommunitySecret(secret.slot.removePrefix("community_"), bytes)

                else -> {
                    skipped += "unknown secret slot '${secret.slot}'"
                    false
                }
            }
            if (ok) restored++
        }
        if (restored < opened.size && skipped.none { it.startsWith("unknown secret") }) {
            // The Keystore refusing to seal is the usual cause; the app
            // does not fall back to plaintext, so say what happened.
            skipped += "${opened.size - restored} secret(s) could not be stored in the Keystore"
        }
        return restored
    }

    /**
     * PSKs pulled out of a restored backup, waiting for the caller to
     * write the matching channel slot to the radio. Cleared once used.
     */
    val pendingChannelPsks = LinkedHashMap<Int, ByteArray>()

    companion object {
        const val SLOT_CHANNEL_PSK = "channel_psk_"
        const val SLOT_IDENTITY = "identity_seed"

        /** Suggested filename; the user can rename it in the picker. */
        fun suggestedFileName(nowSeconds: Long): String =
            "meshcore-hardened-backup-$nowSeconds.mcbackup"
    }
}
