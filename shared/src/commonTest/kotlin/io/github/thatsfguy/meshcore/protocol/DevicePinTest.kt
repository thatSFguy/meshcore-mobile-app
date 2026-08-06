package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The BLE pairing PIN frame.
 *
 * The PIN a user types is six ASCII digits; the wire wants a 32-bit
 * little-endian integer. The firmware's handler settles it —
 * `len >= 5` and `memcpy(&pin, &cmd_frame[1], 4)` into a `uint32_t`
 * (`companion_radio/MyMesh.cpp`) — so this pins the byte layout rather
 * than trusting that a builder and a parser written by the same hand
 * agree with each other (LESSONS §8).
 */
class DevicePinTest {

    @Test
    fun `the default PIN encodes to the bytes the firmware reads`() {
        // 123456 = 0x0001E240, little-endian.
        val frame = Frames.setDevicePin(123456)
        assertContentEquals(
            byteArrayOf(37, 0x40, 0xE2.toByte(), 0x01, 0x00),
            frame,
        )
    }

    @Test
    fun `the frame is the five bytes the handler requires`() {
        // The firmware rejects anything shorter than 5.
        for (pin in listOf(0, 1, 123456, 999999)) {
            assertEquals(5, Frames.setDevicePin(pin).size, "wrong length for $pin")
            assertEquals(Codes.CMD_SET_DEVICE_PIN, Frames.setDevicePin(pin)[0].toInt())
        }
    }

    @Test
    fun `a PIN with leading zeros is a number, not a string`() {
        // "000123" typed by the user is 123 on the wire. If this ever
        // encoded ASCII instead, the radio would take a different PIN
        // than the one displayed and lock the user out of BLE.
        assertContentEquals(byteArrayOf(37, 123, 0, 0, 0), Frames.setDevicePin(123))
        assertContentEquals(byteArrayOf(37, 0, 0, 0, 0), Frames.setDevicePin(0))
    }

    @Test
    fun `the largest six-digit PIN round-trips`() {
        // 999999 = 0x000F423F
        assertContentEquals(
            byteArrayOf(37, 0x3F, 0x42, 0x0F, 0x00),
            Frames.setDevicePin(999999),
        )
    }

    // --- what the FIRMWARE accepts ----------------------------------------
    //
    // These were wrong in 0.6.4. The tests asserted my assumption —
    // "any six digits" — and passed, which is exactly the failure
    // LESSONS §8 describes: both halves written by the same hand,
    // agreeing with each other and with nothing else. The rule below is
    // copied from the handler:
    //     if (pin == 0 || (pin >= 100000 && pin <= 999999))

    @Test
    fun `a PIN starting with zero is refused, because the radio refuses it`() {
        // The 0.6.4 bug: "012345" was offered, sent as 12345, and
        // rejected with ERR_CODE_ILLEGAL_ARG.
        assertFalse(DevicePin.isValid("012345"))
        assertFalse(DevicePin.isValid("000123"))
        assertNotNull(DevicePin.rejection("012345"))
    }

    @Test
    fun `six digits from 100000 to 999999 are accepted`() {
        assertTrue(DevicePin.isValid("100000"))
        assertTrue(DevicePin.isValid("123456"))
        assertTrue(DevicePin.isValid("999999"))
        assertEquals(100000, DevicePin.parse("100000"))
        assertEquals(999999, DevicePin.parse("999999"))
    }

    @Test
    fun `wrong lengths and non-digits are refused`() {
        for (bad in listOf("", "12345", "1234567", "12345a", "12 456", "-12345")) {
            assertFalse(DevicePin.isValid(bad), "accepted \"$bad\"")
            assertEquals(null, DevicePin.parse(bad))
        }
    }

    @Test
    fun `all zeros clears the PIN rather than setting one`() {
        // Zero is a real, accepted value with a DIFFERENT meaning: the
        // node drops its stored PIN and uses its compiled default.
        assertTrue(DevicePin.isUseDefault("000000"))
        assertFalse(DevicePin.isValid("000000"))
        assertTrue(DevicePin.isAcceptable("000000"))
        assertEquals(DevicePin.USE_DEFAULT, DevicePin.parse("000000"))
        assertEquals(byteArrayOf(37, 0, 0, 0, 0).toList(), Frames.setDevicePin(0).toList())
    }

    @Test
    fun `everything acceptable encodes inside the firmware's range`() {
        // A constraint, not examples: whatever the UI lets through must
        // satisfy the handler's own condition.
        for (text in listOf("100000", "123456", "654321", "999999", "000000")) {
            val pin = DevicePin.parse(text)
            assertNotNull(pin, "rejected \"$text\"")
            assertTrue(
                pin == 0 || pin in DevicePin.MIN..DevicePin.MAX,
                "\"$text\" encodes to $pin, which the firmware rejects",
            )
        }
    }

    @Test
    fun `the factory default is recognised so it can be called out`() {
        assertTrue(DevicePin.isFactoryDefault("123456"))
        assertFalse(DevicePin.isFactoryDefault("654321"))
    }

    // --- reporting what the node has ---------------------------------------

    @Test
    fun `a reported zero is described, never shown as a typeable PIN`() {
        // "000000" on screen would read as a PIN somebody could enter.
        assertTrue(DevicePin.describe(0L).contains("built-in default"))
        assertFalse(DevicePin.describe(0L).contains("000000"))
        assertEquals("654321", DevicePin.describe(654321L))
        assertTrue(DevicePin.describe(null).contains("Unknown"))
    }
}
