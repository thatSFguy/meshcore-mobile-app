package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- what the UI is allowed to accept ---------------------------------

    @Test
    fun `six digits is the accepted range`() {
        // BLE passkeys are six decimal digits; anything else is not a
        // PIN the pairing dialog can ever ask for.
        assertTrue(DevicePin.isValid("123456"))
        assertTrue(DevicePin.isValid("000000"))
        assertTrue(DevicePin.isValid("999999"))
        assertFalse(DevicePin.isValid("12345"))
        assertFalse(DevicePin.isValid("1234567"))
        assertFalse(DevicePin.isValid(""))
        assertFalse(DevicePin.isValid("12345a"))
        assertFalse(DevicePin.isValid("12 456"))
        assertFalse(DevicePin.isValid("-12345"))
    }

    @Test
    fun `the factory default is recognised so it can be called out`() {
        assertTrue(DevicePin.isFactoryDefault("123456"))
        assertFalse(DevicePin.isFactoryDefault("654321"))
    }

    @Test
    fun `parsing keeps leading zeros meaningful`() {
        assertEquals(123, DevicePin.parse("000123"))
        assertEquals(0, DevicePin.parse("000000"))
        assertEquals(null, DevicePin.parse("nope"))
    }
}
