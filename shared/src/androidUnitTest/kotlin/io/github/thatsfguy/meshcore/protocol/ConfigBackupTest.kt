package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Config backup encode/decode (PARITY §1).
 *
 * The load-bearing assertions are the negative ones: a backup file is
 * something a user can be *handed*, so decode runs on hostile input, and
 * the one rule that must never bend is that secrets are not written
 * without a passphrase.
 */
class ConfigBackupTest {

    private val crypto = AndroidCryptoProvider()

    private val plain = ConfigBackup.Plain(
        createdAt = 1_700_000_000,
        appVersion = "0.3.0",
        selfKeyHex = "aa".repeat(32),
        settings = mapOf("theme" to "dark", "diagnostics_enabled" to "false"),
        contacts = listOf(
            ConfigBackup.BackupContact("bb".repeat(32), "Blue Ridge", 2, 1),
            ConfigBackup.BackupContact("cc".repeat(32), "Alice", 1, 0),
        ),
        channels = listOf(
            ConfigBackup.BackupChannel(0, "Public"),
            ConfigBackup.BackupChannel(1, "#rescue"),
        ),
        regions = listOf("bayarea", "socal"),
        channelRegions = mapOf(1 to "bayarea"),
    )

    private val secrets = listOf(
        ConfigBackup.BackupSecret("channel_psk_0", "8b3387e9c5cdea6ac9e5edbaa115cd72"),
        ConfigBackup.BackupSecret("login_" + "bb".repeat(32), "68756e74657232"),
    )

    // ------------------------------------------------------------------
    // The rule that cannot bend
    // ------------------------------------------------------------------

    @Test
    fun secretsAreNeverWrittenWithoutAPassphrase() {
        // Not "dropped silently" — refused, so the user can't come back
        // months later to a backup that turned out not to have them.
        val e = assertFailsWith<IllegalArgumentException> {
            ConfigBackup.encode(crypto, plain, secrets, passphrase = null)
        }
        assertTrue("clear" in e.message!!)
    }

    @Test
    fun noSecretMaterialAppearsAnywhereInAnUnencryptedBackup() {
        val text = ConfigBackup.encode(crypto, plain, emptyList(), passphrase = null)
        for (s in secrets) {
            assertTrue(s.valueHex !in text, "secret ${s.slot} leaked into a plain backup")
        }
        assertTrue("kdf" !in text)
        assertTrue("sealed" !in text)
    }

    @Test
    fun noSecretMaterialAppearsInTheClearInAnEncryptedBackup() {
        val text = ConfigBackup.encode(crypto, plain, secrets, "correct horse battery")
        for (s in secrets) {
            assertTrue(s.valueHex !in text, "secret ${s.slot} leaked past the cipher")
            assertTrue(s.slot !in text, "slot name ${s.slot} leaked past the cipher")
        }
    }

    @Test
    fun encryptingRefusesAWeakOrAbsentPassphrase() {
        assertFailsWith<IllegalArgumentException> {
            ConfigBackup.encode(crypto, plain, secrets, "1234")
        }
        assertFailsWith<IllegalArgumentException> {
            ConfigBackup.encode(crypto, plain, secrets, "")
        }
        // Exactly at the floor is fine.
        ConfigBackup.encode(crypto, plain, secrets, "a".repeat(ConfigBackup.MIN_PASSPHRASE_LENGTH))
    }

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    fun plainSectionRoundTrips() {
        val parsed = ConfigBackup.decode(ConfigBackup.encode(crypto, plain))
        assertNotNull(parsed)
        assertEquals(plain.createdAt, parsed.plain.createdAt)
        assertEquals(plain.appVersion, parsed.plain.appVersion)
        assertEquals(plain.selfKeyHex, parsed.plain.selfKeyHex)
        assertEquals(plain.settings, parsed.plain.settings)
        assertEquals(plain.contacts, parsed.plain.contacts)
        assertEquals(plain.channels, parsed.plain.channels)
        assertEquals(plain.regions, parsed.plain.regions)
        assertEquals(plain.channelRegions, parsed.plain.channelRegions)
        assertTrue(!parsed.hasSecrets)
    }

    @Test
    fun sealedSectionRoundTripsWithTheRightPassphrase() {
        val text = ConfigBackup.encode(crypto, plain, secrets, "correct horse battery")
        val parsed = ConfigBackup.decode(text)
        assertNotNull(parsed)
        assertTrue(parsed.hasSecrets)
        assertEquals(
            secrets,
            ConfigBackup.openSecrets(crypto, parsed.sealed!!, "correct horse battery"),
        )
    }

    @Test
    fun theWrongPassphraseYieldsNothingRatherThanGarbage() {
        val parsed = ConfigBackup.decode(
            ConfigBackup.encode(crypto, plain, secrets, "correct horse battery"),
        )!!
        assertNull(ConfigBackup.openSecrets(crypto, parsed.sealed!!, "correct horse batteru"))
        assertNull(ConfigBackup.openSecrets(crypto, parsed.sealed, ""))
    }

    @Test
    fun namesWithSpacesAndNewlinesSurviveTheRoundTrip() {
        // The format is space-delimited, so these are the cases that
        // corrupt a naive writer — or let a name inject a whole record.
        val tricky = ConfigBackup.Plain(
            contacts = listOf(
                ConfigBackup.BackupContact("dd".repeat(32), "Blue Ridge Repeater", 2, 0),
                ConfigBackup.BackupContact("ee".repeat(32), "line\nbreak", 1, 0),
                ConfigBackup.BackupContact("ff".repeat(32), "back\\slash", 1, 0),
                ConfigBackup.BackupContact("ab".repeat(32), "", 1, 0),
            ),
            channels = listOf(ConfigBackup.BackupChannel(0, "two words")),
            settings = mapOf("key with space" to "value with space"),
        )
        val parsed = ConfigBackup.decode(ConfigBackup.encode(crypto, tricky))!!
        assertEquals(tricky.contacts, parsed.plain.contacts)
        assertEquals(tricky.channels, parsed.plain.channels)
        assertEquals(tricky.settings, parsed.plain.settings)
    }

    @Test
    fun aNameCannotForgeAnExtraRecord() {
        // A contact name is attacker-influenced (it came off the mesh).
        // If the writer didn't escape the newline, exporting and
        // re-importing would conjure a contact nobody added.
        val forgedKey = "99".repeat(32)
        val injected = ConfigBackup.Plain(
            contacts = listOf(
                ConfigBackup.BackupContact(
                    "dd".repeat(32),
                    "evil\ncontact $forgedKey 2 0 Injected",
                    1,
                    0,
                ),
            ),
        )
        val parsed = ConfigBackup.decode(ConfigBackup.encode(crypto, injected))!!
        assertEquals(1, parsed.plain.contacts.size)
        assertEquals("dd".repeat(32), parsed.plain.contacts[0].keyHex)
        assertTrue(
            parsed.plain.contacts.none { it.keyHex == forgedKey },
            "a contact name forged a second contact record",
        )
        // The name is capped on the way back in, so what survives is a
        // prefix of the original — the point is that it stayed one
        // field instead of becoming a record.
        assertTrue(parsed.plain.contacts[0].name.startsWith("evil\ncontact"))
    }

    @Test
    fun aShortNameWithANewlineSurvivesExactly() {
        val plainWithNewline = ConfigBackup.Plain(
            contacts = listOf(ConfigBackup.BackupContact("dd".repeat(32), "two\nlines", 1, 0)),
        )
        val parsed = ConfigBackup.decode(ConfigBackup.encode(crypto, plainWithNewline))!!
        assertEquals("two\nlines", parsed.plain.contacts[0].name)
    }

    // ------------------------------------------------------------------
    // Hostile input
    // ------------------------------------------------------------------

    @Test
    fun rejectsAnythingThatIsNotABackup() {
        for (bad in listOf(
            "", "   ", "hello", "{\"json\": true}",
            "meshcore-hardened-config", // no version
            "meshcore-hardened-config vX",
            "not-our-magic v1\ncreated 1",
        )) {
            assertNull(ConfigBackup.decode(bad), "accepted: $bad")
        }
    }

    @Test
    fun rejectsAFutureFormatVersion() {
        // Round-tripping a newer file through an older writer would drop
        // the fields it didn't understand — silent data loss.
        val text = ConfigBackup.encode(crypto, plain)
            .replaceFirst("v${ConfigBackup.VERSION}", "v${ConfigBackup.VERSION + 1}")
        assertNull(ConfigBackup.decode(text))
    }

    @Test
    fun dropsMalformedRowsAndKeepsTheGoodOnes() {
        val text = buildString {
            appendLine("${ConfigBackup.MAGIC} v1")
            appendLine("created notanumber")
            appendLine("contact short 1 0 Bad")               // key too short
            appendLine("contact ${"zz".repeat(32)} 1 0 Bad")   // non-hex key
            appendLine("contact ${"bb".repeat(32)} notanint 0 Bad")
            appendLine("contact ${"cc".repeat(32)} 1 0 Good")
            appendLine("channel notanint Name")
            appendLine("channel 999999 TooBig")
            appendLine("channel 2 Fine")
            appendLine("region bay area")                       // not a region name
            appendLine("region bayarea")
            appendLine("chregion 2 bayarea")
            appendLine("chregion notanint bayarea")
            appendLine("garbage line with no meaning")
            appendLine("set")                                   // no value
        }
        val parsed = ConfigBackup.decode(text)!!
        assertEquals(0L, parsed.plain.createdAt)
        assertEquals(1, parsed.plain.contacts.size)
        assertEquals("Good", parsed.plain.contacts[0].name)
        assertEquals(listOf(ConfigBackup.BackupChannel(2, "Fine")), parsed.plain.channels)
        assertEquals(listOf("bayarea"), parsed.plain.regions)
        assertEquals(mapOf(2 to "bayarea"), parsed.plain.channelRegions)
        assertTrue(!parsed.hasSecrets)
    }

    @Test
    fun anEditedKdfHeaderBreaksTheSeal() {
        // The downgrade attack: rewrite 600000 iterations down to 1 and
        // brute-force the passphrase cheaply. The header is GCM AAD, so
        // the tag stops verifying.
        val text = ConfigBackup.encode(crypto, plain, secrets, "correct horse battery")
        val weakened = text.replace(" ${ConfigBackup.KDF_ITERATIONS} ", " 1 ")
        assertTrue(weakened != text, "test did not actually edit the header")
        val parsed = ConfigBackup.decode(weakened)!!
        assertNull(ConfigBackup.openSecrets(crypto, parsed.sealed!!, "correct horse battery"))
    }

    @Test
    fun anAbsurdIterationCountIsRefusedAtParseTime() {
        // Importing a file that claims a billion iterations would hang
        // the phone; that is a denial of service, not a stronger file.
        val text = ConfigBackup.encode(crypto, plain, secrets, "correct horse battery")
            .replace(" ${ConfigBackup.KDF_ITERATIONS} ", " 999999999 ")
        val parsed = ConfigBackup.decode(text)!!
        assertNull(parsed.sealed, "an absurd iteration count was accepted")
    }

    @Test
    fun aSealedSectionWithoutItsHeaderIsIgnored() {
        val text = buildString {
            appendLine("${ConfigBackup.MAGIC} v1")
            appendLine("sealed ${"ab".repeat(40)}")
        }
        assertNull(ConfigBackup.decode(text)!!.sealed)
    }

    @Test
    fun truncationAtEveryLengthIsSurvivable() {
        val text = ConfigBackup.encode(crypto, plain, secrets, "correct horse battery")
        for (n in 0..text.length) {
            // Never throws; either parses what it can or returns null.
            ConfigBackup.decode(text.substring(0, n))
        }
    }

    @Test
    fun escapeRoundTripsEveryTrickyString() {
        for (s in listOf(
            "", " ", "  ", "a b", "a\nb", "a\r\nb", "\\", "\\s", "\\n", "\\e",
            "\\\\s", "trailing ", " leading", "unicode ✓ ok",
        )) {
            assertEquals(s, ConfigBackup.unescape(ConfigBackup.escape(s)), "failed for: [$s]")
        }
    }
}
