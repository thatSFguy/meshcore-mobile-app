package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseParserTest {

    private fun selfInfoFrame(name: String = "Node"): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_SELF_INFO)
        w.writeByte(Codes.ADV_TYPE_CHAT)      // adv type
        w.writeByte(22)                        // tx power
        w.writeByte(30)                        // max tx power
        w.writeBytes(ByteArray(32) { 7 })      // pubkey
        w.writeInt32LE(37_774_900)             // lat
        w.writeInt32LE(-122_419_400)           // lon
        w.writeByte(1)                         // multi acks
        w.writeByte(1)                         // advert loc policy
        w.writeByte(0)                         // telemetry modes
        w.writeByte(1)                         // manual add (bit0 set = manual)
        w.writeUInt32LE(910_525_000L)          // freq
        w.writeUInt32LE(250_000L)              // bw
        w.writeByte(10)                        // sf
        w.writeByte(5)                         // cr
        w.writeString(name)
        w.writeByte(0)
        return w.toBytes()
    }

    @Test
    fun parsesSelfInfo() {
        val e = ResponseParser.parse(selfInfoFrame())
        val info = assertIs<DeviceEvent.SelfInfoReceived>(e).info
        assertEquals(22, info.txPowerDbm)
        assertEquals(30, info.maxTxPowerDbm)
        assertEquals(37.7749, info.latitude, 1e-9)
        assertEquals(-122.4194, info.longitude, 1e-9)
        assertEquals(910_525_000L, info.freqHz)
        assertEquals(250_000L, info.bwHz)
        assertEquals(10, info.sf)
        assertEquals(5, info.cr)
        assertEquals("Node", info.name)
        assertTrue(info.manualAddContacts)
    }

    private fun contactFrame(
        code: Int = Codes.RESP_CODE_CONTACT,
        pubKey: ByteArray = ByteArray(32) { (it + 1).toByte() },
        name: String = "alice",
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(code)
        w.writeBytes(pubKey)
        w.writeByte(Codes.ADV_TYPE_CHAT)
        w.writeByte(Codes.CONTACT_FLAG_FAVORITE)
        w.writeByte(2)                       // path_len
        w.writeBytesPadded(byteArrayOf(0x11, 0x22), 64)
        w.writeFixedCString(name, 32)
        w.writeUInt32LE(1000L)
        w.writeInt32LE(1_000_000)            // lat = 1.0
        w.writeInt32LE(2_000_000)            // lon = 2.0
        w.writeUInt32LE(2000L)
        return w.toBytes()
    }

    @Test
    fun parsesContactRecord() {
        val e = ResponseParser.parse(contactFrame())
        val c = assertIs<DeviceEvent.ContactReceived>(e)
        assertEquals(false, c.fromPush)
        assertEquals("alice", c.contact.name)
        assertEquals(2, c.contact.pathLen)
        assertEquals(1.0, c.contact.latitude!!, 1e-9)
        assertEquals(1000L, c.contact.timestamp)
        assertEquals(2000L, c.contact.lastModified)
        assertTrue(c.contact.isChat)
    }

    @Test
    fun newAdvertPushSharesContactLayout() {
        val e = ResponseParser.parse(contactFrame(code = Codes.PUSH_CODE_NEW_ADVERT))
        val c = assertIs<DeviceEvent.ContactReceived>(e)
        assertTrue(c.fromPush)
        assertTrue(c.isPush)
    }

    @Test
    fun rejectsZeroKeyContact() {
        val e = ResponseParser.parse(contactFrame(pubKey = ByteArray(32)))
        assertIs<DeviceEvent.Unknown>(e)
    }

    @Test
    fun parsesSentAndConfirmed() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_SENT)
        w.writeByte(1)
        w.writeUInt32LE(0xDEADBEEFL)
        w.writeUInt32LE(4500L)
        val sent = assertIs<DeviceEvent.Sent>(ResponseParser.parse(w.toBytes()))
        assertTrue(sent.isFlood)
        assertEquals(0xDEADBEEFL, sent.ackHash)
        assertEquals(4500L, sent.timeoutMs)

        val w2 = BufferWriter()
        w2.writeByte(Codes.PUSH_CODE_SEND_CONFIRMED)
        w2.writeUInt32LE(0xDEADBEEFL)
        w2.writeUInt32LE(321L)
        val conf = assertIs<DeviceEvent.SendConfirmed>(ResponseParser.parse(w2.toBytes()))
        assertEquals(0xDEADBEEFL, conf.ackHash)
        assertEquals(321L, conf.tripMs)
    }

    @Test
    fun parsesContactMessageV3() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CONTACT_MSG_RECV_V3)
        w.writeByte(40)     // snr*4 = 10 dB
        w.writeByte(0)      // reserved
        w.writeByte(0)      // reserved
        w.writeBytes(ByteArray(6) { 9 })
        w.writeByte(3)      // path len
        w.writeByte(Codes.TXT_TYPE_PLAIN)
        w.writeUInt32LE(777L)
        w.writeString("hello")
        w.writeByte(0)
        val m = assertIs<DeviceEvent.ContactMessage>(ResponseParser.parse(w.toBytes()))
        assertEquals("hello", m.text)
        assertEquals(777L, m.timestamp)
        assertEquals(10.0, m.snr!!, 1e-9)
        assertContentEquals(ByteArray(6) { 9 }, m.senderPrefix)
    }

    @Test
    fun signedContactMessageSkipsSignature() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CONTACT_MSG_RECV)
        w.writeBytes(ByteArray(6))
        w.writeByte(0)
        w.writeByte(Codes.TXT_TYPE_SIGNED)
        w.writeUInt32LE(1L)
        w.writeBytes(byteArrayOf(1, 2, 3, 4)) // 4-byte signature
        w.writeString("signed")
        w.writeByte(0)
        val m = assertIs<DeviceEvent.ContactMessage>(ResponseParser.parse(w.toBytes()))
        assertEquals("signed", m.text)
    }

    @Test
    fun parsesChannelMessageLegacyAndV3() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CHANNEL_MSG_RECV)
        w.writeByte(1)               // channel idx
        w.writeByte(0xFF)            // path len (-1 = flood)
        w.writeByte(Codes.TXT_TYPE_PLAIN)
        w.writeUInt32LE(42L)
        w.writeString("bob: hi there")
        w.writeByte(0)
        val m = assertIs<DeviceEvent.ChannelMessage>(ResponseParser.parse(w.toBytes()))
        assertEquals(1, m.channelIndex)
        assertEquals("bob", m.senderName)
        assertEquals("hi there", m.text)

        val v3 = BufferWriter()
        v3.writeByte(Codes.RESP_CODE_CHANNEL_MSG_RECV_V3)
        v3.writeByte(20)             // snr
        v3.writeByte(0x01)           // flags: has path
        v3.writeByte(0)              // reserved
        v3.writeByte(4)              // channel idx
        v3.writeByte(0x42)           // path byte: width mode 1 (2 bytes/hop), 2 hops
        v3.writeBytes(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        v3.writeByte(Codes.TXT_TYPE_PLAIN)
        v3.writeUInt32LE(43L)
        v3.writeString("eve:no-space")
        v3.writeByte(0)
        val m3 = assertIs<DeviceEvent.ChannelMessage>(ResponseParser.parse(v3.toBytes()))
        assertEquals(4, m3.channelIndex)
        assertEquals(2, m3.pathHashWidth)
        assertEquals(2, m3.pathLen)
        assertEquals(4, m3.pathBytes.size)
        assertEquals("eve", m3.senderName)
        assertEquals("no-space", m3.text)
    }

    @Test
    fun channelInfoParses() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CHANNEL_INFO)
        w.writeByte(3)
        w.writeFixedCString("General", 32)
        w.writeBytes(ByteArray(16) { 5 })
        val e = assertIs<DeviceEvent.ChannelInfoReceived>(ResponseParser.parse(w.toBytes()))
        assertEquals(3, e.channel.index)
        assertEquals("General", e.channel.name)
        assertEquals(false, e.channel.isEmpty)
    }

    @Test
    fun batteryParsesWithAndWithoutStorage() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_BATT_AND_STORAGE)
        w.writeUInt16LE(3987)
        val short = assertIs<DeviceEvent.BatteryAndStorageReceived>(ResponseParser.parse(w.toBytes()))
        assertEquals(3987, short.info.batteryMillivolts)
        assertNull(short.info.storageUsedKb)

        val w2 = BufferWriter()
        w2.writeByte(Codes.RESP_CODE_BATT_AND_STORAGE)
        w2.writeUInt16LE(4100)
        w2.writeUInt32LE(100L)
        w2.writeUInt32LE(2000L)
        val full = assertIs<DeviceEvent.BatteryAndStorageReceived>(ResponseParser.parse(w2.toBytes()))
        assertEquals(100L, full.info.storageUsedKb)
        assertEquals(2000L, full.info.storageTotalKb)
    }

    @Test
    fun loginSuccessParses() {
        val w = BufferWriter()
        w.writeByte(Codes.PUSH_CODE_LOGIN_SUCCESS)
        w.writeByte(1)
        w.writeBytes(ByteArray(6) { 3 })
        w.writeUInt32LE(99999L)
        val e = assertIs<DeviceEvent.LoginSuccess>(ResponseParser.parse(w.toBytes()))
        assertEquals(1, e.permissions)
        assertEquals(99999L, e.serverTimestamp)
    }

    @Test
    fun logRxDataCarriesSnrRssi() {
        val w = BufferWriter()
        w.writeByte(Codes.PUSH_CODE_LOG_RX_DATA)
        w.writeByte(0x28)          // snr*4 = 10
        w.writeByte(0xB0)          // rssi = -80 (i8)
        w.writeBytes(byteArrayOf(1, 2, 3))
        val e = assertIs<DeviceEvent.LogRxData>(ResponseParser.parse(w.toBytes()))
        assertEquals(10.0, e.snr, 1e-9)
        assertEquals(-80, e.rssi)
        assertContentEquals(byteArrayOf(1, 2, 3), e.packet)
    }

    @Test
    fun customVarsParse() {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CUSTOM_VARS)
        w.writeString("gps:1, pin:1234")
        w.writeByte(0)
        val e = assertIs<DeviceEvent.CustomVars>(ResponseParser.parse(w.toBytes()))
        assertEquals(mapOf("gps" to "1", "pin" to "1234"), e.vars)
    }

    @Test
    fun errCarriesCode() {
        assertEquals(7, assertIs<DeviceEvent.Err>(ResponseParser.parse(byteArrayOf(1, 7))).errorCode)
        assertNull(assertIs<DeviceEvent.Err>(ResponseParser.parse(byteArrayOf(1))).errorCode)
    }

    @Test
    fun truncatedFramesNeverThrow() {
        // Every known code truncated to every possible short length must
        // degrade gracefully — never crash the RX path.
        val codes = intArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 17, 18, 21, 24, 25,
            0x80, 0x81, 0x82, 0x83, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8E,
        )
        for (code in codes) {
            for (len in 1..8) {
                val frame = ByteArray(len)
                frame[0] = code.toByte()
                val result = ResponseParser.parse(frame) // must not throw
                assertNotNull(result)
            }
        }
        assertNull(ResponseParser.parse(ByteArray(0)))
    }

    @Test
    fun splitSenderTextHeuristics() {
        assertEquals("bob" to "hi", ResponseParser.splitSenderText("bob: hi"))
        assertEquals("bob" to "hi", ResponseParser.splitSenderText("bob:hi"))
        assertEquals("Unknown" to "no colon here", ResponseParser.splitSenderText("no colon here"))
        // Sender containing brackets is rejected (spoof heuristic).
        assertEquals("Unknown" to "[x]: hi", ResponseParser.splitSenderText("[x]: hi"))
    }
}
