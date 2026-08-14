package io.github.thatsfguy.meshcore.platform

import io.github.thatsfguy.meshcore.firmware.LegacyDfu
import io.github.thatsfguy.meshcore.firmware.StaleBondException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Turning a GATT write status into a diagnosis.
 *
 * This matters more than it looks. The MeshCore DFU characteristics
 * require an encrypted, MITM-authenticated link
 * (`SerialBLEInterface.cpp`: `bledfu.setPermission(SECMODE_ENC_WITH_MITM,
 * …)`), so a node bonded before it carried the service fails HERE and
 * only here — and no amount of retrying fixes it. Reading that status
 * as a generic write failure sends someone into an endless retry loop
 * when the answer is one tap in the system Bluetooth settings.
 */
class DfuGattStatusTest {

    @Test
    fun `the CCCD config error is reported as a stale pairing`() {
        // 0xFD is `BLE_GATT_STATUS_ATTERR_CPS_CCCD_CONFIG_ERROR`, which
        // the app-mode service returns when the client has not
        // subscribed — and which a stale bond produces because the bond
        // carries no CCCD state for a service that did not exist then.
        assertEquals(0xFD, LegacyDfu.CCCD_CONFIG_ERROR)
        val e = AndroidDfuGattClient.gattWriteError(LegacyDfu.CCCD_CONFIG_ERROR)
        assertIs<StaleBondException>(e)
    }

    @Test
    fun `authentication failures are a pairing problem, not a dead radio`() {
        for (status in listOf(5, 8, 15)) {
            assertIs<StaleBondException>(
                AndroidDfuGattClient.gattWriteError(status),
                "status $status was not diagnosed as a pairing problem",
            )
        }
    }

    @Test
    fun `an ordinary failure is not blamed on the pairing`() {
        for (status in listOf(1, 3, 7, 133, 257)) {
            val e = AndroidDfuGattClient.gattWriteError(status)
            assertTrue(e !is StaleBondException, "status $status was blamed on pairing")
            assertTrue(e.message!!.contains(status.toString()))
        }
    }

    @Test
    fun `the DFU UUIDs parse to the bootloader's own values`() {
        // 0x1530/0x1531/0x1532 inside Nordic's base UUID
        // (`ble_dfu.h`). A typo here connects to nothing at all.
        assertEquals("00001530-1212-efde-1523-785feabcd123", AndroidDfuGattClient.SERVICE_UUID.toString())
        assertEquals(
            "00001531-1212-efde-1523-785feabcd123",
            AndroidDfuGattClient.CONTROL_POINT_UUID.toString(),
        )
        assertEquals("00001532-1212-efde-1523-785feabcd123", AndroidDfuGattClient.PACKET_UUID.toString())
    }
}
