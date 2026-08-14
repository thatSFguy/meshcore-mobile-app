package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.model.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The build identity carried in `RESP_CODE_DEVICE_INFO`: build date,
 * board name and firmware version.
 *
 * These three fields sit between the BLE pin and the two trailing flag
 * bytes, and this parser skipped straight over them for a year — which
 * is why the app could not say what firmware the radio was running.
 * Firmware updates need all three: the board picks the release asset,
 * the version says whether there is anything to install.
 *
 * The layout is pinned against the firmware's WRITER, not against our
 * own reader (LESSONS §8) — `companion_radio/MyMesh.cpp`, the
 * `RESP_CODE_DEVICE_INFO` block:
 *
 * ```
 * memcpy(&out_frame[i], &_prefs.ble_pin, 4);        i += 4;
 * memset(&out_frame[i], 0, 12);
 * strcpy((char *)&out_frame[i], FIRMWARE_BUILD_DATE);           i += 12;
 * StrHelper::strzcpy(&out_frame[i], board.getManufacturerName(), 40); i += 40;
 * StrHelper::strzcpy(&out_frame[i], FIRMWARE_VERSION, 20);      i += 20;
 * out_frame[i++] = _prefs.isRepeatEn() ? 1 : 0;   // v9+
 * out_frame[i++] = _prefs.path_hash_mode;         // v10+
 * ```
 *
 * so byte offsets are 8, 20, 60 and the whole frame is 82 bytes.
 */
class DeviceIdentityTest {

    /** Builds the frame exactly as the firmware writes it. */
    private fun deviceInfoFrame(
        fwVerCode: Int = 10,
        maxContactsHalved: Int = 50,
        maxChannels: Int = 8,
        blePin: Long = 123456,
        buildDate: String = "13 Aug 2026",
        boardName: String = "Heltec T114",
        firmwareVersion: String = "v1.17.0",
        repeatEnabled: Boolean = false,
        pathHashMode: Int = 1,
        truncateTo: Int = -1,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_DEVICE_INFO)
        w.writeByte(fwVerCode)
        w.writeByte(maxContactsHalved)
        w.writeByte(maxChannels)
        w.writeUInt32LE(blePin)
        w.writeBytesPadded(buildDate.encodeToByteArray(), 12)
        w.writeBytesPadded(boardName.encodeToByteArray(), 40)
        w.writeBytesPadded(firmwareVersion.encodeToByteArray(), 20)
        w.writeByte(if (repeatEnabled) 1 else 0)
        w.writeByte(pathHashMode)
        val frame = w.toBytes()
        return if (truncateTo >= 0) frame.copyOfRange(0, truncateTo) else frame
    }

    private fun parse(frame: ByteArray): DeviceInfo {
        val event = ResponseParser.parse(frame)
        assertTrue(event is DeviceEvent.DeviceInfoReceived, "parsed as $event")
        return event.info
    }

    @Test
    fun `the frame is the eighty-two bytes the firmware writes`() {
        assertEquals(82, deviceInfoFrame().size)
    }

    @Test
    fun `build date board and version are read from their fixed offsets`() {
        val info = parse(deviceInfoFrame())
        assertEquals("13 Aug 2026", info.firmwareBuildDate)
        assertEquals("Heltec T114", info.boardName)
        assertEquals("v1.17.0", info.firmwareVersion)
        // The fields around them must still land where they did before.
        assertEquals(123456L, info.blePin)
        assertEquals(100, info.maxContacts)
        assertEquals(8, info.maxChannels)
        assertEquals(false, info.clientRepeat)
        assertEquals(2, info.pathHashByteWidth)
    }

    @Test
    fun `a name that exactly fills its field does not bleed into the next`() {
        // strzcpy fills all 40 bytes with no terminator when the name is
        // 40 characters. Reading to the next NUL instead of to the field
        // edge would splice the board name onto the version string.
        val fortyChars = "0123456789012345678901234567890123456789"
        assertEquals(40, fortyChars.length)
        val info = parse(deviceInfoFrame(boardName = fortyChars))
        assertEquals(fortyChars, info.boardName)
        assertEquals("v1.17.0", info.firmwareVersion)
    }

    @Test
    fun `firmware that stops after the pin reports no identity`() {
        // Older companions end the frame at the BLE pin. Everything past
        // it must be absent, not invented, and must not throw.
        val info = parse(deviceInfoFrame(truncateTo = 8))
        assertEquals(123456L, info.blePin)
        assertNull(info.firmwareBuildDate)
        assertNull(info.boardName)
        assertNull(info.firmwareVersion)
        assertEquals(1, info.pathHashByteWidth) // the documented fallback
    }

    @Test
    fun `a frame cut mid-field keeps what is whole and drops the rest`() {
        // 40 bytes lands inside the board-name field: the build date is
        // complete, the board name is not.
        val info = parse(deviceInfoFrame(truncateTo = 40))
        assertEquals("13 Aug 2026", info.firmwareBuildDate)
        assertNull(info.boardName)
        assertNull(info.firmwareVersion)
    }

    @Test
    fun `empty fields are absent rather than blank strings`() {
        // A board with no manufacturer name writes 40 zeros. "" would
        // render as a blank line in the UI and would match nothing in
        // the release-asset lookup; null says "not reported".
        val info = parse(deviceInfoFrame(buildDate = "", boardName = "", firmwareVersion = ""))
        assertNull(info.firmwareBuildDate)
        assertNull(info.boardName)
        assertNull(info.firmwareVersion)
    }

    @Test
    fun `every truncation of the frame parses without throwing`() {
        // The RX path must survive any prefix of this frame — a short
        // read here would take down the connection, not just the field.
        val full = deviceInfoFrame()
        for (length in 0..full.size) {
            val event = ResponseParser.parse(full.copyOfRange(0, length))
            assertTrue(
                event == null || event is DeviceEvent.DeviceInfoReceived ||
                    event is DeviceEvent.Unknown,
                "length $length parsed as $event",
            )
        }
    }

    @Test
    fun `non-UTF8 bytes in a name do not crash the parse`() {
        // Nothing guarantees the board name is valid UTF-8; a garbled
        // one is a display problem, never an exception.
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_DEVICE_INFO)
        w.writeByte(10)
        w.writeByte(50)
        w.writeByte(8)
        w.writeUInt32LE(0)
        w.writeBytesPadded(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte()), 12)
        w.writeBytesPadded(ByteArray(40) { 0xC3.toByte() }, 40)
        w.writeBytesPadded(byteArrayOf(0xE2.toByte()), 20)
        w.writeByte(0)
        w.writeByte(0)
        val info = parse(w.toBytes())
        // Decoded lossily; the only contract is that we got here at all.
        assertTrue(info.boardName != null)
    }
}
