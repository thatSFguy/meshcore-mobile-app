package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.ConfigBackup
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The settings a backup may carry, under Android's preference names, so
 * a file written by either app restores on the other. An ALLOW-list: a
 * new preference is never exported until it is added here on purpose.
 */
object BackupSettings {
    const val TCP = "transport_tcp_enabled"

    val KEYS: List<String> = listOf(
        "transport_ble_enabled", "transport_usb_enabled", TCP,
        "auto_reconnect", "theme", "units", "diagnostics_enabled",
        "map_tiles_enabled", "notifications_enabled",
        "nodes_tab",
    )

    val BOOLEAN_KEYS: Set<String> = setOf(
        "transport_ble_enabled", "transport_usb_enabled", TCP,
        "auto_reconnect", "diagnostics_enabled", "map_tiles_enabled",
        "notifications_enabled",
    )

    val INT_KEYS: Set<String> = setOf("nodes_tab")

    /**
     * The entries of a backup's settings that may be applied here, with
     * their values normalised ("true"/"false", base-10 ints). Unknown
     * keys and ill-typed values are dropped — a backup file is as much
     * "handed to me" as "written by me" — and TCP is never switched ON
     * unless its warning was already accepted on THIS device.
     */
    fun importable(settings: Map<String, String>, tcpWarningAccepted: Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in settings) {
            if (key !in KEYS) continue
            when (key) {
                in BOOLEAN_KEYS -> {
                    val b = value.toBooleanStrictOrNull() ?: continue
                    if (key == TCP && b && !tcpWarningAccepted) continue
                    out[key] = b.toString()
                }
                in INT_KEYS -> out[key] = (value.toIntOrNull() ?: continue).toString()
                else -> out[key] = value
            }
        }
        return out
    }

    /** How many of [settings] [importable] would refuse, for the report. */
    fun refusedCount(settings: Map<String, String>, tcpWarningAccepted: Boolean): Int =
        settings.size - importable(settings, tcpWarningAccepted).size
}

/**
 * Building a backup from what the radio holds, and restoring one onto
 * the radio — the parts of Android's ConfigBackupRepository that are not
 * about Android storage, for iOS (Phase 3, 2026-11-18).
 *
 * Secret slots use Android's names. iOS keeps no repeater passwords,
 * community secrets or identity seed, so its only secrets are the
 * channel keys the radio reports; slots it cannot use are reported as
 * skipped, never silently dropped.
 */
class BackupRestore(private val radio: Radio) {

    interface Radio {
        suspend fun setChannel(index: Int, name: String, psk: ByteArray): Boolean
        suspend fun addContact(keyHex: String, name: String, type: Int): Boolean
        suspend fun syncContacts()
    }

    data class Options(
        val settings: Boolean,
        val regions: Boolean,
        val channels: Boolean,
        val contacts: Boolean,
        val secrets: Boolean,
    )

    /**
     * What a restore did. [settings] and [regions] are what the platform
     * should store; the radio writes have already happened.
     */
    data class Report(
        val settings: Map<String, String>,
        val regions: List<String>,
        val message: String,
    )

    suspend fun restore(
        crypto: CryptoProvider,
        parsed: ConfigBackup.Parsed,
        options: Options,
        passphrase: String?,
        tcpWarningAccepted: Boolean,
        connected: Boolean,
    ): Report {
        val skipped = mutableListOf<String>()
        val plain = parsed.plain

        val settings = if (options.settings) BackupSettings.importable(plain.settings, tcpWarningAccepted) else emptyMap()
        if (options.settings) {
            val refused = BackupSettings.refusedCount(plain.settings, tcpWarningAccepted)
            if (refused > 0) skipped += "$refused setting(s) this version doesn't recognise"
        }
        val regions = if (options.regions) plain.regions else emptyList()

        val psks = HashMap<Int, ByteArray>()
        var secretsUsed = 0
        if (options.secrets) {
            val sealed = parsed.sealed
            when {
                sealed == null -> skipped += "no encrypted section in this file"
                passphrase.isNullOrEmpty() -> skipped += "secrets need the passphrase"
                else -> {
                    // 600,000 PBKDF2 rounds: off the caller's thread, which on
                    // iOS is the main one.
                    val opened = withContext(Dispatchers.Default) {
                        ConfigBackup.openSecrets(crypto, sealed, passphrase)
                    }
                    if (opened == null) {
                        skipped += "the passphrase did not open the encrypted section"
                    } else {
                        var notHere = 0
                        for (s in opened) {
                            val idx = s.slot.removePrefix(SLOT_CHANNEL_PSK).takeIf { s.slot.startsWith(SLOT_CHANNEL_PSK) }?.toIntOrNull()
                            val psk = hexToBytesOrNull(s.valueHex)
                            if (idx != null && idx in 0..255 && psk != null && psk.size == 16) {
                                psks[idx] = psk
                                secretsUsed++
                            } else {
                                notHere++
                            }
                        }
                        if (notHere > 0) skipped += "$notHere secret(s) this app doesn't store (passwords, community keys or an identity seed)"
                    }
                }
            }
        }

        var channelsDone = 0
        val channelsWanted = if (options.channels) plain.channels.size else 0
        if (options.channels && connected) {
            for (ch in plain.channels) {
                val psk = psks[ch.index] ?: continue
                if (runCatching { radio.setChannel(ch.index, ch.name, psk) }.getOrDefault(false)) channelsDone++
            }
            if (channelsDone < channelsWanted && psks.isEmpty()) {
                skipped += "channels need their keys — tick \"Channel keys\" and give the passphrase"
            }
        }

        var contactsDone = 0
        val contactsWanted = if (options.contacts) plain.contacts.size else 0
        if (options.contacts && connected) {
            for (c in plain.contacts) {
                if (runCatching { radio.addContact(c.keyHex, c.name, c.type) }.getOrDefault(false)) contactsDone++
            }
            if (contactsDone > 0) runCatching { radio.syncContacts() }
        }

        val message = buildString {
            append("Restored: ${settings.size} setting(s)")
            if (options.regions) append(", ${regions.size} region(s)")
            if (options.secrets) append(", $secretsUsed channel key(s)")
            if (options.channels) append(", $channelsDone of $channelsWanted channel(s)")
            if (options.contacts) append(", $contactsDone of $contactsWanted contact(s)")
            if ((options.channels || options.contacts) && !connected) append(" (connect a radio to restore channels and contacts)")
            if (skipped.isNotEmpty()) append(". Skipped: ").append(skipped.joinToString("; "))
            append(".")
        }
        return Report(settings, regions, message)
    }

    companion object {
        const val SLOT_CHANNEL_PSK = "channel_psk_"

        /** A backup file this large is not one of ours (Android's cap too). */
        const val MAX_FILE_BYTES: Int = 4 * 1024 * 1024

        fun suggestedFileName(nowSeconds: Long): String = "meshcore-hardened-backup-$nowSeconds.mcbackup"

        /** The plain part of a backup of what the radio holds. */
        fun plainFor(
            selfKeyHex: String,
            appVersion: String,
            nowSeconds: Long,
            settings: Map<String, String>,
            contacts: List<Contact>,
            channels: List<Channel>,
            regions: List<String>,
        ): ConfigBackup.Plain = ConfigBackup.Plain(
            createdAt = nowSeconds,
            appVersion = appVersion,
            selfKeyHex = selfKeyHex,
            settings = settings.filterKeys { it in BackupSettings.KEYS },
            contacts = contacts.map { ConfigBackup.BackupContact(it.publicKeyHex, it.name, it.type, it.flags) },
            channels = channels.filterNot { it.isEmpty }.map { ConfigBackup.BackupChannel(it.index, it.name) },
            regions = regions,
        )

        /** Each non-empty channel's key, for the encrypted section. */
        fun channelSecrets(channels: List<Channel>): List<ConfigBackup.BackupSecret> =
            channels.filterNot { it.isEmpty || it.psk.all { b -> b.toInt() == 0 } }
                .map { ConfigBackup.BackupSecret("$SLOT_CHANNEL_PSK${it.index}", it.pskHex) }

        /**
         * The whole file. Secrets go in only with a passphrase; without
         * one the file carries names and keys of contacts, never a PSK.
         * Slow with a passphrase (PBKDF2) — call it off the main thread.
         * Declared throwing so a refusal reaches Swift as an error, not
         * a crash.
         */
        @Throws(IllegalArgumentException::class, IllegalStateException::class, UnsupportedOperationException::class)
        fun export(
            crypto: CryptoProvider,
            plain: ConfigBackup.Plain,
            channels: List<Channel>,
            passphrase: String?,
        ): String = if (passphrase.isNullOrEmpty()) {
            ConfigBackup.encode(crypto, plain, emptyList(), null)
        } else {
            ConfigBackup.encode(crypto, plain, channelSecrets(channels), passphrase)
        }

        /** Whether a restore would write onto a different radio than the backup came from. */
        fun differentRadio(backupSelfKeyHex: String, currentSelfKeyHex: String?): Boolean =
            backupSelfKeyHex.isNotEmpty() && !currentSelfKeyHex.isNullOrEmpty() &&
                !backupSelfKeyHex.equals(currentSelfKeyHex, ignoreCase = true)

        /** One line describing what a file holds, for the restore sheet. */
        fun summary(parsed: ConfigBackup.Parsed): String {
            val p = parsed.plain
            return "Contains ${p.settings.size} setting(s), ${p.contacts.size} contact(s), " +
                "${p.channels.size} channel(s), ${p.regions.size} region(s)" +
                if (parsed.hasSecrets) ", plus an encrypted section." else "."
        }

        fun over(engine: MeshCoreEngine): BackupRestore = BackupRestore(object : Radio {
            override suspend fun setChannel(index: Int, name: String, psk: ByteArray) = engine.setChannel(index, name, psk)
            override suspend fun addContact(keyHex: String, name: String, type: Int): Boolean {
                val key = hexToBytesOrNull(keyHex) ?: return false
                return engine.addContactFromCard(key, name, type)
            }
            override suspend fun syncContacts() = engine.syncContacts()
        })
    }
}
