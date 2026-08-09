package io.github.thatsfguy.meshcore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What counts as a channel, in one place.
 *
 * There were two answers and they disagreed. The channel sweep dropped
 * unconfigured slots; the per-slot event handler kept them. Every screen
 * that reads a slot goes through the handler — and the Channels settings
 * screen reads EVERY slot the radio has, to offer them for editing — so
 * opening that screen refilled the list with blank entries. Those were
 * persisted and rendered in Chats as "Channel 2", "Channel 3", "Channel
 * 4": conversations that do not exist.
 *
 * Same shape as the trace-flags and neighbours bugs — two halves of one
 * codebase with different ideas about the same thing, and a green suite
 * throughout. These tests exist so the halves cannot drift again.
 */
class ChannelListTest {

    private fun ch(index: Int, name: String = "n$index", psk: Int = 1) =
        Channel(index, name, ByteArray(16) { psk.toByte() })

    /** Exactly what an unconfigured slot looks like on the wire. */
    private fun emptySlot(index: Int) = Channel(index, "", ByteArray(16))

    @Test
    fun anUnconfiguredSlotIsNotAChannel() {
        // The reported bug, at its root: slot 2 came back with a blank
        // name and an all-zero PSK, and was added to the list anyway.
        assertTrue(emptySlot(2).isEmpty)
        assertEquals(emptyList(), ChannelList.applySlot(emptyList(), emptySlot(2)))
    }

    @Test
    fun readingEverySlotLeavesOnlyTheConfiguredOnes() {
        // The Channels settings screen does exactly this: asks for all
        // eight slots. Before the fix, the list came back with eight.
        val slots = listOf(
            ch(0, "Public"), ch(1, "somechannel"),
            emptySlot(2), emptySlot(3), emptySlot(4),
            emptySlot(5), emptySlot(6), emptySlot(7),
        )
        val result = ChannelList.fromSlots(slots)
        assertEquals(listOf(0, 1), result.map { it.index })
        assertEquals(listOf("Public", "somechannel"), result.map { it.name })
    }

    @Test
    fun clearingAChannelRemovesItRatherThanBlankingIt() {
        // The case the old code could not express at all: a slot that
        // WAS a channel and has been wiped. Keeping it left a nameless
        // row in Chats that could never be opened usefully.
        val list = ChannelList.applySlot(emptyList(), ch(1, "somechannel"))
        assertEquals(1, list.size)
        assertEquals(emptyList(), ChannelList.applySlot(list, emptySlot(1)))
    }

    @Test
    fun aSlotReplacesItsOwnIndexAndNothingElse() {
        var list = ChannelList.fromSlots(listOf(ch(0, "Public"), ch(1, "old")))
        list = ChannelList.applySlot(list, ch(1, "renamed"))
        assertEquals(listOf("Public", "renamed"), list.map { it.name })
        assertEquals(2, list.size, "a re-read must not duplicate its slot")
    }

    @Test
    fun theListStaysSortedByIndexWhateverOrderSlotsArriveIn() {
        // Slots arrive in whatever order the radio answers; the UI shows
        // them as "0: Public, 1: …" and must not reorder between reads.
        val list = ChannelList.fromSlots(listOf(ch(3), ch(0), ch(7), ch(1)))
        assertEquals(listOf(0, 1, 3, 7), list.map { it.index })
    }

    @Test
    fun aNamedSlotWithNoKeyIsStillAChannel() {
        // isEmpty needs BOTH blank name and zero key. A named channel
        // whose key failed to load is a real row with a real problem —
        // hiding it would hide the problem too.
        val named = Channel(2, "Public", ByteArray(16))
        assertTrue(!named.isEmpty)
        assertEquals(listOf(2), ChannelList.applySlot(emptyList(), named).map { it.index })
    }

    @Test
    fun anUnnamedSlotWithARealKeyIsStillAChannel() {
        // The mirror case: somebody joined a channel by key and never
        // named it. Dropping that would lose a channel they can receive
        // on, which is worse than an ugly row.
        val keyed = Channel(4, "", ByteArray(16) { 7 })
        assertTrue(!keyed.isEmpty)
        assertEquals(listOf(4), ChannelList.applySlot(emptyList(), keyed).map { it.index })
    }

    @Test
    fun repeatedlyReadingTheSameSlotsIsIdempotent() {
        // Opening the Channels screen twice must not grow the list —
        // that is precisely how the rows accumulated.
        val slots = listOf(ch(0, "Public"), emptySlot(1), ch(2, "x"))
        val once = ChannelList.fromSlots(slots)
        val twice = slots.fold(once) { acc, s -> ChannelList.applySlot(acc, s) }
        assertEquals(once, twice)
    }
}
