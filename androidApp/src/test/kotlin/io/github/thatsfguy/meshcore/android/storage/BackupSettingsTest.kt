package io.github.thatsfguy.meshcore.android.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The preference allow-list that decides what a config backup carries
 * (PARITY §1).
 *
 * This is the seam where a secret would leak into the plain half of a
 * backup, so the tests are mostly about what must NOT be in there —
 * including for keys that don't exist yet, which is why the sealed-blob
 * case is asserted structurally rather than by name.
 */
@RunWith(RobolectricTestRunner::class)
class BackupSettingsTest {

    private lateinit var prefs: Preferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("meshcore_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = Preferences(context)
    }

    @Test
    fun exportsTheAllowListedPreferences() {
        prefs.theme = "dark"
        prefs.diagnosticsEnabled = true
        prefs.autoReconnect = false

        val exported = prefs.exportableSettings()
        assertEquals("dark", exported["theme"])
        assertEquals("true", exported["diagnostics_enabled"])
        assertEquals("false", exported["auto_reconnect"])
    }

    @Test
    fun neverExportsASealedSecret() {
        // Sealed blobs are the Keystore's business; they belong in the
        // encrypted section or nowhere near a file.
        prefs.putSealed("login_${"bb".repeat(32)}", "c2VjcmV0")
        prefs.putSealed("identity_seed", "c2VlZA==")

        val exported = prefs.exportableSettings()
        assertTrue(
            exported.keys.none { it.startsWith("sealed_") || it.startsWith("login_") },
            "a sealed key reached the plain export: ${exported.keys}",
        )
        assertTrue(
            exported.values.none { it == "c2VjcmV0" || it == "c2VlZA==" },
            "sealed material reached the plain export",
        )
    }

    @Test
    fun neverExportsDeviceSpecificConnectionDetails() {
        prefs.lastBleAddress = "10:06:1C:31:42:2E"
        prefs.lastBleName = "MeshCore-Blue"
        prefs.lastTcpHost = "192.168.1.50"

        val exported = prefs.exportableSettings()
        for (key in listOf("last_ble_address", "last_ble_name", "last_tcp_host", "last_tcp_port")) {
            assertFalse(key in exported, "$key must not be exported")
        }
        assertTrue(exported.values.none { "10:06" in it || "192.168" in it })
    }

    @Test
    fun importOnlyAcceptsAllowListedKeys() {
        val applied = prefs.importSettings(
            mapOf(
                "theme" to "light",
                "sealed_login_deadbeef" to "c2VjcmV0",
                "last_ble_address" to "AA:BB:CC:DD:EE:FF",
                "not_a_real_setting" to "1",
            ),
        )
        assertEquals(1, applied)
        assertEquals("light", prefs.theme)
        assertEquals(null, prefs.lastBleAddress)
        assertEquals(null, prefs.getSealed("login_deadbeef"))
    }

    @Test
    fun importCannotEnableTcpBehindItsWarning() {
        // TCP is plaintext and gated on a one-time stern warning. A file
        // must not be able to turn it on for someone who never saw it.
        assertFalse(prefs.tcpWarningAccepted)
        prefs.importSettings(mapOf("transport_tcp_enabled" to "true"))
        assertFalse(prefs.tcpEnabled, "a backup enabled TCP without the warning")

        // Once the user has accepted it on THIS device, restoring is fine.
        prefs.tcpWarningAccepted = true
        prefs.importSettings(mapOf("transport_tcp_enabled" to "true"))
        assertTrue(prefs.tcpEnabled)
    }

    @Test
    fun importIgnoresIllTypedValues() {
        prefs.theme = "dark"
        prefs.importSettings(
            mapOf(
                "diagnostics_enabled" to "yes please",  // not a strict boolean
                "nodes_tab" to "three",                  // not an int
            ),
        )
        assertFalse(prefs.diagnosticsEnabled)
        assertEquals(0, prefs.nodesTab)
        assertEquals("dark", prefs.theme, "an ill-typed row disturbed an unrelated setting")
    }

    @Test
    fun exportImportRoundTripsCleanly() {
        prefs.theme = "light"
        prefs.diagnosticsEnabled = true
        prefs.mapTilesEnabled = false
        prefs.notificationsEnabled = false
        val exported = prefs.exportableSettings()

        // Wipe, then restore.
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("meshcore_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val fresh = Preferences(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )
        val applied = fresh.importSettings(exported)

        assertEquals(exported.size, applied)
        assertEquals("light", fresh.theme)
        assertTrue(fresh.diagnosticsEnabled)
        assertFalse(fresh.mapTilesEnabled)
        assertFalse(fresh.notificationsEnabled)
    }

    @Test
    fun anEmptyImportIsANoOp() {
        prefs.theme = "dark"
        assertEquals(0, prefs.importSettings(emptyMap()))
        assertEquals("dark", prefs.theme)
    }
}
