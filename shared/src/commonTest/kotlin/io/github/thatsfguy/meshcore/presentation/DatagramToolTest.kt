package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DatagramToolTest {
    @Test
    fun aGoodRequestParses() {
        val r = assertNotNull(DatagramTool.parse("2", "ff00", "0a0b0c"))
        assertEquals(2, r.channel)
        assertEquals(0xFF00, r.dataType)
        assertEquals(3, r.payload.size)
        assertEquals("Sent 3 bytes to channel 2.", DatagramTool.result(true, r))
        assertEquals(DatagramTool.REFUSED, DatagramTool.result(false, r))
        assertNull(DatagramTool.problem("2", "ff00", "0a0b0c"))
    }

    @Test
    fun badInputSaysWhichPartIsWrong() {
        assertNull(DatagramTool.parse("x", "ff00", "0a"))
        assertEquals("Channel is a slot number.", DatagramTool.problem("x", "ff00", "0a"))
        assertNull(DatagramTool.parse("0", "fffff", "0a"))
        assertEquals("Type is up to 4 hex digits.", DatagramTool.problem("0", "zz", "0a"))
        assertNull(DatagramTool.parse("0", "ff00", "abc"))
        assertEquals(DatagramTool.BAD_PAYLOAD, DatagramTool.problem("0", "ff00", "abc"))
        assertEquals(DatagramTool.BAD_PAYLOAD, DatagramTool.problem("0", "ff00", ""))
    }
}
