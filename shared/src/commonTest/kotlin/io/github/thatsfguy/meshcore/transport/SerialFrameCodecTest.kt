package io.github.thatsfguy.meshcore.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SerialFrameCodecTest {

    private fun rxFramed(payload: ByteArray): ByteArray {
        val out = ByteArray(3 + payload.size)
        out[0] = SerialFraming.RX_FRAME_START.toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, 3)
        return out
    }

    @Test
    fun wrapTxLayout() {
        val f = SerialFraming.wrapTx(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(0x3C, 3, 0, 1, 2, 3), f)
    }

    @Test
    fun wrapTxRejectsOversize() {
        assertFailsWith<IllegalArgumentException> {
            SerialFraming.wrapTx(ByteArray(SerialFraming.MAX_PAYLOAD_LENGTH + 1))
        }
    }

    @Test
    fun decodeSingleFrame() {
        val decoder = SerialFrameDecoder()
        val payload = byteArrayOf(5, 6, 7, 8)
        val frames = decoder.ingest(rxFramed(payload))
        assertEquals(1, frames.size)
        assertTrue(frames[0].isRxFrame)
        assertContentEquals(payload, frames[0].payload)
    }

    @Test
    fun decodeAcrossChunkBoundaries() {
        val decoder = SerialFrameDecoder()
        val framed = rxFramed(ByteArray(50) { it.toByte() })
        var total = 0
        for (b in framed) {
            total += decoder.ingest(byteArrayOf(b)).size
        }
        assertEquals(1, total)
    }

    @Test
    fun decodeMultipleFramesInOneChunk() {
        val decoder = SerialFrameDecoder()
        val a = rxFramed(byteArrayOf(1))
        val b = rxFramed(byteArrayOf(2, 3))
        val frames = decoder.ingest(a + b)
        assertEquals(2, frames.size)
        assertContentEquals(byteArrayOf(1), frames[0].payload)
        assertContentEquals(byteArrayOf(2, 3), frames[1].payload)
    }

    @Test
    fun resyncsAfterGarbage() {
        val decoder = SerialFrameDecoder()
        val garbage = byteArrayOf(0x00, 0x7F, 0x41)
        val frames = decoder.ingest(garbage + rxFramed(byteArrayOf(9)))
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(9), frames[0].payload)
    }

    @Test
    fun skipsImplausibleLengthHeader() {
        val decoder = SerialFrameDecoder()
        // 0x3E followed by a length far above MAX — must resync, then
        // find the real frame that follows.
        val bogus = byteArrayOf(0x3E, 0xFF.toByte(), 0x7F)
        val frames = decoder.ingest(bogus + rxFramed(byteArrayOf(4)))
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(4), frames[0].payload)
    }

    @Test
    fun txFramesAreDecodedButFlagged() {
        val decoder = SerialFrameDecoder()
        val frames = decoder.ingest(SerialFraming.wrapTx(byteArrayOf(1)))
        assertEquals(1, frames.size)
        assertEquals(false, frames[0].isRxFrame)
    }

    @Test
    fun emptyPayloadFrameDecodes() {
        val decoder = SerialFrameDecoder()
        val frames = decoder.ingest(rxFramed(ByteArray(0)))
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].payload.size)
    }
}
