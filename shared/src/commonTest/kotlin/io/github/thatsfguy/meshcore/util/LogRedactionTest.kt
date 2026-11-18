package io.github.thatsfguy.meshcore.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shared redaction rule, pinned where iOS can reach it. Android's
 * DiagnosticsRedactionTest pins the same rule through its delegate.
 */
class LogRedactionTest {

    @Test
    fun aRealPrivateKeyCommandIsRedactedAtItsRealLength() {
        // `set prv.key` carries 64 bytes = 128 hex characters.
        val key = "0123456789abcdef".repeat(8)
        val line = LogRedaction.redact("CLI tx: set prv.key $key")
        assertFalse("0123456789abcdef" in line)
        assertEquals("CLI tx: set prv.key [REDACTED]", line)
    }

    @Test
    fun aPasswordIsRedactedWhateverItsCase() {
        assertFalse("hunter2" in LogRedaction.redact("PASSWORD hunter2"))
        assertFalse("hunter2" in LogRedaction.redact("set guest.password hunter2"))
    }

    @Test
    fun aChannelKeyIsRedacted() {
        // A captured 16-byte PSK in hex.
        val psk = "8b3387e9c5cdea6ac9e5edbaa115cd72"
        val line = LogRedaction.redact("channel psk $psk written")
        assertFalse(psk in line)
        assertTrue("[HEX-REDACTED]" in line)
    }

    @Test
    fun aKeyPrefixAndAFrameDumpSurvive() {
        // What the log is FOR: 12-hex key prefixes, and frame bytes the
        // engine space-separates precisely so this rule leaves them.
        val prefix = "contact a1b2c3d4e5f6 updated"
        assertEquals(prefix, LogRedaction.redact(prefix))
        val frame = "TX code=0x2 len=9 02 00 00 01 a1 b2 c3 d4 e5"
        assertEquals(frame, LogRedaction.redact(frame))
    }

    @Test
    fun aMacKeepsOnlyEnoughToTellRadiosApart() {
        val a = LogRedaction.redact("BLE: Connecting to C4:DE:E2:9A:1B:7F")
        val b = LogRedaction.redact("BLE: Connecting to C4:DE:E2:9A:20:01")
        assertFalse("C4:DE:E2:9A" in a)
        assertTrue(a.endsWith("1B:7F"))
        assertNotEquals(a, b)
    }

    @Test
    fun secretsOnlyLeavesAMacReadable() {
        // A stored `start ota` reply must keep the MAC the app reads back.
        val reply = "OK - mac: C4:DE:E2:9A:87:E1"
        assertEquals(reply, LogRedaction.redactSecrets(reply))
    }
}
