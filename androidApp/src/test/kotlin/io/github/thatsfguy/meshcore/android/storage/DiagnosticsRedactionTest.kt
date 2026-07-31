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
    fun shortHexUntouched() {
        // 12-char prefixes (pubkey short forms) are fine to log.
        val line = "contact a1b2c3d4e5f6 updated"
        assertEquals(line, DiagnosticsLog.redact(line))
    }
}
