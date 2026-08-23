package io.github.thatsfguy.meshcore.android.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diagnostics log must never leak secrets (SCOPE.md: redact
 * `set prv.key` and the login-password path).
 */
class DiagnosticsRedactionTest {

    @Test
    fun redactsPrvKeyCli() {
        val line = DiagnosticsLog.redact("CLI tx: set prv.key a1b2c3d4e5f6")
        assertFalse("a1b2c3d4e5f6" in line)
        assertTrue("[REDACTED]" in line)
    }

    /**
     * The real command, at the real length. `set prv.key` carries 128
     * hex characters (MESHCORE_PROTOCOL §12) — the short sample above
     * would still be caught by a rule that only matched 64, and the one
     * line in this app that can put a node's whole identity in a
     * shareable log deserves to be tested with what it actually sends.
     */
    @Test
    fun redactsARealSixtyFourBytePrivateKey() {
        val key = "0123456789abcdef".repeat(8)
        assertEquals(128, key.length)
        val line = DiagnosticsLog.redact("CLI tx: set prv.key $key")
        assertFalse(key in line)
        assertFalse("0123456789abcdef" in line)
        assertTrue("[REDACTED]" in line)
    }

    @Test
    fun redactsPasswordText() {
        val line = DiagnosticsLog.redact("login with password hunter2 sent")
        assertFalse("hunter2" in line)
    }

    @Test
    fun redactsLongHexBlobs() {
        val psk = "8b3387e9c5cdea6ac9e5edbaa115cd72"
        val line = DiagnosticsLog.redact("channel psk $psk written")
        assertFalse(psk in line)
        assertTrue("[HEX-REDACTED]" in line)
    }

    @Test
    fun leavesNormalLinesAlone() {
        val line = "BLE connected to MeshCore-abc (rssi -60)"
        assertEquals(line, DiagnosticsLog.redact(line))
    }

    @Test
    fun trimsBleMacsToTheirLastTwoOctets() {
        val line = DiagnosticsLog.redact("BLE: Connecting to C4:DE:E2:9A:1B:7F")
        assertFalse("C4:DE:E2:9A" in line, "the OUI half of a MAC must not survive")
        assertTrue(line.endsWith("1B:7F"), "enough must survive to tell two radios apart: $line")
    }

    @Test
    fun twoRadiosStayDistinguishableAfterRedaction() {
        val a = DiagnosticsLog.redact("BLE: Disconnected from C4:DE:E2:9A:1B:7F")
        val b = DiagnosticsLog.redact("BLE: Disconnected from C4:DE:E2:9A:20:01")
        assertFalse(a == b)
    }

    @Test
    fun shortHexUntouched() {
        // 12-char prefixes (pubkey short forms) are fine to log.
        val line = "contact a1b2c3d4e5f6 updated"
        assertEquals(line, DiagnosticsLog.redact(line))
    }
}
