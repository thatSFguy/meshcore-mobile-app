package io.github.thatsfguy.meshcore.firmware

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole update, driven against a fake bootloader that answers the
 * way the Adafruit one does.
 *
 * This is the positive control the suite needs (CLAUDE.md: "a suite of
 * asserts-null needs a positive control"). Most of what can go wrong
 * here is the update declining to proceed, and every one of those tests
 * would pass if the feature did nothing at all — so the test that
 * carries this file is the one asserting that a real image arrives at
 * the peer, byte for byte, and ends in a reboot.
 */
class FirmwareUpdaterTest {

    // --- a fake that behaves like the bootloader --------------------------

    private class FakeBootloader(
        private val imageSize: Int,
        private val receiptInterval: Int = LegacyDfu.DEFAULT_PRN_INTERVAL,
        /** Answer this procedure with this failure instead of success. */
        private val rejectProcedure: Int? = null,
        private val rejectWith: Int = LegacyDfu.RESP_CRC_ERROR,
        /**
         * Throw on the jump write, the way a real node does: acting on
         * it kills the link before the ATT response can be sent, so the
         * write callback reports status 133.
         */
        private val jumpKillsTheLink: Boolean = false,
        /** Refuse the jump outright, as a stale bond does. */
        private val jumpRefused: Boolean = false,
        /** What the revision characteristic reports, if anything. */
        private val revision: Int? = null,
        /**
         * Refuse the first start the way a bootloader left mid-transfer
         * by an earlier attempt does, and accept it after a reset.
         */
        private val latchedFromAnEarlierSession: Boolean = false,
        /**
         * Refuse the image step with `operation failed` while the client
         * asks for more than this many packets between receipts.
         *
         * This is the real bootloader's behaviour, not an invention:
         * `hci_mem_pool_rx_produce` returns `NRF_ERROR_NO_MEM` when the
         * receive pool fills, and `nrf_err_code_translate` reports it as
         * the catch-all [LegacyDfu.RESP_OPER_FAILED]. Observed on a live
         * ProMicro at the documented interval of 10.
         */
        private val chokesAbovePrn: Int? = null,
        /**
         * Throw on the activate write, the way a real node does: it
         * resets inside the handler, so the ATT response never comes.
         */
        private val activateKillsTheLink: Boolean = false,
        /**
         * Stop answering once this many image bytes have arrived, while
         * leaving the link nominally up.
         *
         * A live ProMicro did exactly this at 14,800 of 372,044 bytes:
         * the control point simply stopped notifying, with no disconnect
         * to notice and nothing to time out against.
         */
        private val goesQuietAfter: Int? = null,
        /**
         * Never complete a packet write once this many image bytes have
         * arrived — the write suspends and stays suspended.
         *
         * Distinct from [goesQuietAfter], and the distinction is the
         * point: that one models a peer that stops NOTIFYING, which a
         * watchdog on the notification stream can catch. This models the
         * local Bluetooth stack never delivering
         * `onCharacteristicWrite`, which happens on the other side of
         * that watchdog entirely and hangs the sender rather than the
         * wait.
         */
        private val swallowsWritesAfter: Int? = null,
        /**
         * Never answer the start step. The real bootloader erases the
         * bank inside the start handler and replies only afterwards, so
         * a start that goes unanswered may have erased everything — it
         * is not evidence that nothing happened.
         */
        private val silentAtStart: Boolean = false,
        /**
         * Throw from a packet write once this many image bytes have
         * arrived, as `AndroidDfuGattClient` does when the stack keeps
         * refusing — which is what switching Bluetooth off mid-image
         * produced on the test RAK.
         */
        private val refusesWritesAfter: Int? = null,
    ) : DfuGattClient {

        override suspend fun readDfuRevision(): Int? = revision

        /**
         * A channel, not a replaying `SharedFlow`.
         *
         * This fake answers the start step inside the write that
         * provokes it, before the updater has begun collecting — exactly
         * as the real bootloader does. `replay = 512` made that
         * impossible to get wrong here while the Android client, which
         * has no replay, dropped the response and reported the node as
         * silent. A fake that cannot reproduce the defect is the same
         * assumption twice.
         */
        private val _notifications = Channel<ByteArray>(Channel.UNLIMITED)
        override val notifications: Flow<ByteArray> = _notifications.receiveAsFlow()

        val imageReceived = mutableListOf<Byte>()
        val initReceived = mutableListOf<Byte>()
        var startPacket: ByteArray? = null
        var subscribed = false
        var connected = false
        var closed = false
        var activated = false
        var jumped = false

        /** Every control-point write, so a reset can be asserted. */
        val controlWrites = mutableListOf<List<Byte>>()

        /** How many times the peer was told to reset itself. */
        var resets = 0

        /** Whether the link was asked for a short connection interval. */
        var highThroughputRequested = false

        /** How many times it was asked for — it has to be re-asserted. */
        var highThroughputRequests = 0

        /** Every receipt interval the client asked for, in order. */
        val prnRequests = mutableListOf<Int>()

        private enum class Mode { None, Start, Init, Image }

        private var mode = Mode.None
        private var packetsSinceReceipt = 0
        private var startsSeen = 0

        override suspend fun connect() {
            connected = true
        }

        override suspend fun subscribeToControlPoint() {
            subscribed = true
        }

        override suspend fun requestHighThroughput(): Boolean {
            highThroughputRequested = true
            highThroughputRequests++
            return true
        }

        override suspend fun close() {
            closed = true
        }

        private fun respond(procedure: Int, override: Int? = null) {
            val result = override
                ?: if (procedure == rejectProcedure) rejectWith else LegacyDfu.RESP_SUCCESS
            _notifications.trySend(
                byteArrayOf(LegacyDfu.OP_RESPONSE.toByte(), procedure.toByte(), result.toByte()),
            )
        }

        override suspend fun writeControl(bytes: ByteArray) {
            check(subscribed) { "the peer rejects control writes before subscription" }
            controlWrites += bytes.toList()
            // The app-mode jump and the bootloader's "start DFU" share
            // an opcode byte: BLEDfu.cpp looks only at data[0] == 1, and
            // the bootloader reads a second byte for the image type.
            // Length is the only thing that tells them apart.
            if (bytes.size == 1 && bytes[0].toInt() == LegacyDfu.OP_START_DFU) {
                jumped = true
                if (jumpRefused) throw StaleBondException("write rejected (0xfd)")
                if (jumpKillsTheLink) {
                    throw IllegalStateException("The write failed (status 133).")
                }
                return
            }
            when (bytes[0].toInt()) {
                LegacyDfu.OP_START_DFU -> mode = Mode.Start
                LegacyDfu.OP_RECEIVE_INIT ->
                    if (bytes[1].toInt() == LegacyDfu.INIT_RECEIVE) {
                        mode = Mode.Init
                    } else {
                        mode = Mode.None
                        respond(LegacyDfu.OP_RECEIVE_INIT)
                    }

                LegacyDfu.OP_RECEIVE_FW -> {
                    mode = Mode.Image
                    packetsSinceReceipt = 0
                }

                LegacyDfu.OP_VALIDATE -> respond(LegacyDfu.OP_VALIDATE)
                LegacyDfu.OP_ACTIVATE_N_RESET -> {
                    activated = true
                    if (activateKillsTheLink) {
                        throw IllegalStateException("The write failed (status 133).")
                    }
                }
                LegacyDfu.OP_PKT_RCPT_NOTIF_REQ -> {
                    packetsSinceReceipt = 0
                    prnRequests += (bytes[1].toInt() and 0xFF) or
                        ((bytes[2].toInt() and 0xFF) shl 8)
                }
                // dfu_transport_ble.c closes the transport and calls
                // dfu_reset() inside the handler, so the write is never
                // acknowledged and everything after it is gone.
                LegacyDfu.OP_SYS_RESET -> {
                    resets++
                    mode = Mode.None
                    throw IllegalStateException("The write failed (status 133).")
                }
            }
        }

        override suspend fun writePacket(bytes: ByteArray) {
            when (mode) {
                Mode.Start -> {
                    startPacket = bytes
                    mode = Mode.None
                    // dfu_single_bank.c: the start handler's last line is
                    // `if (DFU_STATE_IDLE != m_dfu_state) return
                    // NRF_ERROR_INVALID_STATE;`, and only dfu_init() at
                    // boot ever puts that state back.
                    val latched = latchedFromAnEarlierSession && startsSeen == 0
                    startsSeen++
                    if (silentAtStart) return
                    respond(
                        LegacyDfu.OP_START_DFU,
                        override = if (latched) LegacyDfu.RESP_INVALID_STATE else null,
                    )
                }

                Mode.Init -> initReceived += bytes.toList()
                Mode.Image -> {
                    val asked = prnRequests.lastOrNull() ?: LegacyDfu.DEFAULT_PRN_INTERVAL
                    if (chokesAbovePrn != null && asked > chokesAbovePrn) {
                        mode = Mode.None
                        respond(LegacyDfu.OP_RECEIVE_FW, LegacyDfu.RESP_OPER_FAILED)
                        return
                    }
                    if (refusesWritesAfter != null && imageReceived.size >= refusesWritesAfter) {
                        throw IllegalStateException("The Bluetooth stack refused 244 bytes 5 times.")
                    }
                    if (swallowsWritesAfter != null && imageReceived.size >= swallowsWritesAfter) {
                        awaitCancellation()
                    }
                    imageReceived += bytes.toList()
                    if (goesQuietAfter != null && imageReceived.size >= goesQuietAfter) return
                    packetsSinceReceipt++
                    if (receiptInterval > 0 && packetsSinceReceipt == receiptInterval) {
                        packetsSinceReceipt = 0
                        val n = imageReceived.size.toLong()
                        _notifications.trySend(
                            byteArrayOf(
                                LegacyDfu.OP_PKT_RCPT_NOTIF.toByte(),
                                (n and 0xFF).toByte(),
                                ((n shr 8) and 0xFF).toByte(),
                                ((n shr 16) and 0xFF).toByte(),
                                ((n shr 24) and 0xFF).toByte(),
                            ),
                        )
                    }
                    if (imageReceived.size >= imageSize) {
                        mode = Mode.None
                        respond(LegacyDfu.OP_RECEIVE_FW)
                    }
                }

                Mode.None -> {}
            }
        }
    }

    /**
     * Answers each scan in turn. A node that has run `start ota` is
     * found twice — once advertising from its own firmware, and again
     * as a bootloader after the jump.
     */
    private class FakeScanner(private vararg val peers: DfuPeer?) : DfuScanner {
        val expectations = mutableListOf<BootloaderExpectation>()

        /** The scan window asked for each time, in order. */
        val timeouts = mutableListOf<Long>()
        var expectation: BootloaderExpectation? = null
        private var calls = 0

        override suspend fun findBootloader(
            expectation: BootloaderExpectation,
            timeoutMs: Long,
        ): DfuPeer? {
            expectations += expectation
            timeouts += timeoutMs
            this.expectation = expectation
            val answer = peers.getOrNull(calls) ?: peers.lastOrNull()
            calls++
            return answer
        }
    }

    private class FakeTransport(private val error: Exception? = null) : BootloaderCapableTransport {
        var asked = 0

        override fun offersFirmwareUpdates(): Boolean = error == null

        override suspend fun requestBootloaderReboot() {
            asked++
            error?.let { throw it }
        }
    }

    private fun packageOf(imageSize: Int) = DfuPackage(
        binFileName = "firmware.bin",
        datFileName = "firmware.dat",
        initPacket = byteArrayOf(0x52, 0, -1, -1, -1, -1, -1, -1, 1, 0, -74, 0, -83, 0x6B),
        image = ByteArray(imageSize) { (it % 251).toByte() },
        deviceType = 0x52,
        deviceRevision = 0xFFFF,
        applicationVersion = 0xFFFFFFFFL,
        softDeviceRequirements = listOf(182),
    )

    private val peer = DfuPeer("AA:BB:CC:DD:EE:11", "RAK4631_OTA")

    // --- the positive control ---------------------------------------------

    @Test
    fun `a whole update delivers the image and ends in a reboot`() = runTest {
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(pkg.imageSize)
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
        assertContentEquals(pkg.initPacket, bootloader.initReceived.toByteArray())
        assertEquals(12, bootloader.startPacket!!.size)
        assertTrue(bootloader.activated, "the node was never told to activate and reset")
        assertTrue(bootloader.closed, "the connection was left open")
    }

    @Test
    fun `progress runs in order from nothing sent to everything sent`() = runTest {
        val pkg = packageOf(4096)
        val updater = FirmwareUpdater(FakeScanner(peer)) { FakeBootloader(pkg.imageSize) }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()
        val transfers = progress.filterIsInstance<DfuProgress.Transferring>()

        assertEquals(0, transfers.first().bytesSent)
        assertEquals(pkg.imageSize, transfers.last().bytesSent)
        assertEquals(1.0, transfers.last().fraction)
        assertEquals(transfers.map { it.bytesSent }.sorted(), transfers.map { it.bytesSent })
        assertTrue(progress.any { it is DfuProgress.Verifying }, "no verify step was reported")
    }

    @Test
    fun `the connected radio is rebooted first and looked for at its bootloader address`() =
        runTest {
            val pkg = packageOf(512)
            val transport = FakeTransport()
            val scanner = FakeScanner(peer)
            val updater = FirmwareUpdater(scanner) { FakeBootloader(pkg.imageSize) }

            val progress = updater.update(
                pkg,
                DfuTarget.ConnectedRadio(transport, "AA:BB:CC:DD:EE:10", "RAK 4631"),
            ).toList()

            assertEquals(1, transport.asked)
            assertTrue(progress.any { it is DfuProgress.EnteringBootloader })
            assertEquals("AA:BB:CC:DD:EE:10", scanner.expectation?.companionAddress)
            assertEquals("RAK 4631", scanner.expectation?.nameHint)
            assertEquals(DfuProgress.Finished, progress.last())
        }

    @Test
    fun `a node already waiting in the bootloader is never asked to reboot`() = runTest {
        // The repeater path: `start ota` went over the mesh, the node
        // rebooted minutes ago, and there is no app-mode connection left
        // to write to.
        val pkg = packageOf(512)
        val scanner = FakeScanner(peer)
        val updater = FirmwareUpdater(scanner) { FakeBootloader(pkg.imageSize) }

        val progress = updater.update(
            pkg,
            DfuTarget.WaitingInBootloader("AA:BB:CC:DD:EE:10", "RAK 4631"),
        ).toList()

        assertTrue(progress.none { it is DfuProgress.EnteringBootloader })
        assertEquals(DfuProgress.Finished, progress.last())
    }

    // --- the ways it fails ------------------------------------------------

    @Test
    fun `a node that never appears is described as waiting rather than broken`() = runTest {
        val pkg = packageOf(512)
        val updater = FirmwareUpdater(FakeScanner(null)) { FakeBootloader(pkg.imageSize) }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertEquals(Recovery.NODE_NOT_FOUND, failed.recovery)
    }

    @Test
    fun `a stale bond is named as such rather than reported as a dead radio`() = runTest {
        // PR #2323: a node paired before it carried the DFU service
        // refuses the jump, and no amount of retrying fixes it — the
        // pairing has to be forgotten first.
        val pkg = packageOf(512)
        val transport = FakeTransport(StaleBondException("write rejected (0xfd)"))
        val updater = FirmwareUpdater(FakeScanner(peer)) { FakeBootloader(pkg.imageSize) }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.ConnectedRadio(transport, "AA:BB:CC:DD:EE:10", null))
                .toList().last(),
        )
        assertEquals(Recovery.STALE_BOND, failed.recovery)
    }

    @Test
    fun `a radio with no DFU service is told what firmware it needs`() = runTest {
        val pkg = packageOf(512)
        val transport = FakeTransport(NoDfuServiceException("DFU service not found"))
        val updater = FirmwareUpdater(FakeScanner(peer)) { FakeBootloader(pkg.imageSize) }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.ConnectedRadio(transport, null, null)).toList().last(),
        )
        assertEquals(Recovery.NO_DFU_SERVICE, failed.recovery)
        assertTrue(failed.recovery.contains("v1.15"))
    }

    @Test
    fun `a connection that will not open is not reported as a flash failure`() = runTest {
        val pkg = packageOf(512)
        val updater = FirmwareUpdater(FakeScanner(peer)) {
            throw IllegalStateException("GATT 133")
        }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertEquals(Recovery.CONNECT_FAILED, failed.recovery)
    }

    @Test
    fun `a CRC error is reported as a bad transfer rather than a bad package`() = runTest {
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(
            pkg.imageSize,
            rejectProcedure = LegacyDfu.OP_RECEIVE_FW,
            rejectWith = LegacyDfu.RESP_CRC_ERROR,
        )
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertEquals(Recovery.CRC, failed.recovery)
        assertTrue(!bootloader.activated, "activated after a CRC failure")
        assertTrue(bootloader.closed)
    }

    @Test
    fun `a package the node refuses never reaches activate`() = runTest {
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(
            pkg.imageSize,
            rejectProcedure = LegacyDfu.OP_START_DFU,
            rejectWith = LegacyDfu.RESP_NOT_SUPPORTED,
        )
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertEquals(Recovery.REJECTED, failed.recovery)
        assertTrue(bootloader.imageReceived.isEmpty(), "sent an image the node had refused")
        assertTrue(!bootloader.activated)
    }

    @Test
    fun `a node that goes silent mid-transfer is not called flashable over Bluetooth`() = runTest {
        // The commonest real failure: the phone walks out of range. This
        // test used to assert the opposite — that the node "stays in DFU
        // mode waiting for another attempt" — and that was true of the
        // link and false of the node: the stock bootloader stays latched
        // out of IDLE, so the next attempt's start is refused, and the
        // restart that would clear it puts an erased node in USB mode.
        val pkg = packageOf(512)
        val resets = mutableListOf<ByteArray>()
        val silent = object : DfuGattClient {
            override val notifications: Flow<ByteArray> = emptyFlow()
            override suspend fun connect() {}
            override suspend fun subscribeToControlPoint() {}
            override suspend fun writeControl(bytes: ByteArray) {
                if (LegacyDfu.rebootsInsideTheHandler(bytes)) resets += bytes
            }
            override suspend fun writePacket(bytes: ByteArray) {}
            override suspend fun close() {}
        }
        val updater = FirmwareUpdater(FakeScanner(peer)) { silent }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertEquals(Recovery.INTERRUPTED, failed.recovery)
        assertTrue(failed.recovery.contains("USB cable"), failed.recovery)
        assertTrue(!failed.recovery.contains("waiting, not"), "promised a Bluetooth retry")
        assertTrue(resets.isEmpty(), "a node that may be erased was restarted")
    }

    @Test
    fun `every recovery message tells the user what to do next`() {
        val messages = listOf(
            Recovery.NODE_NOT_FOUND,
            Recovery.CONNECT_FAILED,
            Recovery.STALE_BOND,
            Recovery.NO_DFU_SERVICE,
            Recovery.INTERRUPTED,
            Recovery.UNCHANGED,
            Recovery.UNKNOWN_STAGE,
            Recovery.CRC,
            Recovery.REJECTED,
            Recovery.TOO_FAST,
            Recovery.STALE_SESSION,
            Recovery.NOT_ADVERTISING,
            Recovery.LINK_LOST,
        )
        for (m in messages) {
            assertTrue(m.length > 40, "too terse to act on: $m")
        }
        assertEquals(messages.size, messages.toSet().size, "two failures share one message")
    }

    // --- a repeater that has run `start ota` ------------------------------

    @Test
    fun `a node advertising for an update is told to jump before anything is flashed`() = runTest {
        // `start ota` does NOT reboot a repeater. NRF52Board::
        // startOTAUpdate brings up a BLE stack inside the running
        // firmware and advertises; the node keeps repeating until a
        // client writes the jump byte. Treating it as if it were already
        // in the bootloader means writing DFU opcodes at a control point
        // that accepts exactly one byte and ignores the rest — a hang,
        // not an error.
        val pkg = packageOf(1024)
        val appMode = FakeBootloader(pkg.imageSize)
        val bootloader = FakeBootloader(pkg.imageSize)
        val clients = mutableListOf<DfuGattClient>()
        val scanner = FakeScanner(
            DfuPeer("AA:BB:CC:DD:EE:10", "RAK4631_OTA"),
            DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU"),
        )
        var call = 0
        val updater = FirmwareUpdater(scanner) {
            val client = if (call++ == 0) appMode else bootloader
            clients += client
            client
        }

        val progress = updater.update(
            pkg,
            DfuTarget.AdvertisingForUpdate("AA:BB:CC:DD:EE:10", "RAK 4631"),
        ).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        // The first connection wrote the jump and nothing else.
        assertTrue(appMode.jumped, "the node was never told to enter its bootloader")
        assertTrue(appMode.imageReceived.isEmpty(), "an image was sent to the app-mode service")
        assertTrue(appMode.closed, "the app-mode connection was left open")
        // The image went to the bootloader found afterwards.
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
        assertTrue(progress.any { it is DfuProgress.EnteringBootloader })
    }

    @Test
    fun `the scan accepts both the announced address and the bootloader's`() = runTest {
        // The first scan has to be able to find the node in EITHER
        // state, because which state it is in is exactly what we do not
        // know: still in its own firmware on the address it announced,
        // or already in the bootloader one higher. Matching only the
        // announced address would miss a node that had already jumped
        // and report it as "not advertising".
        val pkg = packageOf(512)
        val scanner = FakeScanner(
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA"),
            DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU"),
        )
        var call = 0
        val updater = FirmwareUpdater(scanner) {
            if (call++ == 0) FakeBootloader(pkg.imageSize) else FakeBootloader(pkg.imageSize)
        }

        updater.update(
            pkg,
            DfuTarget.AdvertisingForUpdate("AA:BB:CC:DD:EE:10", "RAK 4631"),
        ).toList()

        assertEquals(2, scanner.expectations.size)
        assertEquals("AA:BB:CC:DD:EE:10", scanner.expectations[0].companionAddress)
        assertNull(scanner.expectations[0].exactAddress)
        // Second scan: after the jump, the bootloader is one higher.
        assertEquals("AA:BB:CC:DD:EE:10", scanner.expectations[1].companionAddress)
    }

    @Test
    fun `a node that is not advertising is named as such`() = runTest {
        val pkg = packageOf(512)
        val updater = FirmwareUpdater(FakeScanner(null)) { FakeBootloader(pkg.imageSize) }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.AdvertisingForUpdate()).toList().last(),
        )
        assertEquals(Recovery.NOT_ADVERTISING, failed.recovery)
        assertTrue(failed.recovery.contains("ESP32"))
    }

    @Test
    fun `a dropped link is not reported as a radio that cannot be updated`() = runTest {
        // GATT status 133 is Android's catch-all — a link that failed,
        // and nothing at all about what the node supports. This was
        // reported as "this radio does not offer over-the-air updates",
        // which sent the reader off to check their board and firmware
        // version when the answer was to move closer and try again.
        val pkg = packageOf(512)
        val transport = FakeTransport(IllegalStateException("The write failed (status 133)."))
        val updater = FirmwareUpdater(FakeScanner(peer)) { FakeBootloader(pkg.imageSize) }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.ConnectedRadio(transport, "AA:BB:CC:DD:EE:10", null))
                .toList().last(),
        )
        assertEquals(Recovery.LINK_LOST, failed.recovery)
        assertTrue(
            !failed.recovery.contains("does not offer"),
            "a dropped link still reads as an unsupported radio",
        )
        assertTrue(failed.recovery.contains("still running"))
    }

    @Test
    fun `a node already in its bootloader is not sent the jump`() = runTest {
        // Arriving from "this node is stuck": the address we recorded is
        // the one it announced, and it is now advertising on that
        // address + 1, which means it already took the jump.
        //
        // Writing the jump again would be wrong in a way that is easy to
        // miss: the bootloader reads the byte AFTER the op code as the
        // image type, so a one-byte 0x01 is a malformed start-DFU rather
        // than a reboot request.
        val pkg = packageOf(1024)
        val bootloader = FakeBootloader(pkg.imageSize)
        val scanner = FakeScanner(DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA"))
        val updater = FirmwareUpdater(scanner) { bootloader }

        val progress = updater.update(
            pkg,
            DfuTarget.AdvertisingForUpdate("AA:BB:CC:DD:EE:10", "ProMicro DIY"),
        ).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertTrue(!bootloader.jumped, "sent the jump to a node already in its bootloader")
        assertTrue(progress.none { it is DfuProgress.EnteringBootloader })
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
    }

    @Test
    fun `a node still in app mode is sent the jump first`() = runTest {
        // Same entry point, one address lower: this one has only run
        // `start ota` and has to be rebooted before there is a
        // bootloader to talk to.
        val pkg = packageOf(1024)
        val appMode = FakeBootloader(pkg.imageSize)
        val bootloader = FakeBootloader(pkg.imageSize)
        val scanner = FakeScanner(
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA"),
            DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA"),
        )
        var call = 0
        val updater = FirmwareUpdater(scanner) {
            if (call++ == 0) appMode else bootloader
        }

        val progress = updater.update(
            pkg,
            DfuTarget.AdvertisingForUpdate("AA:BB:CC:DD:EE:10", "ProMicro DIY"),
        ).toList()

        assertTrue(appMode.jumped, "the node was never told to enter its bootloader")
        assertTrue(appMode.imageReceived.isEmpty())
        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
    }

    // --- signal strength --------------------------------------------------

    @Test
    fun `a transfer is not started over a link too weak to finish it`() = runTest {
        // The bootloader erases before it writes, so losing the link
        // half way costs a visit to the node — the exact trip this
        // feature exists to save.
        val pkg = packageOf(400_000)
        val bootloader = FakeBootloader(pkg.imageSize)
        val faint = DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA", rssi = -99)
        val updater = FirmwareUpdater(FakeScanner(faint)) { bootloader }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        val stopped = assertIs<DfuProgress.SignalTooWeak>(progress.last())
        assertEquals(-99, stopped.rssi)
        assertTrue(bootloader.imageReceived.isEmpty(), "started a transfer it should not have")
        assertTrue(!bootloader.activated)
    }

    @Test
    fun `the nodes on this mesh are reachable at the signal they actually have`() = runTest {
        // -91 and -93 dBm are the levels measured against the real
        // repeater, and some nodes here cannot be approached closer than
        // that — they are on masts. A floor above these would refuse
        // every attempt on the nodes that most need updating, which
        // sends the whole job back to a USB cable and a ladder.
        for (rssi in listOf(-91, -93, -94)) {
            val pkg = packageOf(2048)
            val bootloader = FakeBootloader(pkg.imageSize)
            val peer = DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA", rssi = rssi)
            val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

            val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()
            assertEquals(DfuProgress.Finished, progress.last(), "refused at $rssi dBm")
        }
    }

    @Test
    fun `a strong link is not second-guessed`() = runTest {
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(pkg.imageSize)
        val near = DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA", rssi = -55)
        val updater = FirmwareUpdater(FakeScanner(near)) { bootloader }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()
        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
    }

    @Test
    fun `an unknown signal is not treated as a weak one`() = runTest {
        // Not every path knows the RSSI — a peer reached by address
        // rather than by scanning, for one. Refusing to flash because we
        // did not measure would be a guess dressed as caution.
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(pkg.imageSize)
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()
        assertEquals(DfuProgress.Finished, progress.last())
    }

    @Test
    fun `the user can overrule the weak-signal refusal`() = runTest {
        // A node on a mast cannot be got closer to. Refusing outright
        // would leave it unupdatable forever.
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(pkg.imageSize)
        val faint = DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA", rssi = -100)
        val updater = FirmwareUpdater(FakeScanner(faint)) { bootloader }

        val progress = updater.update(
            pkg,
            DfuTarget.WaitingInBootloader(),
            DfuOptions(allowWeakSignal = true),
        ).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
    }

    // --- the jump cannot be acknowledged ----------------------------------

    @Test
    fun `a jump write that kills the link counts as success`() = runTest {
        // BLEDfu.cpp saves the bond keys, disables the SoftDevice and
        // calls bootloader_util_app_start() while handling this write.
        // The node is gone before it can answer, so the write callback
        // fails with status 133 and the link times out. Reading that as
        // an error is how a jump that WORKED got reported as a dead
        // radio — and left a node sitting in its bootloader while the
        // app said the update had failed.
        val pkg = packageOf(1024)
        val appMode = FakeBootloader(pkg.imageSize, jumpKillsTheLink = true)
        val bootloader = FakeBootloader(pkg.imageSize)
        val scanner = FakeScanner(
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA", rssi = -60),
            DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA", rssi = -60),
        )
        var call = 0
        val updater = FirmwareUpdater(scanner) {
            if (call++ == 0) appMode else bootloader
        }

        val progress = updater.update(
            pkg,
            DfuTarget.AdvertisingForUpdate("AA:BB:CC:DD:EE:10", "ProMicro DIY"),
        ).toList()

        assertTrue(appMode.jumped, "the jump was never written")
        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
    }

    @Test
    fun `a node that refuses the jump is still reported`() = runTest {
        // Ignoring a lost link must not become ignoring a refusal: a
        // stale bond says the write was rejected rather than obeyed, and
        // no amount of scanning afterwards will find a bootloader.
        val pkg = packageOf(512)
        val appMode = FakeBootloader(pkg.imageSize, jumpRefused = true)
        val updater = FirmwareUpdater(
            FakeScanner(DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA", rssi = -60)),
        ) { appMode }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(
                pkg,
                DfuTarget.AdvertisingForUpdate("AA:BB:CC:DD:EE:10", "ProMicro DIY"),
            ).toList().last(),
        )
        assertEquals(Recovery.STALE_BOND, failed.recovery)
    }

    @Test
    fun `a jump that quietly did nothing is caught by the scan that follows`() = runTest {
        // The write is not the proof — the bootloader turning up is. If
        // it never appears, that is reported rather than assumed away.
        val pkg = packageOf(512)
        val appMode = FakeBootloader(pkg.imageSize, jumpKillsTheLink = true)
        val scanner = FakeScanner(
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA", rssi = -60),
            null,
        )
        val updater = FirmwareUpdater(scanner) { appMode }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(
                pkg,
                DfuTarget.AdvertisingForUpdate("AA:BB:CC:DD:EE:10", "ProMicro DIY"),
            ).toList().last(),
        )
        assertEquals(Recovery.NODE_NOT_FOUND, failed.recovery)
    }

    @Test
    fun `with no address to compare the peer is asked which mode it is in`() = runTest {
        // Reached from "a node is already in update mode": found by
        // scanning alone, so there is no announced address and no
        // arithmetic to do. The revision characteristic settles it —
        // 0x0008 is the bootloader (dfu_transport_ble.c), 0x0001 the
        // running application (BLEDfu.cpp).
        val pkg = packageOf(1024)
        val bootloader = FakeBootloader(pkg.imageSize, revision = LegacyDfu.REVISION_BOOTLOADER)
        val scanner = FakeScanner(DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA", rssi = -70))
        val updater = FirmwareUpdater(scanner) { bootloader }

        val progress = updater.update(pkg, DfuTarget.AdvertisingForUpdate()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertTrue(!bootloader.jumped, "sent a jump to a peer that said it was a bootloader")
        assertTrue(progress.none { it is DfuProgress.EnteringBootloader })
    }

    @Test
    fun `the connection the peer was probed on is the one it is flashed on`() = runTest {
        // The probe that reads the DFU revision used to close its
        // connection, after which the transfer opened another to the
        // same address. A bootloader takes ONE connection at a time and
        // needs a moment to drop the old one and start advertising
        // again, so the immediate reconnect lost the race: status 0,
        // then an autoConnect fallback that came up nominally connected
        // and answered nothing. On hardware it read as "the node stopped
        // responding part-way through" four milliseconds into a transfer
        // — on a node that had been perfectly reachable a second before,
        // on the connection this app had just thrown away.
        val pkg = packageOf(1024)
        val clients = mutableListOf<FakeBootloader>()
        val scanner = FakeScanner(DfuPeer("AA:BB:CC:DD:EE:11", "ProMicro_OTA", rssi = -70))
        val updater = FirmwareUpdater(scanner) {
            FakeBootloader(pkg.imageSize, revision = LegacyDfu.REVISION_BOOTLOADER)
                .also { clients += it }
        }

        val progress = updater.update(pkg, DfuTarget.AdvertisingForUpdate()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertEquals(1, clients.size, "the peer was connected to more than once")
        assertContentEquals(pkg.image, clients.single().imageReceived.toByteArray())
    }

    @Test
    fun `an app-mode peer is sent the jump on the connection it was probed on`() = runTest {
        // This test used to pin the opposite — close the probe, then
        // connect again for the jump — on the reasoning that an open
        // connection left behind would race the jump's. The reconnect
        // WAS the race: on the test RAK (2026-09-24) the jump's
        // connection attached to a link the stack was still tearing
        // down, dropped during the MTU request, and the update hung.
        val pkg = packageOf(1024)
        val appMode = FakeBootloader(
            pkg.imageSize,
            jumpKillsTheLink = true,
            revision = LegacyDfu.REVISION_APP_MODE,
        )
        val scanner = FakeScanner(
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA", rssi = -70),
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA", rssi = -70),
        )
        val bootloader = FakeBootloader(pkg.imageSize)
        val made = mutableListOf<FakeBootloader>()
        var call = 0
        val updater = FirmwareUpdater(scanner) {
            (if (call++ == 0) appMode else bootloader).also { made += it }
        }

        val progress = updater.update(pkg, DfuTarget.AdvertisingForUpdate()).toList()

        assertTrue(appMode.jumped, "an app-mode peer was never told to jump")
        assertEquals(1, made.count { it === appMode }, "the app-mode peer was connected to twice")
        assertTrue(appMode.closed, "the jump connection was left open")
        assertEquals(DfuProgress.Finished, progress.last())
    }

    @Test
    fun `a peer that says it is in app mode is still sent the jump`() = runTest {
        val pkg = packageOf(1024)
        val appMode = FakeBootloader(
            pkg.imageSize,
            jumpKillsTheLink = true,
            revision = LegacyDfu.REVISION_APP_MODE,
        )
        val bootloader = FakeBootloader(pkg.imageSize)
        val scanner = FakeScanner(
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA", rssi = -70),
            DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA", rssi = -70),
        )
        var call = 0
        val updater = FirmwareUpdater(scanner) {
            // the probe (which also carries the jump), then the transfer
            if (call++ == 0) appMode else bootloader
        }

        val progress = updater.update(pkg, DfuTarget.AdvertisingForUpdate()).toList()

        assertTrue(appMode.jumped, "an app-mode peer was never told to jump")
        assertEquals(DfuProgress.Finished, progress.last())
    }

    // --- the bootloader falling behind -----------------------------------

    @Test
    fun `the image step failing with the catch-all is diagnosed as too fast`() = runTest {
        // Measured against the real node: the transfer stopped 600 bytes
        // in with `[0x10, 3, 6]`. The bootloader copies each packet into
        // a small hci_mem_pool and flushes it to flash asynchronously;
        // when that pool fills, `hci_mem_pool_rx_produce` returns
        // NRF_ERROR_NO_MEM, and nrf_err_code_translate has no specific
        // response value for it, so it arrives as the catch-all.
        //
        // Reporting that as "the node refused the package" sends someone
        // off to re-check their board and their file, when the fix is to
        // send it more slowly.
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(
            pkg.imageSize,
            rejectProcedure = LegacyDfu.OP_RECEIVE_FW,
            rejectWith = LegacyDfu.RESP_OPER_FAILED,
        )
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertEquals(Recovery.TOO_FAST, failed.recovery)
        // It used to end "Retrying more slowly is the fix", beside a
        // button that did so — against a node that, having failed after
        // the start step, refuses any new start. The honest advice is
        // the cable.
        assertTrue(!failed.recovery.contains("more slowly"), failed.recovery)
        assertTrue(failed.recovery.contains("USB cable"), failed.recovery)
    }

    @Test
    fun `the same catch-all on another step is not blamed on speed`() {
        // Only the image step is a pool-overflow story. The start step
        // failing this way is something else entirely.
        assertEquals(
            Recovery.REJECTED,
            Recovery.forFailure(
                DfuFailure.Rejected(LegacyDfu.OP_START_DFU, LegacyDfu.RESP_OPER_FAILED),
                applicationErased = true,
            ),
        )
        assertEquals(
            Recovery.CRC,
            Recovery.forFailure(
                DfuFailure.Rejected(LegacyDfu.OP_RECEIVE_FW, LegacyDfu.RESP_CRC_ERROR),
                applicationErased = true,
            ),
        )
    }

    // --- a bootloader latched out of an earlier session -------------------

    @Test
    fun `a node stuck part-way through an earlier update is never reset`() = runTest {
        // Measured on 13 Mile, 2026-08-13: every attempt for days came
        // back "The radio rejected the start step: invalid state".
        //
        // dfu_single_bank.c ends dfu_start_pkt_handle with
        // `if (DFU_STATE_IDLE != m_dfu_state) return
        // NRF_ERROR_INVALID_STATE;`, and nothing but dfu_init() at boot
        // puts that state back — so a reset would clear it. This test
        // used to assert exactly that, and it was the most expensive
        // assertion in the suite: the ONLY way out of IDLE is
        // `dfu_prepare_func_app_erase`, so this refusal proves the bank
        // is already erased, and a reset of an erased stock bootloader
        // brings it up in USB mode. The refusal is reported, the node is
        // left reachable, and nothing is tried twice.
        val pkg = packageOf(2048)
        val made = mutableListOf<FakeBootloader>()
        val updater = FirmwareUpdater(FakeScanner(peer)) {
            FakeBootloader(pkg.imageSize, latchedFromAnEarlierSession = true).also { made += it }
        }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(1, made.size, "a latched node was connected to again")
        assertEquals(0, made.single().resets, "a node proven erased was reset into USB mode")
        assertTrue(made.single().imageReceived.isEmpty(), "sent an image to a node that refused")
        assertEquals(Recovery.STALE_SESSION, failed.recovery)
        assertTrue(!failed.recovery.contains("right file"), "a latched session read as a bad package")
    }

    @Test
    fun `the link is asked for a short connection interval before anything is sent`() = runTest {
        // A default Android connection interval is 30-50 ms, and at that
        // spacing the stack packs several 20-byte writes into each
        // connection event — so a receipt window arrives as two or three
        // bursts, faster than the bootloader's flash path drains
        // `hci_mem_pool`, and it answers `operation failed`. Nordic's own
        // legacy implementation requests CONNECTION_PRIORITY_HIGH, and so
        // does Meshtastic's, whose note reads: "Default Android intervals
        // (~30-50 ms) starve the link during sustained DFU and trigger
        // LSTO." This app did not, and a live ProMicro managed 200 bytes
        // in 17 seconds before failing.
        val pkg = packageOf(1024)
        val bootloader = FakeBootloader(pkg.imageSize)
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertTrue(
            bootloader.highThroughputRequested,
            "the transfer ran at whatever interval Android felt like",
        )
    }

    @Test
    fun `a node that cannot keep up is not retried behind the operator's back`() = runTest {
        // `operation failed` during the image step is the receive pool
        // overflowing. This used to trigger an automatic retry at half
        // the receipt interval and double the packet gap — against a
        // fake that accepted the second start. A real stock bootloader
        // does not: the failure leaves it latched out of IDLE, so the
        // retry's start is refused with `invalid state` before a byte
        // is sent. The fake that let it succeed was the same assumption
        // twice.
        val pkg = packageOf(2048)
        val made = mutableListOf<FakeBootloader>()
        val updater = FirmwareUpdater(FakeScanner(peer, peer)) {
            FakeBootloader(pkg.imageSize, chokesAbovePrn = 5).also { made += it }
        }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(1, made.size, "the transfer was attempted again")
        assertEquals(Recovery.TOO_FAST, failed.recovery)
        assertEquals(0, made.single().resets, "a node with an erased bank was reset onto a cable")
    }

    @Test
    fun `a node that goes quiet mid-stream is not waited on for ever`() = runTest {
        // The failure that has no error in it. There is nothing in
        // legacy DFU to ask a peer whether it is still there — the
        // control point stops notifying and the link stays nominally
        // connected — so an unbounded wait shows a progress bar frozen
        // at whatever number it last reached, with no way out but
        // killing the app. A live ProMicro did this at 14,800 of
        // 372,044 bytes.
        val pkg = packageOf(4096)
        val quiet = FakeBootloader(pkg.imageSize, goesQuietAfter = 400)
        var connections = 0
        val updater = FirmwareUpdater(FakeScanner(peer, peer)) { connections++; quiet }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        // It gave up on the silence, and said so. Not reset — the stall
        // happened mid-image, so the bank is erased — and not retried:
        // the latched bootloader would refuse the retry's start.
        val failed = assertIs<DfuProgress.Failed>(progress.last())
        assertEquals(Recovery.INTERRUPTED, failed.recovery)
        assertEquals(0, quiet.resets, "a node with an erased bank was reset onto a cable")
        assertEquals(1, connections, "a latched node was connected to again")
    }

    @Test
    fun `a node that stays quiet says how far it got`() = runTest {
        val pkg = packageOf(4096)
        val updater = FirmwareUpdater(FakeScanner(peer)) {
            FakeBootloader(pkg.imageSize, goesQuietAfter = 400)
        }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(Recovery.INTERRUPTED, failed.recovery)
        // How far it got, out of how much — the number that decides
        // whether this was a link that never really worked or a node
        // that died three quarters of the way through.
        assertTrue(
            Regex("""after \d+ of 4096 bytes""").containsMatchIn(failed.message),
            "a stall must say how far it got: ${failed.message}",
        )
    }

    @Test
    fun `a node whose application is erased is never reset`() = runTest {
        // The most expensive thing this code can do, and it did it on
        // hardware: a transfer failed around 15 KB, the abandon path
        // wrote a system reset, and the node came back in USB
        // mass-storage mode advertising nothing at all. Adafruit's
        // bootloader only brings BLE DFU up when it was entered by an
        // over-the-air request; a plain reset is not one. So the reset
        // turned a node that was still reachable over Bluetooth into
        // one that needed a cable.
        //
        // Before the start step is accepted the reset is still right —
        // the application is intact and the node boots it.
        val pkg = packageOf(4096)
        val erased = FakeBootloader(pkg.imageSize, goesQuietAfter = 400)
        val updater = FirmwareUpdater(FakeScanner(peer)) { erased }

        updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(
            0,
            erased.resets,
            "a node with an erased application bank was reset onto a USB cable",
        )
    }

    @Test
    fun `a stock bootloader's packets are spaced out and a bigger link's are not`() {
        // The measured link, not a name. A peer still at the 23-byte ATT
        // default never raised its MTU, which is what a stock Adafruit
        // bootloader does — small buffers included. One that negotiated
        // up to 244 has the pool to match and needs no pause.
        assertEquals(
            LegacyDfu.STOCK_BOOTLOADER_PACKET_DELAY_MS,
            LegacyDfu.packetDelayFor(LegacyDfu.DEFAULT_CHUNK_SIZE),
        )
        assertEquals(0L, LegacyDfu.packetDelayFor(LegacyDfu.MAX_PACKET_SIZE))
        // Slow enough to matter: the rate that failed on hardware was
        // about 150 packets a second, and this has to be well under it.
        assertTrue(
            1000 / LegacyDfu.STOCK_BOOTLOADER_PACKET_DELAY_MS < 100,
            "the pause still allows a rate a live ProMicro refused",
        )
    }

    @Test
    fun `a second attempt at a node an earlier one erased does not reset it`() = runTest {
        // The hole that survived the first two versions of this fix, and
        // the one the operator could walk into from the screen: attempt
        // one erased the bank and correctly declined to reset; attempt
        // two — started fresh, from a button — was refused with `invalid
        // state` before it sent a byte, so ITS session had erased nothing
        // and wrote the reset. The fix was threaded through the
        // updater's own retry, never through a new update().
        //
        // It needs no memory of the first attempt now: the refusal
        // itself proves the erase (see [DfuFailure.StaleSession]).
        val pkg = packageOf(4096)
        val first = FakeBootloader(pkg.imageSize, goesQuietAfter = 400)
        val latched = FakeBootloader(pkg.imageSize, latchedFromAnEarlierSession = true)

        FirmwareUpdater(FakeScanner(peer)) { first }
            .update(pkg, DfuTarget.WaitingInBootloader()).toList()
        val again = FirmwareUpdater(FakeScanner(peer)) { latched }
            .update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(0, first.resets)
        assertEquals(0, latched.resets, "a fresh attempt reset a node with no application")
        assertEquals(Recovery.STALE_SESSION, assertIs<DfuProgress.Failed>(again.last()).recovery)
    }

    @Test
    fun `a start step that goes unanswered counts as an erase`() = runTest {
        // The bootloader erases inside the start handler and answers
        // afterwards, so silence at the start step is no evidence that
        // the bank survived. This used to be abandoned with a reset,
        // because the session only counted a node as erased once the
        // start had been ACKNOWLEDGED.
        val pkg = packageOf(1024)
        val silent = FakeBootloader(pkg.imageSize, silentAtStart = true)
        val updater = FirmwareUpdater(FakeScanner(peer)) { silent }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(0, silent.resets, "a node that may be mid-erase was restarted")
        assertEquals(Recovery.INTERRUPTED, failed.recovery)
    }

    @Test
    fun `a session that never sent the start step still offers the restart`() {
        // The positive control for the tests above. Without it, "never
        // reset anything" would pass them all — and the restart is what
        // puts a node that still has its firmware back on the mesh.
        val session = LegacyDfuSession(byteArrayOf(1, 2, 3, 4), ByteArray(64))
        assertTrue(!session.applicationErased)
        assertEquals(
            listOf(DfuAction.WriteControl(LegacyDfu.SYSTEM_RESET)),
            session.abort(),
            "a node that still has its application was not offered a restart",
        )
    }

    @Test
    fun `sending the start step is what counts and not its answer`() {
        val session = LegacyDfuSession(byteArrayOf(1, 2, 3, 4), ByteArray(64))
        session.start()
        assertTrue(session.applicationErased, "the start is out; the peer may be erasing")
        assertEquals(emptyList(), session.abort())
    }

    @Test
    fun `no refusal of the start step makes the node count as intact again`() {
        // NOT_SUPPORTED and DATA_SIZE are returned before the erase — but
        // also before the latch check, so a node an EARLIER attempt
        // erased answers them too.
        for (result in listOf(
            LegacyDfu.RESP_NOT_SUPPORTED,
            LegacyDfu.RESP_DATA_SIZE,
            LegacyDfu.RESP_INVALID_STATE,
            LegacyDfu.RESP_OPER_FAILED,
        )) {
            val session = LegacyDfuSession(byteArrayOf(1, 2, 3, 4), ByteArray(64))
            session.start()
            session.onNotification(
                byteArrayOf(
                    LegacyDfu.OP_RESPONSE.toByte(),
                    LegacyDfu.OP_START_DFU.toByte(),
                    result.toByte(),
                ),
            )
            assertTrue(session.applicationErased, "result $result cleared the erase")
            assertEquals(emptyList(), session.abort(), "result $result led to a reset")
        }
    }

    @Test
    fun `abandoning a transfer after the start step writes nothing`() {
        val session = LegacyDfuSession(byteArrayOf(1, 2, 3, 4), ByteArray(64))
        session.start()
        session.onNotification(
            byteArrayOf(
                LegacyDfu.OP_RESPONSE.toByte(),
                LegacyDfu.OP_START_DFU.toByte(),
                LegacyDfu.RESP_SUCCESS.toByte(),
            ),
        )
        assertTrue(session.applicationErased, "the start step was accepted and the bank is gone")
        assertEquals(emptyList(), session.abort())
    }

    @Test
    fun `a write that is never acknowledged does not hang the update`() = runTest {
        // The stall watchdog bounded the wrong half. It covered waiting
        // for the peer to say something, and every packet write sits
        // OUTSIDE it — so a `writeCharacteristic` whose completion
        // callback never arrives suspends for ever, behind a progress
        // bar frozen at the last batch, with no way out but killing the
        // app. That is exactly what a live ProMicro looked like at
        // 14,800 of 372,044 bytes, and the watchdog added afterwards
        // could not have caught it.
        //
        // Android delivers `onCharacteristicWrite` for write-without-
        // response too, and it is the documented flow-control signal —
        // but it is a callback from a vendor stack, and "it always
        // arrives" is an assumption, not a guarantee.
        val pkg = packageOf(4096)
        val updater = FirmwareUpdater(FakeScanner(peer)) {
            FakeBootloader(pkg.imageSize, swallowsWritesAfter = 400)
        }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertEquals(Recovery.INTERRUPTED, failed.recovery)
        assertTrue(
            Regex("""of 4096 bytes""").containsMatchIn(failed.message),
            "a write that never completed must still say how far it got: ${failed.message}",
        )
    }

    @Test
    fun `the short connection interval is asked for again as the transfer runs`() = runTest {
        // Requested once, immediately before the image — and then hoped
        // for. An Android connection-priority request is advisory and
        // several stacks quietly let it lapse, which on a transfer
        // measured in minutes means it holds for the part that was
        // never in doubt. Nordic's own implementation re-asserts it as
        // it goes; so does this.
        val pkg = packageOf(8192)
        val bootloader = FakeBootloader(pkg.imageSize)
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertTrue(
            bootloader.highThroughputRequests > 1,
            "the connection interval was requested ${bootloader.highThroughputRequests} " +
                "time(s) across a whole transfer",
        )
    }

    @Test
    fun `the start step is allowed to be silent for far longer than the others`() {
        // A stock single-bank bootloader erases the whole application
        // bank before answering the start, and the SoftDevice
        // time-slices each page erase against radio events — 30-50 s of
        // silence there is health, not a stall, and a shorter cap aborts
        // transfers that were about to work.
        assertEquals(90_000L, stallBudgetMs(DfuStage.Starting))
        assertEquals(60_000L, stallBudgetMs(DfuStage.Validating))
        assertEquals(30_000L, stallBudgetMs(DfuStage.SendingImage))
        assertEquals(30_000L, stallBudgetMs(DfuStage.SendingInit))
        // The write backstop must never be tighter than the budget it
        // sits inside, or it decides instead of supporting.
        //
        // Observed on a live ProMicro: the bootloader answered the start
        // step after 19 seconds of flash erase, accepted the next
        // control write, and then did not confirm a 14-byte init packet
        // within the 10 seconds the backstop allowed — so a step with a
        // 30-second budget was failed at 10 by the guard meant to catch
        // an infinite hang. A peer starved of radio time by its own
        // flash erase is busy, not gone, and which of the two it is, is
        // this budget's call.
        for (stage in DfuStage.entries) {
            assertTrue(
                WRITE_CONFIRMATION_TIMEOUT_MS > stallBudgetMs(stage),
                "the write backstop ($WRITE_CONFIRMATION_TIMEOUT_MS ms) preempts $stage " +
                    "(${stallBudgetMs(stage)} ms)",
            )
        }
        for (stage in DfuStage.entries) {
            assertTrue(stallBudgetMs(stage) >= 30_000L, "$stage has too short a budget")
        }
    }

    @Test
    fun `the packet size comes from the link and is never unaligned`() {
        // The bootloader answers BLE_DFU_RESP_VAL_NOT_SUPPORTED to any
        // write whose length is not a multiple of four, so the size
        // derived from an MTU is floored to a word.
        assertEquals(20, LegacyDfu.packetSizeFor(null), "an unknown MTU must use the safe floor")
        // MTU 23 — the ATT default, and what the link reports when the
        // exchange never happened. NOT, as this comment used to claim,
        // "what a stock Adafruit bootloader reports": that bootloader
        // negotiates properly, and believing otherwise makes a 20-byte
        // transfer look like the expected outcome instead of a symptom.
        // See the alignment test below.
        assertEquals(20, LegacyDfu.packetSizeFor(20))
        // MTU 247, the most the data-length extension gives.
        assertEquals(244, LegacyDfu.packetSizeFor(244))
        assertEquals(244, LegacyDfu.packetSizeFor(500), "the cap was not applied")
        // Word alignment, in both directions.
        assertEquals(20, LegacyDfu.packetSizeFor(23))
        assertEquals(100, LegacyDfu.packetSizeFor(103))
        assertEquals(20, LegacyDfu.packetSizeFor(4), "below the floor is still the floor")
        for (mtu in 0..600) {
            val size = LegacyDfu.packetSizeFor(mtu)
            assertEquals(0, size % 4, "packet size $size for MTU $mtu is not word-aligned")
            assertTrue(size in 20..244, "packet size $size for MTU $mtu is out of range")
        }
    }

    @Test
    fun `the derived packet size is exactly what the bootloader replied it could take`() {
        // Written against the BOOTLOADER's arithmetic rather than our
        // own, which is the whole lesson of the trace and neighbours
        // defects: a size checked only against our parser is the same
        // assumption twice.
        //
        // `dfu_transport_ble.c` answers an MTU exchange with
        //
        //     uint16_t att_mtu = MIN(client_rx_mtu, BLEGATT_ATT_MTU_MAX);
        //     att_mtu &= 0xFFFCU;
        //     att_mtu |= 3;
        //     sd_ble_gatts_exchange_mtu_reply(m_conn_handle, att_mtu);
        //
        // — it rounds so that (MTU − 3) is a whole number of words,
        // because DFU data packets must be. Our derivation has to land
        // on the same number for every MTU it could ever reply with, or
        // the transfer is either short-writing every packet or being
        // answered NOT_SUPPORTED.
        //
        // It also settles a claim that had been carried here on
        // plausibility alone: that a bootloader unable to take large
        // writes "never negotiates a large MTU". It negotiates fully.
        // The 20-byte floor is what an ABSENT exchange leaves behind,
        // not a property of the peer — so a transfer running at 20 bytes
        // a packet means the MTU request did not happen, and that is a
        // fault to chase rather than a limit to accept.
        val attMtuMax = 247
        for (requested in 23..600) {
            val replied = (minOf(requested, attMtuMax) and 0xFFFC) or 3
            val theirs = replied - 3
            if (theirs < LegacyDfu.DEFAULT_CHUNK_SIZE) continue
            assertEquals(
                theirs,
                LegacyDfu.packetSizeFor(theirs),
                "MTU $requested: the bootloader offered $theirs bytes and we did not use them",
            )
        }
        assertEquals(244, LegacyDfu.packetSizeFor(attMtuMax - 3))
    }

    @Test
    fun `a link that offers a bigger mtu is used`() = runTest {
        val pkg = packageOf(2048)
        val bootloader = object : DfuGattClient by FakeBootloader(pkg.imageSize) {
            override suspend fun maxWriteLength(): Int = 247
        }
        // Delegation would hand the fake's own writes back, so this only
        // pins the derivation the updater performs.
        assertEquals(244, LegacyDfu.packetSizeFor(bootloader.maxWriteLength()))
    }

    @Test
    fun `a write the node reboots inside is not a failure`() {
        // Three false failures in this codebase have come from reading
        // status 133 on one of these as a refusal: the jump into the
        // bootloader, "restart it", and a completed flash reported as an
        // error on its very last write.
        assertTrue(LegacyDfu.rebootsInsideTheHandler(byteArrayOf(LegacyDfu.OP_ACTIVATE_N_RESET.toByte())))
        assertTrue(LegacyDfu.rebootsInsideTheHandler(LegacyDfu.SYSTEM_RESET))
        assertTrue(!LegacyDfu.rebootsInsideTheHandler(byteArrayOf(LegacyDfu.OP_VALIDATE.toByte())))
        // The app-mode jump shares its opcode byte with start-DFU and is
        // told apart by length; a two-byte start must not be swallowed.
        assertTrue(
            !LegacyDfu.rebootsInsideTheHandler(
                byteArrayOf(LegacyDfu.OP_START_DFU.toByte(), LegacyDfu.IMAGE_TYPE_APP.toByte()),
            ),
        )
        assertTrue(!LegacyDfu.rebootsInsideTheHandler(byteArrayOf()))
    }

    @Test
    fun `a node that reboots on the activate write still reports success`() = runTest {
        // The last write of a successful flash. `dfu_transport_ble.c`
        // resets inside the handler, so the ATT response never comes and
        // the write callback fails with status 133 — which was being
        // reported as "the update failed" on a node that had just been
        // updated.
        val pkg = packageOf(1024)
        val bootloader = FakeBootloader(pkg.imageSize, activateKillsTheLink = true)
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
    }

    @Test
    fun `only the image step reads operation failed as going too fast`() {
        // The status is a catch-all — `nrf_err_code_translate` maps
        // several errors onto it — so it means "too fast" only where the
        // receive pool is what fills up.
        fun recovery(f: DfuFailure) = Recovery.forFailure(f, applicationErased = true)
        assertEquals(
            Recovery.TOO_FAST,
            recovery(DfuFailure.Rejected(LegacyDfu.OP_RECEIVE_FW, LegacyDfu.RESP_OPER_FAILED)),
        )
        assertEquals(
            Recovery.REJECTED,
            recovery(DfuFailure.Rejected(LegacyDfu.OP_VALIDATE, LegacyDfu.RESP_OPER_FAILED)),
        )
        assertEquals(
            Recovery.CRC,
            recovery(DfuFailure.Rejected(LegacyDfu.OP_RECEIVE_FW, LegacyDfu.RESP_CRC_ERROR)),
        )
        assertEquals(Recovery.STALE_SESSION, recovery(DfuFailure.StaleSession))
    }

    @Test
    fun `only a node that was never sent the start step is called safe to retry`() {
        // Every message after the start step carries the cable, and the
        // one before it does not — both halves, so neither can quietly
        // become the other.
        val failures = listOf(
            DfuFailure.StaleSession,
            DfuFailure.Stalled(400, 4096),
            DfuFailure.ByteCountMismatch(10, 20),
            DfuFailure.Rejected(LegacyDfu.OP_RECEIVE_FW, LegacyDfu.RESP_OPER_FAILED),
            DfuFailure.Rejected(LegacyDfu.OP_VALIDATE, LegacyDfu.RESP_CRC_ERROR),
        )
        for (f in failures) {
            assertTrue(Recovery.forFailure(f, true).contains("USB cable"), "$f")
            assertEquals(Recovery.UNCHANGED, Recovery.forFailure(f, false))
        }
        assertTrue(!Recovery.UNCHANGED.contains("USB"))
        assertTrue(Recovery.interrupted(true).contains("USB cable"))
        assertEquals(Recovery.UNCHANGED, Recovery.interrupted(false))
    }

    @Test
    fun `an abandoned transfer leaves the bootloader able to start another`() = runTest {
        // The trap this closes: "the node could not keep up, retry more
        // slowly" is the app's own advice, and without a reset the
        // slower retry is refused before it sends a byte — with a
        // message blaming the file. Every failure hands the node back in
        // a state it can start from.
        val pkg = packageOf(2048)
        val bootloader = FakeBootloader(
            pkg.imageSize,
            rejectProcedure = LegacyDfu.OP_RECEIVE_FW,
            rejectWith = LegacyDfu.RESP_OPER_FAILED,
        )
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(Recovery.TOO_FAST, failed.recovery)
        // Zero. The guarantee this test was written for —
        // "every failure hands the node back able to start again" — was
        // the right instinct applied to the wrong half of the transfer.
        // Before the start step is accepted a reset does exactly that.
        // After it, the application is gone, and Adafruit's bootloader
        // only brings up BLE DFU when it was entered by an
        // over-the-air request — so the reset that was supposed to
        // rescue the node is what strands it on a USB cable. Proven on
        // hardware 2026-08-14.
        assertEquals(0, bootloader.resets, "a node with an erased bank was reset onto a cable")
        assertTrue(
            bootloader.controlWrites.none {
                it == listOf(LegacyDfu.OP_SYS_RESET.toByte())
            },
        )
        assertTrue(!bootloader.activated)
    }

    @Test
    fun `a finished update is not followed by a reset`() = runTest {
        // Activate-and-reset already reboots the node into the new
        // image. A second reset would be sent to something that is gone,
        // and would mean the success path shared a code path with the
        // failure one.
        val pkg = packageOf(1024)
        val bootloader = FakeBootloader(pkg.imageSize)
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertTrue(bootloader.activated)
        assertEquals(0, bootloader.resets)
    }

    @Test
    fun `a paced transfer still delivers every byte in order`() = runTest {
        // Slowing the packets must not change what arrives.
        val pkg = packageOf(4096)
        val bootloader = FakeBootloader(pkg.imageSize, receiptInterval = 4)
        val updater = FirmwareUpdater(FakeScanner(peer)) { bootloader }

        val progress = updater.update(
            pkg,
            DfuTarget.WaitingInBootloader(),
            DfuOptions(receiptInterval = 4, packetDelayMs = 1),
        ).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, bootloader.imageReceived.toByteArray())
    }

    // --- an OTAFIX bootloader --------------------------------------------

    private val otafix = DfuPeer("AA:BB:CC:DD:EE:12", "4631_DFU", rssi = -60)

    @Test
    fun `an OTAFIX node that fails mid-image is restarted and flashed again`() = runTest {
        // OTAFIX starts in Bluetooth update mode when it has no valid
        // application, so the restart that strands a stock node is, here,
        // what clears the latched session. The second fake accepts a new
        // start because the restart really did clear it — unlike the
        // stock case, where a fake that accepted it was the defect.
        val pkg = packageOf(4096)
        val first = FakeBootloader(pkg.imageSize, goesQuietAfter = 400)
        val second = FakeBootloader(pkg.imageSize)
        var call = 0
        val scanner = FakeScanner(otafix, otafix)
        val updater = FirmwareUpdater(scanner) { if (call++ == 0) first else second }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(1, first.resets, "the OTAFIX node was not restarted")
        assertTrue(progress.any { it is DfuProgress.Retrying }, "the retry was not said out loud")
        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, second.imageReceived.toByteArray())
        assertEquals("AA:BB:CC:DD:EE:12", scanner.expectations.last().exactAddress)
        assertTrue(scanner.timeouts.last() >= REBOOT_SCAN_TIMEOUT_MS)
    }

    @Test
    fun `an OTAFIX node latched by an earlier attempt is restarted and flashed`() = runTest {
        // The 2026-08-13 fix, right after all — on this bootloader.
        val pkg = packageOf(2048)
        val latched = FakeBootloader(pkg.imageSize, latchedFromAnEarlierSession = true)
        val fresh = FakeBootloader(pkg.imageSize)
        var call = 0
        val updater = FirmwareUpdater(FakeScanner(otafix, otafix)) {
            if (call++ == 0) latched else fresh
        }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(1, latched.resets)
        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, fresh.imageReceived.toByteArray())
    }

    @Test
    fun `an OTAFIX node is retried a bounded number of times`() = runTest {
        val pkg = packageOf(2048)
        val made = mutableListOf<FakeBootloader>()
        val updater = FirmwareUpdater(FakeScanner(otafix)) {
            FakeBootloader(pkg.imageSize, goesQuietAfter = 400).also { made += it }
        }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(1 + OTAFIX_RETRIES, made.size, "the attempts were not bounded")
        // Restarted after the last failure too, so it is left ready.
        assertTrue(made.all { it.resets == 1 }, "${made.map { it.resets }}")
        assertTrue(failed.canFlashAgain, "an OTAFIX node was not offered another attempt")
        assertTrue(!failed.recovery.contains("USB"), failed.recovery)
    }

    @Test
    fun `a stock node is offered no retry once erased and an unchanged one is`() = runTest {
        // Both halves, so "Try again" can neither vanish nor reappear
        // where it would be refused by a latched bootloader.
        val pkg = packageOf(2048)
        val erased = assertIs<DfuProgress.Failed>(
            FirmwareUpdater(FakeScanner(DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU", rssi = -60))) {
                FakeBootloader(pkg.imageSize, goesQuietAfter = 400)
            }.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )
        assertTrue(!erased.canFlashAgain)
        assertTrue(Recovery.canFlashAgain(applicationErased = false, BootloaderKind.Stock))
        assertTrue(Recovery.canFlashAgain(applicationErased = true, BootloaderKind.Otafix))
        assertTrue(!Recovery.canFlashAgain(applicationErased = true, BootloaderKind.Unknown))
    }

    @Test
    fun `OTAFIX messages promise a restart and stock ones a cable`() {
        val failures = listOf(
            DfuFailure.StaleSession,
            DfuFailure.Stalled(400, 4096),
            DfuFailure.Rejected(LegacyDfu.OP_RECEIVE_FW, LegacyDfu.RESP_OPER_FAILED),
            DfuFailure.Rejected(LegacyDfu.OP_VALIDATE, LegacyDfu.RESP_CRC_ERROR),
        )
        for (f in failures) {
            val otafixText = Recovery.forFailure(f, true, BootloaderKind.Otafix)
            assertTrue(!otafixText.contains("USB"), otafixText)
            assertTrue(otafixText.endsWith(Recovery.AFTER_ERASE_OTAFIX), otafixText)
            assertTrue(Recovery.forFailure(f, true, BootloaderKind.Stock).contains("USB cable"))
        }
        assertTrue(!Recovery.interrupted(true, BootloaderKind.Otafix).contains("USB"))
    }

    @Test
    fun `a retry after the pool overflowed sends the packets further apart`() {
        // The rate is what overflows the receive pool, so the retry has
        // to slow the packets, not just shrink the batch.
        val first = DfuOptions()
        assertEquals(LegacyDfu.STOCK_BOOTLOADER_PACKET_DELAY_MS, first.slower().packetDelayMs)
        assertTrue(first.slower().slower().packetDelayMs > first.slower().packetDelayMs)
        assertTrue(Recovery.isTooFast(DfuFailure.Rejected(LegacyDfu.OP_RECEIVE_FW, 6)))
        assertTrue(!Recovery.isTooFast(DfuFailure.Rejected(LegacyDfu.OP_VALIDATE, 6)))
    }

    @Test
    fun `a restart that went down a dead link gets one more round`() = runTest {
        // The run on the test RAK, 2026-09-24, in order: Bluetooth cut at
        // 102 KB; the stall watchdog fired and the restart went down a
        // link that no longer existed; the retry found the node still
        // latched and was refused; the restart sent over THAT live
        // connection worked; and the third attempt — which this test
        // exists to guarantee — is what finishes the job.
        val pkg = packageOf(4096)
        val stalled = FakeBootloader(pkg.imageSize, goesQuietAfter = 400)
        val stillLatched = FakeBootloader(pkg.imageSize, latchedFromAnEarlierSession = true)
        val restarted = FakeBootloader(pkg.imageSize)
        val sequence = listOf(stalled, stillLatched, restarted)
        var call = 0
        val updater = FirmwareUpdater(FakeScanner(otafix)) { sequence[call++] }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, restarted.imageReceived.toByteArray())
        assertEquals(1, stillLatched.resets, "the latched node was not restarted on the live link")
        assertEquals(2, progress.count { it is DfuProgress.Retrying })
    }

    @Test
    fun `a write the stack refuses mid-image is recovered like any other failure`() = runTest {
        // Hardware, 2026-09-24: Bluetooth off at 119 KB surfaced as an
        // exception rather than a refusal or a stall, and the OTAFIX
        // recovery only ran for refusals — so it stopped and asked.
        val pkg = packageOf(4096)
        val refusing = FakeBootloader(pkg.imageSize, refusesWritesAfter = 400)
        val stillLatched = FakeBootloader(pkg.imageSize, latchedFromAnEarlierSession = true)
        val fresh = FakeBootloader(pkg.imageSize)
        val sequence = listOf(refusing, stillLatched, fresh)
        var call = 0
        val updater = FirmwareUpdater(FakeScanner(otafix)) { sequence[call++] }

        val progress = updater.update(pkg, DfuTarget.WaitingInBootloader()).toList()

        assertEquals(DfuProgress.Finished, progress.last())
        assertContentEquals(pkg.image, fresh.imageReceived.toByteArray())
    }

    @Test
    fun `the same refusal on a stock node is reported and never restarted`() = runTest {
        val pkg = packageOf(4096)
        val refusing = FakeBootloader(pkg.imageSize, refusesWritesAfter = 400)
        var connections = 0
        val updater = FirmwareUpdater(
            FakeScanner(DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU", rssi = -60)),
        ) { connections++; refusing }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(0, refusing.resets, "an erased stock node was restarted into USB mode")
        assertEquals(1, connections)
        assertEquals(Recovery.INTERRUPTED, failed.recovery)
        assertTrue(!failed.canFlashAgain)
    }

    @Test
    fun `an OTAFIX node not seen after its restart can still be flashed again`() = runTest {
        // Hardware, 2026-09-24: the rescan after a restart found nothing
        // (the phone's own Bluetooth was still starting). The node is
        // OTAFIX, so wherever the restart went it is waiting in
        // Bluetooth update mode — "Try again" has to be on offer.
        val pkg = packageOf(4096)
        val updater = FirmwareUpdater(FakeScanner(otafix, null)) {
            FakeBootloader(pkg.imageSize, refusesWritesAfter = 400)
        }

        val failed = assertIs<DfuProgress.Failed>(
            updater.update(pkg, DfuTarget.WaitingInBootloader()).toList().last(),
        )

        assertEquals(Recovery.NODE_NOT_FOUND, failed.recovery)
        assertTrue(failed.canFlashAgain)
    }
}
