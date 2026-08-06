package io.github.thatsfguy.meshcore.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A live connection and its saved-list entry must describe the same
 * node.
 *
 * The reported bug: Forget removed a radio from Saved nodes but left
 * it in the auto-reconnect memory, so it reconnected — and because
 * that path never re-added it, the app finished up connected to a node
 * missing from its own list. Two stores, one of them updated.
 *
 * Both are written from the service now, and [SavedNode.of] is how one
 * derives the other. If these keys ever stop matching, "forget" stops
 * being able to find what it is meant to forget.
 */
class SavedNodeOfTest {

    @Test
    fun `a BLE connection and its saved entry share a key`() {
        val memory = ConnectionMemory.Ble("10:06:1C:31:42:2E", "MeshCore-Blue")
        val saved = SavedNode.of(memory)
        assertEquals(ConnectionMemory.KIND_BLE, saved.kind)
        assertEquals("10:06:1C:31:42:2E", saved.address)
        assertEquals("MeshCore-Blue", saved.name)
        // The key "forget" matches on.
        assertEquals(
            SavedNode(ConnectionMemory.KIND_BLE, "10:06:1C:31:42:2E", null, null).key,
            saved.key,
        )
    }

    @Test
    fun `a TCP connection and its saved entry share a key`() {
        val saved = SavedNode.of(ConnectionMemory.Tcp("192.168.40.10", 5000))
        assertEquals(ConnectionMemory.KIND_TCP, saved.kind)
        assertEquals("192.168.40.10", saved.address)
        assertEquals(5000, saved.port)
        assertEquals(
            SavedNode(ConnectionMemory.KIND_TCP, "192.168.40.10", 5000).key,
            saved.key,
        )
    }

    @Test
    fun `the name is not part of the identity`() {
        // A radio that renames itself must not become a second entry,
        // or forgetting the one you can see leaves the other behind.
        val a = SavedNode.of(ConnectionMemory.Ble("AA:BB", "Blue"))
        val b = SavedNode.of(ConnectionMemory.Ble("AA:BB", "Blue (renamed)"))
        assertEquals(a.key, b.key)
    }

    @Test
    fun `BLE and TCP at the same address are different nodes`() {
        val ble = SavedNode.of(ConnectionMemory.Ble("192.168.40.10", null))
        val tcp = SavedNode.of(ConnectionMemory.Tcp("192.168.40.10", 5000))
        assertEquals(false, ble.key == tcp.key)
    }

    @Test
    fun `a TCP node's port is part of its identity`() {
        val a = SavedNode.of(ConnectionMemory.Tcp("host", 5000))
        val b = SavedNode.of(ConnectionMemory.Tcp("host", 5001))
        assertEquals(false, a.key == b.key)
    }
}
