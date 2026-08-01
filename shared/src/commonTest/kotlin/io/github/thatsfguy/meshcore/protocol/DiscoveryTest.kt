package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Active node discovery and the anonymous-request frame (PARITY §8).
 *
 * Discovery frames are broadcast and unauthenticated: anyone in range
 * hears the request and can answer it. The parser's job is to refuse
 * everything that isn't a well-formed answer to *our* tag, and to hand
 * back a key prefix that the caller still has to resolve against known
 * contacts — a prefix is not an identity (PARITY §12).
 */
class DiscoveryTest {

    /** 3 header bytes + type + inbound SNR + u32 tag + ≥1 prefix byte. */
    private val MIN_RESPONSE_BYTES = 10

    private val tag = 0x11223344L
    private val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

    /** A well-formed DISCOVER_RESP payload (push code already stripped). */
    private fun response(
        tag: Long = this.tag,
        subtype: Int = Codes.CONTROL_SUBTYPE_DISCOVER_RESP,
        advType: Int = Codes.ADV_TYPE_REPEATER,
        prefix: ByteArray = this.prefix,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(10)   // SNR
        w.writeByte(0xB0) // RSSI
        w.writeByte(0)    // path_len
        w.writeByte((subtype shl 4) or advType)
        w.writeByte(12)   // inbound SNR reported by the responder
        w.writeUInt32LE(tag)
        w.writeBytes(prefix)
        return w.toBytes()
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    @Test
    fun parsesAWellFormedResponse() {
        assertEquals("aabbccdd", NodeDiscovery.parseDiscoveryResponse(response(), tag))
    }

    @Test
    fun rejectsAResponseForSomeoneElsesTag() {
        // Correlation is the only thing separating our discovery from a
        // stranger's, so a mismatched tag must be dropped outright.
        assertNull(NodeDiscovery.parseDiscoveryResponse(response(tag = tag + 1), tag))
        assertNull(NodeDiscovery.parseDiscoveryResponse(response(tag = 0), tag))
    }

    @Test
    fun rejectsTheWrongControlSubtype() {
        // Our own outbound request echoed back is not a response.
        assertNull(
            NodeDiscovery.parseDiscoveryResponse(
                response(subtype = Codes.CONTROL_SUBTYPE_DISCOVER_REQ), tag,
            ),
        )
        assertNull(NodeDiscovery.parseDiscoveryResponse(response(subtype = 0), tag))
    }

    @Test
    fun rejectsTheWrongNodeType() {
        // Asking for repeaters must not accept a chat node's answer.
        assertNull(
            NodeDiscovery.parseDiscoveryResponse(
                response(advType = Codes.ADV_TYPE_CHAT), tag, Codes.ADV_TYPE_REPEATER,
            ),
        )
        assertEquals(
            "aabbccdd",
            NodeDiscovery.parseDiscoveryResponse(
                response(advType = Codes.ADV_TYPE_CHAT), tag, Codes.ADV_TYPE_CHAT,
            ),
        )
    }

    @Test
    fun rejectsTruncatedFramesAtEveryLength() {
        // The shortest well-formed response is 9 header bytes plus one
        // prefix byte. Everything below that must be refused, not read
        // past; everything at or above it is a valid short prefix.
        val full = response()
        for (n in 0 until MIN_RESPONSE_BYTES) {
            assertNull(
                NodeDiscovery.parseDiscoveryResponse(full.copyOfRange(0, n), tag),
                "accepted a $n-byte frame",
            )
        }
        for (n in MIN_RESPONSE_BYTES..full.size) {
            val prefixBytes = n - (MIN_RESPONSE_BYTES - 1)
            assertEquals(
                prefix.copyOfRange(0, prefixBytes).toHex(),
                NodeDiscovery.parseDiscoveryResponse(full.copyOfRange(0, n), tag),
                "rejected a valid $n-byte frame",
            )
        }
    }

    @Test
    fun rejectsAResponseCarryingNoPrefix() {
        assertNull(NodeDiscovery.parseDiscoveryResponse(response(prefix = ByteArray(0)), tag))
    }

    @Test
    fun acceptsAFullKeyAsWellAsAPrefix() {
        // prefixOnly is a request, not a guarantee — a responder may
        // send its whole key.
        val key = ByteArray(32) { (it + 1).toByte() }
        assertEquals(
            key.toHex(),
            NodeDiscovery.parseDiscoveryResponse(response(prefix = key), tag),
        )
    }

    // ------------------------------------------------------------------
    // Prefix → contact resolution
    // ------------------------------------------------------------------

    @Test
    fun matchingReturnsEveryContactSharingThePrefixAndNeverPicksOne() {
        // Two bytes is 16 bits — cheap to collide. A prefix that matches
        // two contacts matches two contacts (PARITY §12).
        val keys = listOf("aabbccdd0011", "aabbccdd9999", "ffffffffffff")
        val hits = NodeDiscovery.matching("aabbccdd", keys) { it }
        assertEquals(listOf("aabbccdd0011", "aabbccdd9999"), hits)
        assertEquals(emptyList(), NodeDiscovery.matching("", keys) { it })
        assertEquals(listOf("ffffffffffff"), NodeDiscovery.matching("FFFF", keys) { it })
    }

    // ------------------------------------------------------------------
    // Frame builders
    // ------------------------------------------------------------------

    @Test
    fun discoveryRequestPayloadHasTheDocumentedLayout() {
        val payload = Frames.discoveryRequestPayload(0xCAFEBABEL)
        assertEquals(10, payload.size)
        assertEquals(
            (Codes.CONTROL_SUBTYPE_DISCOVER_REQ shl 4) or Codes.DISCOVER_FLAG_PREFIX_ONLY,
            payload[0].toInt() and 0xFF,
        )
        assertEquals(1 shl Codes.ADV_TYPE_REPEATER, payload[1].toInt() and 0xFF)
        val r = BufferReader(payload)
        r.readBytes(2)
        assertEquals(0xCAFEBABEL, r.readUInt32LE())
        assertEquals(0L, r.readUInt32LE()) // since = 0

        // Without prefixOnly the low nibble clears.
        val full = Frames.discoveryRequestPayload(1L, prefixOnly = false)
        assertEquals(Codes.CONTROL_SUBTYPE_DISCOVER_REQ shl 4, full[0].toInt() and 0xFF)
    }

    @Test
    fun controlDataFrameIsJustTheCommandPlusPayload() {
        val frame = Frames.sendControlData(byteArrayOf(1, 2, 3))
        assertEquals(Codes.CMD_SEND_CONTROL_DATA, frame[0].toInt() and 0xFF)
        assertEquals(listOf<Byte>(1, 2, 3), frame.drop(1))
    }

    @Test
    fun anonRequestEncodesWidthAndHopCountInOneByte() {
        val key = ByteArray(32) { 7 }
        val frame = Frames.sendAnonRequest(key, Codes.ANON_REQ_TYPE_REGIONS)
        assertEquals(Codes.CMD_SEND_ANON_REQ, frame[0].toInt() and 0xFF)
        assertEquals(key.toList(), frame.copyOfRange(1, 33).toList())
        assertEquals(Codes.ANON_REQ_TYPE_REGIONS, frame[33].toInt() and 0xFF)
        // width 1, 0 hops → 0x00
        assertEquals(0x00, frame[34].toInt() and 0xFF)
        assertEquals(35, frame.size)
    }

    @Test
    fun anonRequestPacksEveryWidthAndHopCombination() {
        val key = ByteArray(32)
        for (width in 1..4) {
            for (hops in 0..63) {
                val path = ByteArray(width * hops)
                val frame = Frames.sendAnonRequest(
                    key, Codes.ANON_REQ_TYPE_REGIONS, path, hops, width,
                )
                val encoded = frame[34].toInt() and 0xFF
                assertEquals(width - 1, encoded shr 6, "width $width hops $hops")
                assertEquals(hops, encoded and 0x3F, "width $width hops $hops")
                assertEquals(35 + path.size, frame.size)
            }
        }
    }

    @Test
    fun anonRequestClampsAnOutOfRangeHashWidth() {
        val key = ByteArray(32)
        for (width in listOf(-1, 0, 5, 99)) {
            val frame = Frames.sendAnonRequest(key, 1, ByteArray(0), 0, width)
            val encoded = (frame[34].toInt() and 0xFF) shr 6
            assertTrue(encoded in 0..3, "width $width encoded to $encoded")
        }
    }

    @Test
    fun anonRequestRefusesAKeyThatIsNotAKey() {
        for (size in listOf(0, 6, 31, 33, 64)) {
            assertFailsWith<IllegalArgumentException> {
                Frames.sendAnonRequest(ByteArray(size), Codes.ANON_REQ_TYPE_REGIONS)
            }
        }
    }

    @Test
    fun replyPathIsReversedByHopNotByByte() {
        // Reversing raw bytes would scramble each multi-byte hop hash
        // instead of the hop order — the reply would route nowhere.
        val path = byteArrayOf(0x11, 0x12, 0x21, 0x22, 0x31, 0x32)
        assertEquals(
            listOf<Byte>(0x31, 0x32, 0x21, 0x22, 0x11, 0x12),
            Frames.reversePathByHop(path, 2).toList(),
        )
        // Width 1 degenerates to a plain byte reversal.
        assertEquals(
            listOf<Byte>(3, 2, 1),
            Frames.reversePathByHop(byteArrayOf(1, 2, 3), 1).toList(),
        )
        assertEquals(0, Frames.reversePathByHop(ByteArray(0), 2).size)
    }

    @Test
    fun aPathThatIsNotAWholeNumberOfHopsStillReverses() {
        // Malformed rather than dropped: matches the reference client,
        // and losing the path silently would be worse than an odd one.
        val odd = byteArrayOf(1, 2, 3)
        assertEquals(listOf<Byte>(3, 2, 1), Frames.reversePathByHop(odd, 2).toList())
    }

    @Test
    fun reversingTwiceIsTheIdentityForWholeHopPaths() {
        for (width in 1..4) {
            for (hops in 0..8) {
                val path = ByteArray(width * hops) { (it + 1).toByte() }
                val back = Frames.reversePathByHop(Frames.reversePathByHop(path, width), width)
                assertEquals(path.toList(), back.toList(), "width $width hops $hops")
            }
        }
    }
}
