package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.ConfigBackup
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupRestoreTest {

    private val crypto = AndroidCryptoProvider()
    private val me = "11".repeat(32)
    // A captured channel key (the hashtag PSK shape from ChannelCrypto tests).
    private val psk = hexToBytesOrNull("8b3387e9c5cdea6ac9e5edbaa115cd72")!!

    private val channels = listOf(
        Channel(0, "Public", ByteArray(16) { 1 }),
        Channel(1, "#test", psk),
        Channel(2, "", ByteArray(16)),
    )
    private val contacts = listOf(
        Contact(
            publicKey = ByteArray(32) { 0x22 }, type = Codes.ADV_TYPE_REPEATER, flags = 1, pathLen = 0xFF,
            path = ByteArray(64), name = "13 Mile", timestamp = 0, latitude = null, longitude = null, lastModified = 0,
        ),
    )

    private class FakeRadio(var accept: Boolean = true) : BackupRestore.Radio {
        val channels = mutableListOf<Triple<Int, String, String>>()
        val contacts = mutableListOf<String>()
        var synced = 0
        override suspend fun setChannel(index: Int, name: String, psk: ByteArray): Boolean {
            channels += Triple(index, name, psk.joinToString("") { "%02x".format(it) }); return accept
        }
        override suspend fun addContact(keyHex: String, name: String, type: Int): Boolean { contacts += name; return accept }
        override suspend fun syncContacts() { synced++ }
    }

    private fun file(passphrase: String?, settings: Map<String, String> = mapOf("auto_reconnect" to "false")): ConfigBackup.Parsed {
        val plain = BackupRestore.plainFor(me, "0.1.0", 1_700_000_000, settings, contacts, channels, listOf("bayarea"))
        return ConfigBackup.decode(BackupRestore.export(crypto, plain, channels, passphrase))!!
    }

    private val all = BackupRestore.Options(settings = true, regions = true, channels = true, contacts = true, secrets = true)

    @Test
    fun withoutAPassphraseNoKeyIsInTheFile() {
        val plain = BackupRestore.plainFor(me, "0.1.0", 1, emptyMap(), contacts, channels, emptyList())
        val text = BackupRestore.export(crypto, plain, channels, null)
        assertFalse("8b3387e9" in text, "a channel key must never be written in the clear")
        assertFalse(ConfigBackup.decode(text)!!.hasSecrets)
        // Empty slots are not backed up as channels.
        assertEquals(listOf(0, 1), ConfigBackup.decode(text)!!.plain.channels.map { it.index })
    }

    @Test
    fun aFullRestoreWritesChannelsWithTheirKeysAndContacts() = runTest {
        val radio = FakeRadio()
        val report = BackupRestore(radio).restore(crypto, file("correct horse battery"), all, "correct horse battery", false, true)
        assertEquals(listOf(Triple(1, "#test", "8b3387e9c5cdea6ac9e5edbaa115cd72")), radio.channels.filter { it.first == 1 })
        assertEquals(2, radio.channels.size)
        assertEquals(listOf("13 Mile"), radio.contacts)
        assertEquals(1, radio.synced)
        assertEquals(mapOf("auto_reconnect" to "false"), report.settings)
        assertEquals(listOf("bayarea"), report.regions)
        assertEquals(
            "Restored: 1 setting(s), 1 region(s), 2 channel key(s), 2 of 2 channel(s), 1 of 1 contact(s).",
            report.message,
        )
    }

    @Test
    fun aWrongPassphraseWritesNoChannelAndSaysWhy() = runTest {
        val radio = FakeRadio()
        val report = BackupRestore(radio).restore(crypto, file("correct horse battery"), all, "wrong horse battery", false, true)
        assertTrue(radio.channels.isEmpty())
        assertTrue("the passphrase did not open the encrypted section" in report.message, report.message)
        assertTrue("0 of 2 channel(s)" in report.message)
    }

    @Test
    fun aBackupCannotTurnOnTcpBehindTheWarning() = runTest {
        val f = file(null, mapOf(BackupSettings.TCP to "true", "theme" to "dark", "secret_token" to "x", "nodes_tab" to "two"))
        val opts = all.copy(channels = false, contacts = false, secrets = false)
        val report = BackupRestore(FakeRadio()).restore(crypto, f, opts, null, tcpWarningAccepted = false, connected = true)
        assertEquals(mapOf("theme" to "dark"), report.settings)
        // secret_token is dropped at export (allow-list), so only two refusals remain.
        assertTrue("2 setting(s) this version doesn't recognise" in report.message, report.message)
        val accepted = BackupRestore(FakeRadio()).restore(crypto, f, opts, null, tcpWarningAccepted = true, connected = true)
        assertEquals("true", accepted.settings[BackupSettings.TCP])
    }

    @Test
    fun withNoRadioNothingIsWrittenAndItSaysSo() = runTest {
        val radio = FakeRadio()
        val report = BackupRestore(radio).restore(crypto, file("correct horse battery"), all, "correct horse battery", false, connected = false)
        assertTrue(radio.channels.isEmpty() && radio.contacts.isEmpty())
        assertTrue("(connect a radio to restore channels and contacts)" in report.message)
    }

    @Test
    fun slotsThisAppCannotUseAreCountedNotDropped() = runTest {
        val plain = BackupRestore.plainFor(me, "0.1.0", 1, emptyMap(), emptyList(), channels, emptyList())
        val text = ConfigBackup.encode(
            crypto, plain,
            listOf(ConfigBackup.BackupSecret("login_${"22".repeat(32)}", "6869"), ConfigBackup.BackupSecret("channel_psk_1", "8b3387e9c5cdea6ac9e5edbaa115cd72")),
            "correct horse battery",
        )
        val report = BackupRestore(FakeRadio()).restore(
            crypto, ConfigBackup.decode(text)!!, all.copy(contacts = false), "correct horse battery", false, true,
        )
        assertTrue("1 secret(s) this app doesn't store" in report.message, report.message)
        assertTrue("1 channel key(s)" in report.message)
    }

    @Test
    fun aDifferentRadioIsNamedOnlyWhenBothKeysAreKnown() {
        assertTrue(BackupRestore.differentRadio(me, "22".repeat(32)))
        assertFalse(BackupRestore.differentRadio(me, me.uppercase()))
        assertFalse(BackupRestore.differentRadio("", "22".repeat(32)))
        assertFalse(BackupRestore.differentRadio(me, null))
    }
}
