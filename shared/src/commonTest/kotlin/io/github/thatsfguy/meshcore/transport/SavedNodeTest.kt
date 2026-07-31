package io.github.thatsfguy.meshcore.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedNodeTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val nodes = listOf(
            SavedNode(ConnectionMemory.KIND_BLE, "AA:BB:CC:DD:EE:FF", null, "MeshCore-abc"),
            SavedNode(ConnectionMemory.KIND_TCP, "192.168.40.10", 5000, null),
            SavedNode(ConnectionMemory.KIND_USB, "/dev/bus/usb/001/002", null, "CP210x"),
        )
        for (n in nodes) {
            assertEquals(n, SavedNode.decode(n.encode()))
        }
    }

    @Test
    fun decodeRejectsMalformed() {
        assertNull(SavedNode.decode(""))
        assertNull(SavedNode.decode("just-one-field"))
    }

    @Test
    fun resolveRespectsTcpToggle() {
        val resolved = ConnectionMemory.resolve(
            autoReconnect = true, kind = ConnectionMemory.KIND_TCP,
            bleAddress = null, bleName = null,
            tcpHost = "10.0.0.1", tcpPort = 5000, tcpEnabled = false,
        )
        assertNull(resolved)

        val enabled = ConnectionMemory.resolve(
            autoReconnect = true, kind = ConnectionMemory.KIND_TCP,
            bleAddress = null, bleName = null,
            tcpHost = "10.0.0.1", tcpPort = 5000, tcpEnabled = true,
        )
        assertEquals(ConnectionMemory.Tcp("10.0.0.1", 5000), enabled)
    }

    @Test
    fun resolveBleAndOffSwitch() {
        assertEquals(
            ConnectionMemory.Ble("AA:BB", null),
            ConnectionMemory.resolve(true, "ble", "AA:BB", "", null, null, false),
        )
        assertNull(ConnectionMemory.resolve(false, "ble", "AA:BB", null, null, null, true))
        assertNull(ConnectionMemory.resolve(true, "usb", null, null, null, null, true))
    }
}
