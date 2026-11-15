package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals

class NewMessageTest {

    private data class C(
        override val name: String,
        val type: Int = Codes.ADV_TYPE_CHAT,
        override val lastMessageAt: Long = 0,
        override val keyHex: String = name.lowercase().padEnd(64, '0'),
    ) : NodeListItem {
        override val flags = 0
        override val pathLen = 0
        override val lastSeen = 0L
        override val lastModified = 0L
        override val unread = 0
    }

    private fun names(list: List<C>, q: String = "") =
        NewMessage.candidates(list, { it.type }, q).map { it.name }

    @Test
    fun `people are offered with the last talked to first and then by name`() {
        val list = listOf(
            C("zed"),
            C("Amy"),
            C("Bob", lastMessageAt = 5),
            C("carl", lastMessageAt = 9),
        )
        assertEquals(listOf("carl", "Bob", "Amy", "zed"), names(list))
    }

    @Test
    fun `repeaters and rooms and sensors are not offered`() {
        val list = listOf(
            C("Hill", Codes.ADV_TYPE_REPEATER),
            C("Lobby", Codes.ADV_TYPE_ROOM),
            C("Rain", Codes.ADV_TYPE_SENSOR),
            C("Dana"),
        )
        assertEquals(listOf("Dana"), names(list))
    }

    @Test
    fun `search matches any part of the name regardless of case`() {
        val list = listOf(C("Engine 7"), C("Medic 2"), C("engineer"))
        assertEquals(listOf("Engine 7", "engineer"), names(list, " ENGINE "))
        assertEquals(emptyList(), names(list, "nobody"))
    }
}
