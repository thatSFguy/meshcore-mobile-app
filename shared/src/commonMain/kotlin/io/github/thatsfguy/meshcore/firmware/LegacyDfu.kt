package io.github.thatsfguy.meshcore.firmware

/**
 * Nordic **legacy** (nRF5 SDK 11) DFU — the protocol the Adafruit nRF52
 * bootloader speaks, and therefore the one every nRF MeshCore node
 * updates over.
 *
 * Constants are taken from the bootloader's own source, not from a
 * client:
 * [`lib/sdk11/components/ble/ble_services/ble_dfu/ble_dfu.c`](https://github.com/adafruit/Adafruit_nRF52_Bootloader/blob/master/lib/sdk11/components/ble/ble_services/ble_dfu/ble_dfu.c)
 * for the op codes, `ble_dfu.h` for the UUIDs and response values, and
 * `lib/sdk11/…/bootloader_dfu/dfu_transport_ble.c` for what the peer
 * actually requires of each write.
 *
 * There are two BLE roles wearing the same service UUID:
 *
 * 1. **The companion firmware in app mode.** MeshCore v1.15+ registers
 *    Adafruit's `BLEDfu` beside the NUS (`SerialBLEInterface.cpp`).
 *    Its control point accepts exactly one thing — [ENTER_BOOTLOADER] —
 *    and only from a bonded, MITM-authenticated peer that has already
 *    subscribed to the characteristic. Writing it reboots the node.
 * 2. **The bootloader.** Everything else here. It advertises separately
 *    (see [DfuPeer]) and runs the transfer.
 */
object LegacyDfu {

    // --- GATT ------------------------------------------------------------

    const val SERVICE_UUID = "00001530-1212-efde-1523-785feabcd123"
    const val CONTROL_POINT_UUID = "00001531-1212-efde-1523-785feabcd123"
    const val PACKET_UUID = "00001532-1212-efde-1523-785feabcd123"
    const val REVISION_UUID = "00001534-1212-efde-1523-785feabcd123"

    // --- control point op codes (ble_dfu.c) ------------------------------

    const val OP_START_DFU = 1
    const val OP_RECEIVE_INIT = 2
    const val OP_RECEIVE_FW = 3
    const val OP_VALIDATE = 4
    const val OP_ACTIVATE_N_RESET = 5
    const val OP_SYS_RESET = 6
    const val OP_IMAGE_SIZE_REQ = 7
    const val OP_PKT_RCPT_NOTIF_REQ = 8
    const val OP_RESPONSE = 16
    const val OP_PKT_RCPT_NOTIF = 17

    /** Image-type bit field for [OP_START_DFU] (`dfu_types.h`). */
    const val IMAGE_TYPE_SD = 0x01
    const val IMAGE_TYPE_BL = 0x02
    const val IMAGE_TYPE_APP = 0x04

    /** Sub-codes of [OP_RECEIVE_INIT]. */
    const val INIT_RECEIVE = 0x00
    const val INIT_COMPLETE = 0x01

    /**
     * The only byte the app-mode `BLEDfu` control point accepts
     * (`BLEDfu.cpp`, `enum { START_DFU = 1 }`). Notifications must be
     * enabled on the characteristic first or the write is rejected with
     * `ATTERR_CPS_CCCD_CONFIG_ERROR` — see [CCCD_CONFIG_ERROR].
     */
    val ENTER_BOOTLOADER = byteArrayOf(1)

    /**
     * GATT error 0xFD, returned by the app-mode control point when the
     * client writes without subscribing first. Also what a node paired
     * before the DFU service existed returns, because the stale bond
     * carries no CCCD state for it — hence the "unpair and retry"
     * advice in MeshCore PR #2323.
     */
    const val CCCD_CONFIG_ERROR = 0xFD

    /** Abort a transfer and boot whatever image is already valid. */
    val SYSTEM_RESET = byteArrayOf(OP_SYS_RESET.toByte())

    // --- sizing ----------------------------------------------------------

    /**
     * 20 bytes is what fits the 23-byte ATT default, and what a stock
     * bootloader expects. OTAFIX negotiates higher, but the fallback has
     * to stay correct — a chunk the peer cannot take is a failed flash,
     * and this is not the place to be clever.
     */
    const val DEFAULT_CHUNK_SIZE = 20

    /**
     * Ceiling on a packet derived from the link's MTU.
     *
     * The largest ATT MTU the BLE 5.0 data-length extension gives is 247
     * (244 of payload), and the Adafruit bootloader's DFU pool buffer is
     * 600 bytes, so 244 keeps each write inside one ATT PDU. Ported from
     * Meshtastic's legacy-DFU transport, which carries the same value
     * and the same reasoning.
     */
    const val MAX_PACKET_SIZE = 244

    /**
     * DFU data writes must be a whole number of 32-bit words — the
     * bootloader answers `BLE_DFU_RESP_VAL_NOT_SUPPORTED` to anything
     * else — so a size derived from an MTU is floored to this.
     */
    const val PACKET_WORD_ALIGNMENT = 4

    /**
     * The packet size to use on a link whose largest no-response write
     * is [maxWriteLength] (ATT MTU − 3), or null when that is unknown.
     *
     * Taken from the negotiated MTU, never from the advertised name: the
     * MTU is the direct signal and a name is a guess about one.
     *
     * **What the 20-byte answer means.** It is the ATT default, which is
     * what remains when the MTU exchange did not happen — NOT a property
     * of a stock bootloader. That was claimed here for a while on
     * plausibility ("a peer that cannot take large writes never
     * negotiates a large MTU") and it is wrong: `dfu_transport_ble.c`
     * replies `MIN(client_rx_mtu, BLEGATT_ATT_MTU_MAX)`, rounded so that
     * MTU − 3 is a whole number of words, and the DFU Packet
     * characteristic is declared `max_len = BLEGATT_ATT_MTU_MAX - 3`. So
     * a transfer running at 20 bytes a packet against an Adafruit
     * bootloader is a **symptom** — the MTU request failed — and it
     * costs twelve writes for every one that was needed.
     */
    fun packetSizeFor(maxWriteLength: Int?): Int {
        val negotiated = maxWriteLength ?: return DEFAULT_CHUNK_SIZE
        val sized = negotiated.coerceIn(DEFAULT_CHUNK_SIZE, MAX_PACKET_SIZE)
        return sized - (sized % PACKET_WORD_ALIGNMENT)
    }

    /**
     * Pause between packet writes for a link whose packets are
     * [chunkSize] bytes, in milliseconds.
     *
     * **Derived from the measured link, not from a name**, and this time
     * the derivation is grounded rather than assumed: a peer that never
     * raised the ATT MTU above the 23-byte default is a stock bootloader
     * with a small `hci_mem_pool`, and one that negotiated its way up to
     * 244 has the buffers to match.
     *
     * The number is what a live ProMicro required. At the documented
     * receipt interval of 10 and no pause, the app pushed roughly 15 KB
     * in five seconds — about 150 packets a second — and the bootloader
     * answered `operation failed` every time, at 5 KB, 15 KB and 15 KB
     * again. Receipt notifications cannot prevent that on their own:
     * the peer sends one when a packet has been RECEIVED, not when it
     * has reached flash, so the backlog grows across batches no matter
     * how small the batch is. Halving the receipt interval was tried
     * first, because that is what Meshtastic's recovery profile does,
     * and it does not address the rate at all.
     *
     * 20 ms puts a 20-byte packet at about 1 KB/s, a third of the rate
     * that failed. A 400 KB image then takes around seven minutes, which
     * is the right trade: the alternative is not a faster update, it is
     * a node with no firmware and a drive out to it.
     */
    fun packetDelayFor(chunkSize: Int): Long =
        if (chunkSize <= DEFAULT_CHUNK_SIZE) STOCK_BOOTLOADER_PACKET_DELAY_MS else 0

    /** See [packetDelayFor]. */
    const val STOCK_BOOTLOADER_PACKET_DELAY_MS = 20L

    /**
     * True when [controlWrite] is one the peer reboots inside.
     *
     * `dfu_transport_ble.c` closes the transport and resets from within
     * the handler for both activate-and-reset and system-reset, so the
     * ATT response can never be sent and the write callback fails with
     * status 133. That failure is the node OBEYING. Reading it as a
     * refusal has now produced three separate false failures in this
     * codebase — the jump into the bootloader, the "restart it"
     * recovery, and a completed flash reported as an error on its very
     * last write.
     */
    fun rebootsInsideTheHandler(controlWrite: ByteArray): Boolean =
        controlWrite.size == 1 &&
            (
                controlWrite[0].toInt() == OP_ACTIVATE_N_RESET ||
                    controlWrite[0].toInt() == OP_SYS_RESET
                )

    /**
     * Packets between receipt notifications. The MeshCore FAQ's manual
     * procedure says 10 for RAK boards and 8 for the T114; 10 is the
     * default there and here. Zero disables flow control entirely,
     * which is how stock bootloaders are made to fail.
     */
    const val DEFAULT_PRN_INTERVAL = 10

    /**
     * Values of the revision characteristic (`…1534`), which is the one
     * reliable way to tell the two peers apart without comparing
     * addresses.
     *
     * `BLEDfu.cpp` writes `DFU_REV_APPMODE` (1) in the running
     * application; the bootloader exposes `DFU_REVISION` — major 0,
     * minor 8 — from `dfu_transport_ble.c`. A client that guesses wrong
     * either sends a jump to a bootloader (a malformed start-DFU, since
     * the bootloader reads the next byte as the image type) or starts a
     * transfer at an application that will ignore every opcode.
     */
    const val REVISION_APP_MODE = 0x0001
    const val REVISION_BOOTLOADER = 0x0008

    /** Response values (`ble_dfu_resp_val_t`). */
    const val RESP_SUCCESS = 1
    const val RESP_INVALID_STATE = 2
    const val RESP_NOT_SUPPORTED = 3
    const val RESP_DATA_SIZE = 4
    const val RESP_CRC_ERROR = 5
    const val RESP_OPER_FAILED = 6

    fun describeResponse(value: Int): String = when (value) {
        RESP_SUCCESS -> "success"
        RESP_INVALID_STATE -> "invalid state"
        RESP_NOT_SUPPORTED -> "not supported"
        RESP_DATA_SIZE -> "data size exceeds limit"
        RESP_CRC_ERROR -> "CRC error"
        RESP_OPER_FAILED -> "operation failed"
        else -> "unknown response $value"
    }

    fun describeProcedure(value: Int): String = when (value) {
        OP_START_DFU -> "start"
        OP_RECEIVE_INIT -> "init packet"
        OP_RECEIVE_FW -> "firmware image"
        OP_VALIDATE -> "validate"
        OP_PKT_RCPT_NOTIF_REQ -> "receipt-notification request"
        else -> "procedure $value"
    }
}

/** Where a session has got to. Drives the progress copy in the UI. */
enum class DfuStage {
    Idle,
    Starting,
    SendingInit,
    SendingImage,
    Validating,

    /**
     * The activate-and-reset write has been emitted. There is no
     * acknowledgement to wait for — the node reboots instead.
     */
    Done,
    Failed,
}

/** Why a session stopped. */
sealed class DfuFailure {
    /** The peer answered a procedure with something other than success. */
    data class Rejected(val procedure: Int, val result: Int) : DfuFailure() {
        override val message: String
            get() = "The radio rejected the ${LegacyDfu.describeProcedure(procedure)} step: " +
                LegacyDfu.describeResponse(result)
    }

    /**
     * A receipt notification disagreed with our own byte count. The
     * transfer has lost data; continuing would flash a corrupt image
     * and rely on the bootloader's hash to catch it.
     */
    data class ByteCountMismatch(val reported: Long, val sent: Int) : DfuFailure() {
        override val message: String
            get() = "The radio acknowledged $reported bytes but $sent were sent."
    }

    /** A notification that is not a response or a receipt notification. */
    data class Malformed(val notification: ByteArray) : DfuFailure() {
        override val message: String get() = "The radio sent an unreadable DFU notification."

        override fun equals(other: Any?): Boolean =
            other is Malformed && notification.contentEquals(other.notification)

        override fun hashCode(): Int = notification.contentHashCode()
    }

    /**
     * The peer would not begin because it is still part-way through an
     * earlier transfer.
     *
     * `dfu_start_pkt_handle` ends with
     * `if (DFU_STATE_IDLE != m_dfu_state) return NRF_ERROR_INVALID_STATE;`
     * ([`dfu_single_bank.c`](https://github.com/adafruit/Adafruit_nRF52_Bootloader/blob/master/lib/sdk11/components/libraries/bootloader_dfu/dfu_single_bank.c)),
     * and **nothing puts that state back**: the disconnect handler in
     * `dfu_transport_ble.c` only calls `advertising_start()`, and
     * `dfu_init()` — the one place that assigns `DFU_STATE_IDLE` — runs
     * once, when the bootloader boots.
     *
     * So one attempt that gets as far as the start step latches the node
     * out of IDLE for the whole life of that bootloader session, and
     * every reconnection afterwards is refused the same way. Only
     * [LegacyDfu.OP_SYS_RESET] — or the power — clears it, which is why
     * every abandoned transfer here sends one.
     */
    object StaleSession : DfuFailure() {
        override val message: String
            get() = "The node is still part-way through an earlier update and would not " +
                "start another."
    }

    /** A well-formed response that makes no sense at this point. */
    data class OutOfOrder(val procedure: Int, val stage: DfuStage) : DfuFailure() {
        override val message: String
            get() = "The radio answered the ${LegacyDfu.describeProcedure(procedure)} step " +
                "while the update was ${stage.name.lowercase()}."
    }

    /**
     * The peer went quiet.
     *
     * There is nothing in legacy DFU to ask a node whether it is still
     * there: the control point simply stops notifying, and the link can
     * stay nominally connected while it does. Without a bound on that
     * silence a transfer waits for ever behind a progress bar that will
     * never move again — which is exactly what a live ProMicro did after
     * 14,800 of 372,044 bytes.
     */
    data class Stalled(val bytesSent: Int, val total: Int) : DfuFailure() {
        override val message: String
            get() = "The node stopped answering after $bytesSent of $total bytes."
    }

    abstract val message: String
}

/** One thing the driver should do next. */
sealed class DfuAction {
    /** Write to the control point, with a response. */
    class WriteControl(val bytes: ByteArray) : DfuAction() {
        override fun equals(other: Any?): Boolean =
            other is WriteControl && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "WriteControl(${bytes.joinToString(",")})"
    }

    /** Write to the packet characteristic, WITHOUT a response. */
    class WritePacket(val bytes: ByteArray) : DfuAction() {
        override fun equals(other: Any?): Boolean =
            other is WritePacket && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "WritePacket(${bytes.size} bytes)"
    }

    /**
     * The image is flashed and the node is rebooting. It will not answer
     * the activate write — the reset IS the acknowledgement.
     */
    object Complete : DfuAction()

    data class Fail(val failure: DfuFailure) : DfuAction()
}

/**
 * The transfer, as a pure state machine: no GATT, no coroutines, no
 * clock. Feed it notifications, do what it returns.
 *
 * Flow control is the receipt notification and nothing else. Each batch
 * of [prnInterval] chunks is emitted, then the session waits for the
 * peer to acknowledge the running byte count before emitting the next —
 * which is also the only chance to notice that the peer and this side
 * disagree about how much arrived.
 */
class LegacyDfuSession(
    private val initPacket: ByteArray,
    private val image: ByteArray,
    private val chunkSize: Int = LegacyDfu.DEFAULT_CHUNK_SIZE,
    private val prnInterval: Int = LegacyDfu.DEFAULT_PRN_INTERVAL,
) {
    init {
        require(initPacket.isNotEmpty()) { "init packet is empty" }
        require(image.isNotEmpty()) { "firmware image is empty" }
        // The bootloader treats data packets as words and pads a short
        // final one itself, but a chunk size that is not a whole number
        // of words wastes a byte on every single write.
        require(chunkSize >= 4 && chunkSize % 4 == 0) { "chunk size must be a multiple of 4" }
        require(prnInterval >= 0) { "receipt-notification interval cannot be negative" }
    }

    var stage: DfuStage = DfuStage.Idle
        private set

    /** Bytes of [image] handed to the peer so far. */
    var bytesSent: Int = 0
        private set

    var failure: DfuFailure? = null
        private set

    val totalBytes: Int get() = image.size

    /** 0.0..1.0 over the image transfer, which is all the time there is. */
    val imageProgress: Double get() = bytesSent.toDouble() / image.size

    private var awaitingReceipt = false

    /**
     * True once the peer has accepted the start step — which is the
     * moment it erases the application bank.
     *
     * Read by the driver to decide whether abandoning the transfer may
     * safely reset the node. Before this, a reset boots the firmware
     * that is still there; after it, there is no firmware to boot. See
     * [abort].
     */
    var applicationErased: Boolean = false
        private set

    /**
     * Open the session. The receipt-notification interval is requested
     * before anything else: the peer resets its packet counter when it
     * is set, so setting it mid-transfer would desynchronise the very
     * thing it exists to check.
     */
    fun start(): List<DfuAction> {
        check(stage == DfuStage.Idle) { "session already started" }
        stage = DfuStage.Starting
        val actions = mutableListOf<DfuAction>()
        actions += DfuAction.WriteControl(
            byteArrayOf(LegacyDfu.OP_START_DFU.toByte(), LegacyDfu.IMAGE_TYPE_APP.toByte()),
        )
        actions += DfuAction.WritePacket(startSizes())
        return actions
    }

    /**
     * Abandon the transfer.
     *
     * The bootloader's DFU state survives a disconnection, so a session
     * left half-finished refuses every later attempt with
     * [DfuFailure.StaleSession] until something resets it — and this
     * write is the only thing that does.
     *
     * **But it is only safe while the node still has an application**,
     * which is why it returns nothing once [applicationErased] is set.
     * Reset a bootloader with an erased bank and it comes back in USB
     * mass-storage mode and stops advertising over Bluetooth
     * altogether: Adafruit's bootloader only brings up BLE DFU when it
     * was entered by an over-the-air request, and a plain reset is not
     * one. Verified on hardware 2026-08-14 — a transfer failed at
     * roughly 15 KB, this reset went out, and the node vanished from
     * every scan and reappeared as a `NICENANO` volume on a USB cable.
     *
     * So after the erase, the choice is between a node that MIGHT take
     * another transfer over the air and one that certainly needs
     * somebody to walk to it. Doing nothing is the better of the two,
     * even though it leaves the session latched.
     */
    fun abort(): List<DfuAction> {
        stage = DfuStage.Failed
        if (applicationErased) return emptyList()
        return listOf(DfuAction.WriteControl(LegacyDfu.SYSTEM_RESET))
    }

    fun onNotification(bytes: ByteArray): List<DfuAction> {
        if (stage == DfuStage.Done || stage == DfuStage.Failed) return emptyList()
        if (bytes.isEmpty()) return fail(DfuFailure.Malformed(bytes))
        return when (bytes[0].toInt() and 0xFF) {
            LegacyDfu.OP_RESPONSE -> onResponse(bytes)
            LegacyDfu.OP_PKT_RCPT_NOTIF -> onReceipt(bytes)
            else -> fail(DfuFailure.Malformed(bytes))
        }
    }

    private fun onResponse(bytes: ByteArray): List<DfuAction> {
        if (bytes.size < 3) return fail(DfuFailure.Malformed(bytes))
        val procedure = bytes[1].toInt() and 0xFF
        val result = bytes[2].toInt() and 0xFF
        if (result != LegacyDfu.RESP_SUCCESS) {
            // The start step is the one place where this response says
            // something about the SESSION rather than about the package,
            // and it is recoverable. See [DfuFailure.StaleSession].
            if (procedure == LegacyDfu.OP_START_DFU &&
                result == LegacyDfu.RESP_INVALID_STATE
            ) {
                return fail(DfuFailure.StaleSession)
            }
            return fail(DfuFailure.Rejected(procedure, result))
        }
        return when {
            stage == DfuStage.Starting && procedure == LegacyDfu.OP_START_DFU -> {
                stage = DfuStage.SendingInit
                // From here on the node has no application. Everything
                // that decides how to abandon a transfer turns on this.
                applicationErased = true
                buildList {
                    add(
                        DfuAction.WriteControl(
                            byteArrayOf(
                                LegacyDfu.OP_RECEIVE_INIT.toByte(),
                                LegacyDfu.INIT_RECEIVE.toByte(),
                            ),
                        ),
                    )
                    // The init packet is tens of bytes, but it is still
                    // the packet characteristic's MTU that bounds a
                    // write, so it chunks the same way the image does.
                    var offset = 0
                    while (offset < initPacket.size) {
                        val end = minOf(offset + chunkSize, initPacket.size)
                        add(DfuAction.WritePacket(initPacket.copyOfRange(offset, end)))
                        offset = end
                    }
                    add(
                        DfuAction.WriteControl(
                            byteArrayOf(
                                LegacyDfu.OP_RECEIVE_INIT.toByte(),
                                LegacyDfu.INIT_COMPLETE.toByte(),
                            ),
                        ),
                    )
                }
            }

            stage == DfuStage.SendingInit && procedure == LegacyDfu.OP_RECEIVE_INIT -> {
                stage = DfuStage.SendingImage
                // The receipt interval is set HERE — after the init
                // packet is accepted and immediately before the image
                // step — because that is where Nordic's own legacy
                // implementation puts it, and where Meshtastic's does.
                //
                // It used to go out ahead of START_DFU, which is a
                // different thing to ask of a bootloader that has not
                // been told what it is receiving yet, and which puts the
                // request on the far side of a full-bank erase lasting
                // 30-50 seconds. Neither reference implementation does
                // that, and this app has never once completed a transfer
                // on hardware that Meshtastic flashes reliably — so the
                // sequence matches theirs rather than being a variation
                // on it.
                val prn = if (prnInterval > 0) {
                    listOf(
                        DfuAction.WriteControl(
                            byteArrayOf(
                                LegacyDfu.OP_PKT_RCPT_NOTIF_REQ.toByte(),
                                (prnInterval and 0xFF).toByte(),
                                ((prnInterval shr 8) and 0xFF).toByte(),
                            ),
                        ),
                    )
                } else {
                    emptyList()
                }
                prn + DfuAction.WriteControl(byteArrayOf(LegacyDfu.OP_RECEIVE_FW.toByte())) +
                    nextBatch()
            }

            stage == DfuStage.SendingImage && procedure == LegacyDfu.OP_RECEIVE_FW -> {
                if (bytesSent < image.size) {
                    // The peer declared the image complete while we
                    // still hold bytes. Never activate on that.
                    return fail(DfuFailure.ByteCountMismatch(bytesSent.toLong(), image.size))
                }
                stage = DfuStage.Validating
                listOf(DfuAction.WriteControl(byteArrayOf(LegacyDfu.OP_VALIDATE.toByte())))
            }

            stage == DfuStage.Validating && procedure == LegacyDfu.OP_VALIDATE -> {
                stage = DfuStage.Done
                listOf(
                    DfuAction.WriteControl(byteArrayOf(LegacyDfu.OP_ACTIVATE_N_RESET.toByte())),
                    DfuAction.Complete,
                )
            }

            // The peer acknowledges the receipt-notification request
            // itself on some builds; it changes nothing.
            procedure == LegacyDfu.OP_PKT_RCPT_NOTIF_REQ -> emptyList()

            else -> fail(DfuFailure.OutOfOrder(procedure, stage))
        }
    }

    private fun onReceipt(bytes: ByteArray): List<DfuAction> {
        if (bytes.size < 5) return fail(DfuFailure.Malformed(bytes))
        val reported = (bytes[1].toLong() and 0xFF) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            ((bytes[3].toLong() and 0xFF) shl 16) or
            ((bytes[4].toLong() and 0xFF) shl 24)
        if (reported != bytesSent.toLong()) {
            return fail(DfuFailure.ByteCountMismatch(reported, bytesSent))
        }
        if (!awaitingReceipt) return emptyList()
        awaitingReceipt = false
        return nextBatch()
    }

    /**
     * Emit up to [prnInterval] chunks, then stop and wait to be
     * acknowledged. An interval of zero means no flow control at all,
     * so the whole remaining image goes out in one go.
     */
    private fun nextBatch(): List<DfuAction> {
        val actions = mutableListOf<DfuAction>()
        var chunks = 0
        while (bytesSent < image.size && (prnInterval == 0 || chunks < prnInterval)) {
            val end = minOf(bytesSent + chunkSize, image.size)
            actions += DfuAction.WritePacket(image.copyOfRange(bytesSent, end))
            bytesSent = end
            chunks++
        }
        // Only wait if the peer still owes us a notification: a final
        // partial batch never reaches the target count, and the
        // completion response arrives instead.
        awaitingReceipt = prnInterval > 0 && chunks == prnInterval && bytesSent < image.size
        return actions
    }

    private fun fail(f: DfuFailure): List<DfuAction> {
        stage = DfuStage.Failed
        failure = f
        return listOf(DfuAction.Fail(f))
    }

    /**
     * The 12-byte start packet: SoftDevice, bootloader and application
     * image sizes, u32 LE, in that order (`dfu_transport_ble.c`
     * `SD_IMAGE_SIZE_OFFSET`/`BL`/`APP` = 0/4/8). The peer requires
     * **exactly** three words — a four-byte "app size only" packet, as
     * some clients send, is answered `NOT_SUPPORTED`.
     */
    private fun startSizes(): ByteArray {
        val out = ByteArray(12)
        var size = image.size
        for (i in 0 until 4) {
            out[8 + i] = (size and 0xFF).toByte()
            size = size ushr 8
        }
        return out
    }
}
