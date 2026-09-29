package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.presentation.radioDefaultScopeLine
import io.github.thatsfguy.meshcore.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The companion's saved default scope, pinned against the firmware's
 * READER and WRITER (companion_radio/MyMesh.cpp:1940-1966), not against
 * this app's own parser.
 */
class DefaultFloodScopeFramesTest {

    /** SHA256("#mi")[0..15], computed outside this codebase. */
    private val miKey = hexToBytes("b95485d889ee9da51719e02ec556bf97")

    @Test
    fun setWritesNameIntoA31ByteFieldThenTheKey() {
        // The reader: strlen(&cmd_frame[1]) must be 1..30, the key is at
        // cmd_frame[1+31], and the frame must be at least 1+31+16 long.
        val frame = Frames.setDefaultFloodScope("mi", miKey)!!
        assertEquals(1 + 31 + 16, frame.size)
        assertEquals(63, frame[0].toInt())
        assertContentEquals("mi".encodeToByteArray(), frame.copyOfRange(1, 3))
        assertTrue(frame.copyOfRange(3, 32).all { it.toInt() == 0 }, "name must be NUL-padded")
        assertContentEquals(miKey, frame.copyOfRange(32, 48))
    }

    @Test
    fun namesTheFirmwareWouldRefuseAreNotBuilt() {
        // `n > 0 && n < 31` — 30 is the longest it keeps.
        assertTrue(Frames.setDefaultFloodScope("a".repeat(30), miKey) != null)
        assertNull(Frames.setDefaultFloodScope("a".repeat(31), miKey))
        assertNull(Frames.setDefaultFloodScope("", miKey))
        assertFailsWith<IllegalArgumentException> { Frames.setDefaultFloodScope("mi", ByteArray(15)) }
    }

    @Test
    fun clearIsTheBareCommand() {
        // A frame shorter than 1+31+16 is the "set default to null" branch.
        assertContentEquals(byteArrayOf(63), Frames.clearDefaultFloodScope())
        assertContentEquals(byteArrayOf(64), Frames.getDefaultFloodScope())
    }

    @Test
    fun aOneByteReplyMeansNoDefault() {
        // `_serial->writeFrame(out_frame, 1);   // no name or key means null`
        val ev = assertIs<DeviceEvent.DefaultFloodScope>(ResponseParser.parse(byteArrayOf(28)))
        assertNull(ev.name)
        assertNull(ev.key)
    }

    @Test
    fun aFullReplyCarriesNameAndKey() {
        val frame = byteArrayOf(28) + "mi".encodeToByteArray() + ByteArray(29) + miKey
        val ev = assertIs<DeviceEvent.DefaultFloodScope>(ResponseParser.parse(frame))
        assertEquals("mi", ev.name)
        assertContentEquals(miKey, ev.key)
    }

    @Test
    fun aTruncatedReplyIsNotReadAsNoDefault() {
        // Neither one byte nor the full 48: it must not become "None",
        // which would claim the radio floods untagged.
        val frame = byteArrayOf(28) + "mi".encodeToByteArray() + ByteArray(10)
        assertIs<DeviceEvent.Unknown>(ResponseParser.parse(frame))
    }

    @Test
    fun theLineSaysWhatTheRadioReported() {
        assertNull(radioDefaultScopeLine(RadioDefaultScope.NotRead))
        assertEquals(
            "The radio's saved default scope is region mi.",
            radioDefaultScopeLine(RadioDefaultScope.Set("mi", isPublicRegion = true)),
        )
        assertEquals("The radio has no saved default scope.", radioDefaultScopeLine(RadioDefaultScope.None))
        val private = radioDefaultScopeLine(RadioDefaultScope.Set("mi", isPublicRegion = false))!!
        assertTrue(private.contains("won't match"))
        assertFalse(private.contains("region mi."))
        assertTrue(radioDefaultScopeLine(RadioDefaultScope.Unsupported)!!.contains("doesn't report"))
    }
}
