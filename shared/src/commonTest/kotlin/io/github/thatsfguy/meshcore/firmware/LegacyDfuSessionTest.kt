package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The legacy DFU transfer.
 *
 * Everything asserted here is pinned against the **peer's** code — the
 * Adafruit bootloader's `dfu_transport_ble.c` and `ble_dfu.c` — and not
 * against a parser of our own, because the two halves agreeing with
 * each other is the failure this project has now shipped twice
 * (CLAUDE.md, "a parser tested against captured frames proves nothing
 * about the builder").
 *
 * A wrong byte here does not produce a wrong pixel. It bricks a radio.
 */
class LegacyDfuSessionTest {

    private val initPacket = ByteArray(14) { (it + 1).toByte() }

    private fun image(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun session(
        imageSize: Int = 100,
        chunk: Int = 20,
        prn: Int = 10,
        init: ByteArray = initPacket,
    ) = LegacyDfuSession(init, image(imageSize), chunkSize = chunk, prnInterval = prn)

    private fun response(procedure: Int, result: Int = LegacyDfu.RESP_SUCCESS) =
        byteArrayOf(LegacyDfu.OP_RESPONSE.toByte(), procedure.toByte(), result.toByte())

    private fun receipt(bytes: Long) = byteArrayOf(
        LegacyDfu.OP_PKT_RCPT_NOTIF.toByte(),
        (bytes and 0xFF).toByte(),
        ((bytes shr 8) and 0xFF).toByte(),
        ((bytes shr 16) and 0xFF).toByte(),
        ((bytes shr 24) and 0xFF).toByte(),
    )

    private fun controls(actions: List<DfuAction>) =
        actions.filterIsInstance<DfuAction.WriteControl>().map { it.bytes.toList() }

    private fun packets(actions: List<DfuAction>) =
        actions.filterIsInstance<DfuAction.WritePacket>()

    // --- opening the session ---------------------------------------------

    @Test
    fun `the start sequence is start then three words`() {
        // The receipt interval is NOT part of this step.
        //
        // It used to be sent first, and this test pinned that as
        // correct — a builder checked against our own assumption rather
        // than against a reference, which is the mistake CLAUDE.md
        // records twice already. Nordic's own legacy implementation and
        // Meshtastic's both send `PRN_REQ` after the init packet is
        // accepted and immediately before the image step; see
        // `the receipt interval is set immediately before the image`.
        val s = session(imageSize = 0x1234)
        val actions = s.start()
        assertEquals(
            listOf(
                listOf<Byte>(1, 4), // start DFU, application image only
            ),
            controls(actions),
        )
        val packet = packets(actions).single().bytes
        // Exactly three words: SoftDevice, bootloader, application. The
        // peer answers NOT_SUPPORTED to anything that is not 12 bytes
        // (dfu_transport_ble.c: `length != (3 * sizeof(uint32_t))`).
        assertEquals(12, packet.size)
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0x34, 0x12, 0, 0),
            packet,
        )
    }

    @Test
    fun `disabling receipt notifications skips the request entirely`() {
        val s = session(prn = 0)
        assertEquals(listOf(listOf<Byte>(1, 4)), controls(s.start()))
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        val afterInit = s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
        assertEquals(listOf(listOf<Byte>(3)), controls(afterInit))
    }

    @Test
    fun `the receipt interval is set immediately before the image`() {
        // Where Nordic's legacy implementation puts it and where
        // Meshtastic puts it: after the init packet is accepted, with
        // the stream about to begin — NOT ahead of START_DFU, which
        // leaves the request stranded on the far side of a full-bank
        // erase lasting 30-50 seconds.
        val s = session(imageSize = 100, prn = 7)
        assertTrue(
            controls(s.start()).none { it.firstOrNull()?.toInt() == LegacyDfu.OP_PKT_RCPT_NOTIF_REQ },
            "the receipt interval was sent before the peer knew what it was receiving",
        )
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        val afterInit = controls(s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT)))
        assertEquals(listOf<Byte>(8, 7, 0), afterInit.first())
        assertEquals(listOf<Byte>(3), afterInit[1])
    }

    // --- the happy path ---------------------------------------------------

    @Test
    fun `a whole transfer runs start init image validate activate`() {
        // 100 bytes at 20 per chunk is 5 chunks — under one 10-packet
        // batch, so the image goes out in a single burst.
        val s = session(imageSize = 100)
        s.start()

        val afterStart = s.onNotification(response(LegacyDfu.OP_START_DFU))
        assertEquals(DfuStage.SendingInit, s.stage)
        assertEquals(
            listOf(listOf<Byte>(2, 0), listOf<Byte>(2, 1)),
            controls(afterStart),
        )
        assertContentEquals(initPacket, packets(afterStart).single().bytes)

        val afterInit = s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
        assertEquals(DfuStage.SendingImage, s.stage)
        // Receipt interval, THEN the image step — Nordic's order and
        // Meshtastic's, rather than a variation on it.
        assertEquals(
            listOf(listOf<Byte>(8, 10, 0), listOf<Byte>(3)),
            controls(afterInit),
        )
        assertEquals(5, packets(afterInit).size)
        assertEquals(100, s.bytesSent)
        // The chunks are the image, in order, with nothing dropped.
        assertContentEquals(
            image(100),
            packets(afterInit).fold(ByteArray(0)) { acc, p -> acc + p.bytes },
        )

        val afterImage = s.onNotification(response(LegacyDfu.OP_RECEIVE_FW))
        assertEquals(DfuStage.Validating, s.stage)
        assertEquals(listOf(listOf<Byte>(4)), controls(afterImage))

        val afterValidate = s.onNotification(response(LegacyDfu.OP_VALIDATE))
        assertEquals(listOf(listOf<Byte>(5)), controls(afterValidate))
        assertTrue(afterValidate.last() is DfuAction.Complete)
        assertEquals(DfuStage.Done, s.stage)
        assertEquals(1.0, s.imageProgress)
    }

    @Test
    fun `a long image is metered one batch per receipt`() {
        // 10 chunks per batch × 20 bytes = 200 bytes between receipts.
        val s = session(imageSize = 450)
        s.start()
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        val first = s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
        assertEquals(10, packets(first).size)
        assertEquals(200, s.bytesSent)

        val second = s.onNotification(receipt(200))
        assertEquals(10, packets(second).size)
        assertEquals(400, s.bytesSent)

        // The tail is a partial batch: 50 bytes = two chunks of 20 and
        // one of 10. No receipt follows it, so the session must NOT sit
        // waiting for one — the completion response comes instead.
        val third = s.onNotification(receipt(400))
        assertEquals(3, packets(third).size)
        assertEquals(10, packets(third).last().bytes.size)
        assertEquals(450, s.bytesSent)

        val done = s.onNotification(response(LegacyDfu.OP_RECEIVE_FW))
        assertEquals(listOf(listOf<Byte>(4)), controls(done))
    }

    @Test
    fun `an image that ends exactly on a batch boundary still completes`() {
        // 200 bytes = exactly 10 chunks. The peer sends a receipt for
        // the full image AND the completion response; the receipt must
        // not produce a phantom eleventh chunk.
        val s = session(imageSize = 200)
        s.start()
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
        assertEquals(200, s.bytesSent)

        val stray = s.onNotification(receipt(200))
        assertEquals(emptyList(), packets(stray))

        val done = s.onNotification(response(LegacyDfu.OP_RECEIVE_FW))
        assertEquals(listOf(listOf<Byte>(4)), controls(done))
        assertEquals(DfuStage.Validating, s.stage)
    }

    @Test
    fun `an init packet larger than one write is chunked`() {
        val big = ByteArray(50) { it.toByte() }
        val s = session(init = big)
        s.start()
        val actions = s.onNotification(response(LegacyDfu.OP_START_DFU))
        val written = packets(actions)
        assertEquals(listOf(20, 20, 10), written.map { it.bytes.size })
        assertContentEquals(big, written.fold(ByteArray(0)) { acc, p -> acc + p.bytes })
    }

    @Test
    fun `every image size round-trips byte for byte`() {
        // Exhaustive over the awkward sizes: one byte short of a chunk,
        // exactly a chunk, one over, and across batch boundaries. The
        // 63-hops lesson — sweeps find what examples miss.
        for (size in listOf(1, 4, 19, 20, 21, 39, 40, 199, 200, 201, 1023)) {
            val img = ByteArray(size) { (it % 251).toByte() }
            val s = LegacyDfuSession(initPacket, img)
            s.start()
            s.onNotification(response(LegacyDfu.OP_START_DFU))
            var actions = s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
            val sent = mutableListOf<Byte>()
            packets(actions).forEach { sent += it.bytes.toList() }
            while (s.bytesSent < size) {
                actions = s.onNotification(receipt(s.bytesSent.toLong()))
                packets(actions).forEach { sent += it.bytes.toList() }
            }
            assertContentEquals(img, sent.toByteArray(), "image of $size bytes")
            assertEquals(size, s.bytesSent)
        }
    }

    // --- what the peer does when it is unhappy ----------------------------

    @Test
    fun `a rejected procedure stops the session and says which one`() {
        // RESP_INVALID_STATE is deliberately absent: at the start step it
        // is a stuck bootloader session rather than a rejection, and it
        // is recoverable. See the test below.
        for (result in listOf(
            LegacyDfu.RESP_NOT_SUPPORTED,
            LegacyDfu.RESP_DATA_SIZE,
            LegacyDfu.RESP_CRC_ERROR,
            LegacyDfu.RESP_OPER_FAILED,
        )) {
            val s = session()
            s.start()
            val actions = s.onNotification(response(LegacyDfu.OP_START_DFU, result))
            val fail = assertIs<DfuAction.Fail>(actions.single())
            assertEquals(DfuFailure.Rejected(LegacyDfu.OP_START_DFU, result), fail.failure)
            assertEquals(DfuStage.Failed, s.stage)
            assertTrue(fail.failure.message.isNotBlank())
        }
    }

    @Test
    fun `a CRC error part-way through the image is fatal and not a retry`() {
        // The init packet carries the image's SHA-256 and CRC; a CRC
        // error means what reached flash is not what we sent.
        val s = session(imageSize = 450)
        s.start()
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
        val actions = s.onNotification(
            response(LegacyDfu.OP_RECEIVE_FW, LegacyDfu.RESP_CRC_ERROR),
        )
        assertIs<DfuAction.Fail>(actions.single())
        assertEquals(DfuStage.Failed, s.stage)
        // And nothing more is emitted, whatever the peer says next.
        assertEquals(emptyList(), s.onNotification(receipt(200)))
    }

    @Test
    fun `a receipt that disagrees about the byte count stops the transfer`() {
        // Silent data loss is the one failure the bootloader's own hash
        // would catch only at the end, after minutes of transfer — and
        // on a stock bootloader, after the flash has been erased.
        val s = session(imageSize = 450)
        s.start()
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
        val actions = s.onNotification(receipt(180))
        val fail = assertIs<DfuAction.Fail>(actions.single())
        assertEquals(DfuFailure.ByteCountMismatch(180, 200), fail.failure)
    }

    @Test
    fun `a completion response before the image is sent never activates`() {
        val s = session(imageSize = 450)
        s.start()
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        s.onNotification(response(LegacyDfu.OP_RECEIVE_INIT))
        // Only 200 of 450 bytes have gone out.
        val actions = s.onNotification(response(LegacyDfu.OP_RECEIVE_FW))
        assertIs<DfuAction.Fail>(actions.single())
        assertEquals(DfuStage.Failed, s.stage)
    }

    @Test
    fun `invalid state at the start step is a stuck session rather than a bad package`() {
        // The exact bytes off 13 Mile on 2026-08-13: [0x10, 1, 2].
        //
        // dfu_single_bank.c returns NRF_ERROR_INVALID_STATE from
        // dfu_start_pkt_handle for one reason — `DFU_STATE_IDLE !=
        // m_dfu_state` — and only dfu_init(), which runs once when the
        // bootloader boots, ever sets that state. A disconnection does
        // not: dfu_transport_ble.c's BLE_GAP_EVT_DISCONNECTED handler
        // just calls advertising_start(). So this response says the node
        // is still holding an earlier transfer, and says nothing
        // whatever about the file being sent.
        val s = session()
        s.start()
        val actions = s.onNotification(
            response(LegacyDfu.OP_START_DFU, LegacyDfu.RESP_INVALID_STATE),
        )
        val fail = assertIs<DfuAction.Fail>(actions.single())
        assertEquals(DfuFailure.StaleSession, fail.failure)
        assertEquals(DfuStage.Failed, s.stage)
        assertTrue(
            !fail.failure.message.contains("rejected"),
            "still reads as the node refusing the package",
        )
    }

    @Test
    fun `invalid state anywhere else is still a plain rejection`() {
        // Only the start step carries the stuck-session meaning. The
        // same value later is something else, and must not be answered
        // by rebooting the node mid-transfer.
        val s = session()
        s.start()
        s.onNotification(response(LegacyDfu.OP_START_DFU))
        val actions = s.onNotification(
            response(LegacyDfu.OP_RECEIVE_INIT, LegacyDfu.RESP_INVALID_STATE),
        )
        val fail = assertIs<DfuAction.Fail>(actions.single())
        assertEquals(
            DfuFailure.Rejected(LegacyDfu.OP_RECEIVE_INIT, LegacyDfu.RESP_INVALID_STATE),
            fail.failure,
        )
    }

    @Test
    fun `a response for the wrong step is refused rather than followed`() {
        val s = session()
        s.start()
        // Validate cannot succeed before anything was sent; a peer (or
        // a spoofer) claiming otherwise must not reach activate-reset.
        val actions = s.onNotification(response(LegacyDfu.OP_VALIDATE))
        val fail = assertIs<DfuAction.Fail>(actions.single())
        assertEquals(DfuFailure.OutOfOrder(LegacyDfu.OP_VALIDATE, DfuStage.Starting), fail.failure)
    }

    @Test
    fun `truncated and unknown notifications are rejected not guessed`() {
        val hostile = listOf(
            byteArrayOf(),
            byteArrayOf(LegacyDfu.OP_RESPONSE.toByte()),
            byteArrayOf(LegacyDfu.OP_RESPONSE.toByte(), 1),
            byteArrayOf(LegacyDfu.OP_PKT_RCPT_NOTIF.toByte()),
            byteArrayOf(LegacyDfu.OP_PKT_RCPT_NOTIF.toByte(), 1, 2, 3),
            byteArrayOf(0x42),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        for (bytes in hostile) {
            val s = session()
            s.start()
            val actions = s.onNotification(bytes)
            assertIs<DfuAction.Fail>(actions.single(), "accepted ${bytes.toList()}")
            assertEquals(DfuStage.Failed, s.stage)
        }
    }

    @Test
    fun `a receipt-notification acknowledgement is harmless`() {
        // Some builds answer the interval request itself. It changes
        // nothing and must not be read as a start acknowledgement.
        val s = session()
        s.start()
        assertEquals(emptyList(), s.onNotification(response(LegacyDfu.OP_PKT_RCPT_NOTIF_REQ)))
        assertEquals(DfuStage.Starting, s.stage)
    }

    // --- guards -----------------------------------------------------------

    @Test
    fun `abort asks the node to boot what it already has`() {
        val s = session()
        s.start()
        assertEquals(listOf(listOf<Byte>(6)), controls(s.abort()))
        assertEquals(DfuStage.Failed, s.stage)
    }

    @Test
    fun `nonsense parameters are refused at construction`() {
        assertFailsWith<IllegalArgumentException> { LegacyDfuSession(ByteArray(0), image(10)) }
        assertFailsWith<IllegalArgumentException> { LegacyDfuSession(initPacket, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            LegacyDfuSession(initPacket, image(10), chunkSize = 19)
        }
        assertFailsWith<IllegalArgumentException> {
            LegacyDfuSession(initPacket, image(10), chunkSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LegacyDfuSession(initPacket, image(10), prnInterval = -1)
        }
    }

    @Test
    fun `the app-mode jump is one byte and the reset opcode is six`() {
        // BLEDfu.cpp accepts START_DFU = 1 and nothing else; opcode 6 is
        // what gets a node out of the bootloader without flashing.
        assertContentEquals(byteArrayOf(1), LegacyDfu.ENTER_BOOTLOADER)
        assertContentEquals(byteArrayOf(6), LegacyDfu.SYSTEM_RESET)
    }
}
