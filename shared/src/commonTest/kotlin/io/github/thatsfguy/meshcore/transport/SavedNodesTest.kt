package io.github.thatsfguy.meshcore.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class SavedNodesTest {
    private val blue = SavedNode(ConnectionMemory.KIND_BLE, "UUID-1", null, "MeshCore-Blue Base")
    private val pro = SavedNode(ConnectionMemory.KIND_BLE, "UUID-2", null, "MeshCore-BluePro1W")
    private val tcp = SavedNode(ConnectionMemory.KIND_TCP, "192.168.40.10", 5000, null)

    @Test
    fun theListRoundTripsAndSkipsJunk() {
        val raw = SavedNodes.encode(listOf(blue, tcp))
        assertEquals(listOf(blue, tcp), SavedNodes.decode(raw))
        assertEquals(listOf(blue), SavedNodes.decode(raw.lines().first() + "\n\ngarbage"))
        assertEquals(emptyList(), SavedNodes.decode(null))
    }

    @Test
    fun savingAgainReplacesByKeyAndForgettingRemoves() {
        var list = SavedNodes.with(SavedNodes.with(emptyList(), blue), pro)
        list = SavedNodes.with(list, blue.copy(name = "Renamed"))
        assertEquals(listOf("MeshCore-BluePro1W", "Renamed"), list.map { SavedNodes.label(it) })
        assertEquals(listOf(pro), SavedNodes.without(list, blue.key))
    }

    @Test
    fun aTcpRadioIsLabelledByItsAddress() {
        assertEquals("192.168.40.10:5000", SavedNodes.label(tcp))
        assertEquals(false, SavedNodes.isBle(tcp))
    }
}
