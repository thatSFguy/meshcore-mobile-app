package io.github.thatsfguy.meshcore.firmware

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A GATT connection to a node in bootloader DFU mode. One instance per
 * transfer; the peer reboots at the end of it.
 */
interface DfuGattClient {
    /**
     * Everything the control point notifies, in order.
     *
     * **Buffered from [subscribeToControlPoint] onward.** A notification
     * the peer sends before this flow is collected must still arrive:
     * the answer to the start step can land while the caller is still
     * emitting the writes that provoked it, and an implementation that
     * drops it — a replay-less `SharedFlow`, say — turns a working
     * transfer into "the node stopped responding part-way through".
     * Single consumer; a channel is the natural shape.
     */
    val notifications: Flow<ByteArray>

    /** Connect and discover the DFU service. */
    suspend fun connect()

    /**
     * Subscribe to the control point. Not optional and not merely
     * polite — the peer answers every procedure this way, and in app
     * mode it refuses the jump write outright without it.
     */
    suspend fun subscribeToControlPoint()

    /** Write to the control point and wait for the write to complete. */
    suspend fun writeControl(bytes: ByteArray)

    /** Write to the packet characteristic, without a response. */
    suspend fun writePacket(bytes: ByteArray)

    /**
     * Ask the link for the shortest connection interval it will give.
     *
     * Not a nicety. A default Android connection interval is 30–50 ms,
     * and at that spacing the stack packs several 20-byte writes into
     * each connection event — so the bootloader receives the whole
     * receipt window as two or three bursts, faster than its flash path
     * drains `hci_mem_pool`, and answers `operation failed`. At 7.5 ms
     * the same packets arrive spread out, and the transfer is also an
     * order of magnitude quicker.
     *
     * Nordic's own `LegacyDfuImpl` requests it, and so does Meshtastic's
     * legacy-DFU implementation, whose note reads: "Default Android
     * intervals (~30-50 ms) starve the link during sustained DFU and
     * trigger LSTO."
     *
     * Best effort by definition — the peer can refuse — so the return
     * value is for the log, not for a decision.
     */
    suspend fun requestHighThroughput(): Boolean = false

    /**
     * The largest no-response write this link will take (ATT MTU − 3),
     * or null when it cannot be determined.
     *
     * Feeds [LegacyDfu.packetSizeFor]. Not a preference — a bootloader
     * that cannot accept large DFU writes never negotiates a large MTU,
     * so this is the capability signal itself.
     */
    suspend fun maxWriteLength(): Int? = null

    /**
     * The DFU revision characteristic, or null if it cannot be read.
     *
     * [LegacyDfu.REVISION_APP_MODE] means the peer is still running its
     * own firmware and needs the jump; [LegacyDfu.REVISION_BOOTLOADER]
     * means it is already waiting and must not be sent one.
     */
    suspend fun readDfuRevision(): Int? = null

    suspend fun close()
}

/** Scans for a node advertising in bootloader DFU mode. */
interface DfuScanner {
    suspend fun findBootloader(expectation: BootloaderExpectation, timeoutMs: Long): DfuPeer?
}

/**
 * A transport that can ask the connected radio to reboot into its
 * bootloader. Only BLE can: the app-mode DFU service is a BLE service,
 * and USB/TCP-attached nodes are updated by other means entirely.
 */
interface BootloaderCapableTransport {
    /**
     * True when the connected radio actually exposes the app-mode DFU
     * service.
     *
     * This is a fact about the link in hand, not a guess from the board
     * name: an ESP32 board, an nRF52 on companion firmware older than
     * v1.15, and a USB or TCP link all answer false, and each of those
     * would otherwise send someone into a screen that can only end in
     * "not supported".
     */
    fun offersFirmwareUpdates(): Boolean

    /**
     * Subscribe to the app-mode DFU control point and write
     * [LegacyDfu.ENTER_BOOTLOADER]. The radio disconnects as a result —
     * that is success, not failure.
     */
    suspend fun requestBootloaderReboot()
}

/**
 * What is being updated, and how it gets into the bootloader.
 *
 * Three states, not two, because `start ota` on a repeater does **not**
 * put it in the bootloader — see [AdvertisingForUpdate]. Treating that
 * node as if it were already in DFU mode is a hang: the app-mode
 * control point accepts one byte and ignores every DFU opcode sent to
 * it.
 */
sealed class DfuTarget {
    /**
     * The radio this app is connected to over BLE. We can write the
     * jump over the link we already hold, and we know the address its
     * bootloader will use.
     */
    data class ConnectedRadio(
        val transport: BootloaderCapableTransport,
        val address: String?,
        val boardName: String?,
    ) : DfuTarget()

    /**
     * A node that has been sent `start ota` over the mesh.
     *
     * It is **still running its own firmware** and still repeating.
     * `NRF52Board::startOTAUpdate` brings up a Bluefruit stack inside
     * the running application, names it after the board (`RAK4631_OTA`,
     * `T114_OTA`, and — with a space — `Meshtiny OTA`), registers the
     * app-mode DFU service and advertises indefinitely. Nothing reboots
     * until a client writes [LegacyDfu.ENTER_BOOTLOADER] to it.
     *
     * Unlike the companion's, this service is registered with no
     * `SECMODE_ENC_WITH_MITM`, so no bond is needed to reach it.
     *
     * [otaAddress] is the MAC the node reports back in its own reply to
     * `start ota` (`"OK - mac: …"`). With it there is no guessing
     * between several nodes on a bench.
     */
    data class AdvertisingForUpdate(
        val otaAddress: String? = null,
        val boardName: String? = null,
    ) : DfuTarget()

    /**
     * A node genuinely in the bootloader already: one put there with its
     * buttons, or one being retried after a transfer that failed part
     * way. There is nothing left to reboot.
     */
    data class WaitingInBootloader(
        val addressHint: String? = null,
        val boardName: String? = null,
    ) : DfuTarget()

    val boardNameOrNull: String?
        get() = when (this) {
            is ConnectedRadio -> boardName
            is AdvertisingForUpdate -> boardName
            is WaitingInBootloader -> boardName
        }
}

data class DfuOptions(
    /**
     * Bytes per packet, or null to take it from the link.
     *
     * Null is the default and the right answer: [LegacyDfu.packetSizeFor]
     * derives it from the negotiated MTU, which is self-gating — a
     * bootloader that cannot take large writes never offers a large MTU.
     * On a stock Adafruit bootloader that means the 20-byte floor; on
     * one that negotiates fully it is 244, which is twelve times the
     * throughput on the same link.
     */
    val chunkSize: Int? = null,
    val receiptInterval: Int = LegacyDfu.DEFAULT_PRN_INTERVAL,
    /**
     * How long to wait for the bootloader to advertise. A node erases
     * flash before it starts advertising on some bootloaders, so this
     * is seconds, not milliseconds.
     */
    val scanTimeoutMs: Long = 30_000,
    /**
     * Proceed with a transfer over a link that is likely to drop.
     *
     * Off by default: the cost of being wrong is a node with no
     * firmware. The user can override it, having been told what they
     * are overriding.
     */
    val allowWeakSignal: Boolean = false,
    /**
     * Pause after each packet write, in milliseconds.
     *
     * The bootloader copies every packet into a small `hci_mem_pool`
     * and flushes it to flash asynchronously
     * (`dfu_transport_ble.c`, `app_data_process`). When the pool fills,
     * `hci_mem_pool_rx_produce` returns `NRF_ERROR_NO_MEM`, which
     * `nrf_err_code_translate` reports as the catch-all
     * [LegacyDfu.RESP_OPER_FAILED] — a transfer that dies a few hundred
     * bytes in with "operation failed".
     *
     * Receipt notifications do not prevent this on their own: the
     * bootloader sends one when a packet has been RECEIVED, not when it
     * has reached flash, so the write queue can keep backing up across
     * batches. Slowing the packets themselves is what drains it.
     */
    val packetDelayMs: Long = 0,
) {
    /**
     * True when [failure] is the bootloader saying the packets came too
     * fast.
     *
     * `NRF_ERROR_NO_MEM` out of the receive pool is translated to the
     * catch-all [LegacyDfu.RESP_OPER_FAILED], so the status alone is
     * ambiguous — but during the image step, on this bootloader, it
     * means one thing. Nordic's own legacy implementation reads it the
     * same way and prescribes the same remedy: reduce the receipt
     * interval.
     */
    fun tooFast(failure: DfuFailure): Boolean =
        failure is DfuFailure.Rejected &&
            failure.procedure == LegacyDfu.OP_RECEIVE_FW &&
            failure.result == LegacyDfu.RESP_OPER_FAILED

    /**
     * The same transfer at the recovery pace.
     *
     * Half the receipt window, so fewer packets are in flight between
     * checkpoints. No packet delay is added: Meshtastic's legacy DFU has
     * a RECOVERY profile that is exactly this — `prnInterval` 5 against a
     * normal 10 — and nothing else, and the thing that actually keeps a
     * stock bootloader fed is the short connection interval
     * ([DfuGattClient.requestHighThroughput]), not a sleep in the
     * sender. Never below one packet per receipt, which is as slow as
     * the protocol goes.
     */
    fun gentler(): DfuOptions = copy(
        receiptInterval = maxOf(1, receiptInterval / 2),
        // And slower per packet, which is the part that actually
        // addresses `operation failed`. This used to halve the receipt
        // interval and nothing else, on the strength of Meshtastic's
        // recovery profile being exactly that — but a smaller batch does
        // not reduce the RATE, and the rate is what overflows a
        // bootloader's receive pool. Proven on hardware: the interval
        // stepped down to 5 and the node refused the image step just the
        // same. Zero means "whatever the link implies"; see
        // [LegacyDfu.packetDelayFor].
        packetDelayMs = if (packetDelayMs > 0) {
            packetDelayMs * 2
        } else {
            LegacyDfu.STOCK_BOOTLOADER_PACKET_DELAY_MS * 2
        },
    )

    companion object {
        /**
         * Meshtastic's RECOVERY profile, and the value this steps down
         * to from the documented default of 10.
         */
        const val RECOVERY_PRN_INTERVAL = 5
    }
}

/** Where an update has got to. Everything the UI shows comes from here. */
sealed class DfuProgress {
    object Preparing : DfuProgress()

    /** The jump write has gone out; the radio is rebooting. */
    object EnteringBootloader : DfuProgress()

    object FindingNode : DfuProgress()

    data class Connecting(val peer: DfuPeer) : DfuProgress()

    /**
     * The node was found, but the link is too weak to risk the transfer.
     *
     * Emitted INSTEAD of starting one. The bootloader erases the
     * application before it writes the replacement, so a transfer that
     * dies half way costs a visit to the node — and at the point where
     * that becomes likely, the useful thing to do is say so rather than
     * begin.
     */
    data class SignalTooWeak(val peer: DfuPeer, val rssi: Int) : DfuProgress()

    data class Transferring(val bytesSent: Int, val totalBytes: Int) : DfuProgress() {
        val fraction: Double get() = if (totalBytes == 0) 0.0 else bytesSent.toDouble() / totalBytes
    }

    object Verifying : DfuProgress()

    /** Flashed and rebooting into the new image. */
    object Finished : DfuProgress()

    data class Failed(val message: String, val recovery: String) : DfuProgress()

    /**
     * The attempt failed and another is starting by itself.
     *
     * Emitted so the reason is **said out loud**. A failure that the app
     * recovers from used to emit nothing at all — the log jumped
     * straight from a byte count to "scanning for the node", and the one
     * fact worth having, which is what went wrong, existed only inside a
     * local variable. Three transfers were diagnosed on hardware without
     * it, and the difference between "the node could not keep up" and
     * "the node went quiet" had to be inferred from how many seconds
     * passed.
     */
    data class Retrying(val reason: String, val receiptInterval: Int) : DfuProgress()
}

/**
 * Drives one firmware update from a parsed package to a rebooting node.
 *
 * The protocol lives in [LegacyDfuSession]; this is the part that has to
 * touch a radio, so it is kept as thin as it can be and takes its BLE
 * through interfaces so the whole sequence can be run against fakes.
 */
/** See [FirmwareUpdater]. */
private const val SUBSCRIPTION_SETTLE_MS = 500L

/** How long a node is given to reboot before it is looked for. */
internal const val REBOOT_SETTLE_MS = 5_000L

/**
 * How long to look for a node after resetting it.
 *
 * Longer than a first scan on purpose: the peer has to reboot, run its
 * bootloader init and start advertising again, and it is being looked
 * for by an exact address that is silent until it does.
 */
internal const val REBOOT_SCAN_TIMEOUT_MS = 60_000L

/**
 * How long a step may go without a word from the peer.
 *
 * Ported from Meshtastic's legacy DFU, which carries the same three
 * budgets and the same reason for the first one being so much larger:
 * a stock single-bank bootloader erases the entire application bank on
 * START — hundreds of pages — and the SoftDevice time-slices each page
 * erase against radio events, stretching it to 30-50 seconds. A shorter
 * cap there aborts healthy transfers.
 */
/**
 * Receipt notifications between re-requests of the short connection
 * interval.
 *
 * `requestConnectionPriority` is advisory: the stack may refuse it, and
 * several are known to let it lapse under load or when another link
 * becomes active. There is no API to read the interval back, so the
 * only defence is to keep asking. At the documented interval of 10
 * packets this lands roughly every few seconds of transfer, which is
 * cheap — the request is a local call and idempotent.
 */
internal const val PRIORITY_REFRESH_BATCHES = 25

internal fun stallBudgetMs(stage: DfuStage): Long = when (stage) {
    DfuStage.Starting -> 90_000L
    DfuStage.Validating -> 60_000L
    else -> 30_000L
}

/**
 * How long a single GATT write may go unconfirmed before the link is
 * declared dead.
 *
 * A **backstop against an infinite hang, and nothing else** — which is
 * why it is far larger than any step budget and must stay that way.
 * Set at 10 s to begin with, it preempted the very timeouts it was
 * added to support: on a live ProMicro the bootloader answered the
 * start step after 19 seconds of flash erase, took the next control
 * write, and then did not confirm a 14-byte init packet within 10 —
 * so a transfer that [stallBudgetMs] would have given 30 seconds was
 * failed at 10 by the backstop instead.
 *
 * A peer erasing its application bank is starved of radio time by its
 * own SoftDevice, and an unconfirmed write during that window says
 * "busy", not "gone". Which of the two it is, is [stallBudgetMs]'s
 * decision to make.
 */
internal const val WRITE_CONFIRMATION_TIMEOUT_MS = 120_000L

class FirmwareUpdater(
    private val scanner: DfuScanner,
    private val connect: suspend (DfuPeer) -> DfuGattClient,
) {
    fun update(
        pkg: DfuPackage,
        target: DfuTarget,
        options: DfuOptions = DfuOptions(),
    ): Flow<DfuProgress> = flow {
        emit(DfuProgress.Preparing)

        val expectation = when (target) {
            is DfuTarget.ConnectedRadio -> {
                emit(DfuProgress.EnteringBootloader)
                try {
                    target.transport.requestBootloaderReboot()
                } catch (e: Exception) {
                    emit(rebootFailure(e))
                    return@flow
                }
                BootloaderExpectation(target.address, target.boardName)
            }

            is DfuTarget.AdvertisingForUpdate -> {
                // The node is still running its own firmware with BLE
                // switched on. Reach it, write the jump, and only then
                // is there a bootloader to look for.
                emit(DfuProgress.FindingNode)
                val advertising = scanner.findBootloader(
                    // The node's own `start ota` reply carries its MAC,
                    // which identifies it in BOTH the states it could be
                    // in by the time the scan runs — advertising from
                    // its own firmware on that address, or already in
                    // the bootloader one higher. See
                    // [BootloaderPeer.matches].
                    BootloaderExpectation(
                        companionAddress = target.otaAddress,
                        nameHint = target.boardName,
                    ),
                    options.scanTimeoutMs,
                )
                if (advertising == null) {
                    emit(
                        DfuProgress.Failed(
                            "The node is not advertising for an update.",
                            Recovery.NOT_ADVERTISING,
                        ),
                    )
                    return@flow
                }

                // Which of the two states is it in?
                //
                // A node that has only run `start ota` advertises on its
                // OWN address and needs the jump. One already in its
                // bootloader advertises on that address + 1 and must NOT
                // be sent the jump: the bootloader reads the byte after
                // the op code as the image type, so a one-byte `0x01`
                // there is a malformed start-DFU, not a reboot request.
                val bootloaderAddress = target.otaAddress
                    ?.let { BootloaderPeer.expectedAddress(it) }
                var alreadyInBootloader = bootloaderAddress != null &&
                    advertising.address.equals(bootloaderAddress, ignoreCase = true)

                // With no announced address to compare against — a node
                // found by scanning alone, which is the case whenever
                // this is reached from "a node is already in update
                // mode" — ask the peer instead of guessing.
                if (bootloaderAddress == null) {
                    val probe = runCatching {
                        connect(advertising).also { it.connect() }
                    }.getOrNull()
                    val revision = probe?.let { runCatching { it.readDfuRevision() }.getOrNull() }
                    if (revision == LegacyDfu.REVISION_BOOTLOADER) {
                        // Keep this connection and transfer on it.
                        //
                        // Closing it here and reconnecting was a race
                        // against the peer's own teardown: a bootloader
                        // takes ONE connection at a time and needs a
                        // moment to drop the old one and re-advertise, so
                        // the immediate reconnect failed with status 0,
                        // fell back to autoConnect, and produced a link
                        // that was nominally up and answered nothing. On
                        // hardware that read as "the node stopped
                        // responding part-way through" four milliseconds
                        // after the transfer began — a node that had, in
                        // fact, been perfectly reachable a second
                        // earlier, on a connection this app threw away.
                        return@flow runTransfer(pkg, advertising, options, this, connected = probe)
                    }
                    runCatching { probe?.close() }
                }

                if (alreadyInBootloader) {
                    // Nothing to reboot; go straight to the transfer.
                    return@flow runTransfer(pkg, advertising, options, this)
                }

                emit(DfuProgress.EnteringBootloader)
                try {
                    val jump = connect(advertising)
                    try {
                        jump.connect()
                        jump.subscribeToControlPoint()
                        jump.writeExpectingAReboot(LegacyDfu.ENTER_BOOTLOADER)
                    } finally {
                        runCatching { jump.close() }
                    }
                } catch (e: Exception) {
                    emit(rebootFailure(e))
                    return@flow
                }
                BootloaderExpectation(advertising.address, target.boardName)
            }

            is DfuTarget.WaitingInBootloader ->
                BootloaderExpectation(target.addressHint, target.boardName)
        }

        emit(DfuProgress.FindingNode)
        val peer = scanner.findBootloader(expectation, options.scanTimeoutMs)
        if (peer == null) {
            emit(
                DfuProgress.Failed(
                    "No node in firmware-update mode was found nearby.",
                    Recovery.NODE_NOT_FOUND,
                ),
            )
            return@flow
        }
        runTransfer(pkg, peer, options, this)
    }

    /**
     * Connect to a peer that is in its bootloader and send the image.
     *
     * Separate from [update] because there are two ways to arrive here:
     * a node this app rebooted, and one that was already waiting. The
     * transfer itself is identical, and the difference must not be able
     * to grow into two copies of it.
     */
    private suspend fun runTransfer(
        pkg: DfuPackage,
        peer: DfuPeer,
        options: DfuOptions,
        out: kotlinx.coroutines.flow.FlowCollector<DfuProgress>,
        /**
         * False on the second pass, after a node that was latched out of
         * its previous session has been reset. One retry, so a peer that
         * answers `invalid state` for some other reason cannot become a
         * loop of reboots.
         */
        allowRestart: Boolean = true,
        /**
         * An already-connected client to transfer on, rather than
         * opening a new one.
         *
         * Used when the peer has just been probed for its DFU revision:
         * the bootloader accepts one connection at a time, so handing
         * the live one straight to the transfer avoids a reconnect that
         * races the teardown of the connection being replaced.
         */
        connected: DfuGattClient? = null,
        /**
         * True when an EARLIER attempt already got past the start step,
         * so this node has no application even though this session has
         * not erased anything itself.
         *
         * Without it the fix in [LegacyDfuSession.abort] has a hole
         * exactly one attempt wide, and hardware fell straight into it:
         * the first attempt erased the bank and correctly declined to
         * reset, the retry was refused with `invalid state` before it
         * sent a byte — so ITS session had erased nothing, happily wrote
         * the reset, and put the node back on a USB cable.
         */
        applicationAlreadyErased: Boolean = false,
    ) {
        if (!options.allowWeakSignal && !peer.signalIsAdequate) {
            out.emit(DfuProgress.SignalTooWeak(peer, peer.rssi ?: 0))
            return
        }
        out.emit(DfuProgress.Connecting(peer))
        val client = try {
            (connected ?: connect(peer).also { it.connect() }).also {
                it.subscribeToControlPoint()
                // Let the subscription settle before writing anything.
                // Meshtastic's legacy DFU waits the same 500 ms here;
                // a control write issued in the same breath as the CCCD
                // write can be answered before the peer has finished
                // enabling notifications, and the answer is lost.
                delay(SUBSCRIPTION_SETTLE_MS)
            }
        } catch (e: Exception) {
            out.emit(
                DfuProgress.Failed(
                    e.message ?: "Could not connect to the node in firmware-update mode.",
                    Recovery.CONNECT_FAILED,
                ),
            )
            return
        }

        // Set when the attempt is worth making again, to the options it
        // should be made with. The retry happens after this connection
        // is closed — the node is rebooting, so there is nothing left to
        // hold.
        var retryWith: DfuOptions? = null
        // Whether the node was actually told to restart. Not the same
        // as "we tried to abandon the transfer": once the application
        // bank is erased, abandoning it deliberately writes nothing.
        var wasReset = false
        // Kept out here because the retry happens after the session is
        // out of scope, and the reason is the whole point of saying so.
        var retryReason: String? = null
        try {
            val chunkSize = options.chunkSize
                ?: LegacyDfu.packetSizeFor(client.maxWriteLength())
            // An explicit pause wins; otherwise it comes from what the
            // link turned out to be. See [LegacyDfu.packetDelayFor].
            val packetDelayMs = if (options.packetDelayMs > 0) {
                options.packetDelayMs
            } else {
                LegacyDfu.packetDelayFor(chunkSize)
            }
            val session = LegacyDfuSession(
                initPacket = pkg.initPacket,
                image = pkg.image,
                chunkSize = chunkSize,
                prnInterval = options.receiptInterval,
            )
            var stalledAfter: Int? = null
            var batchesSincePriority = 0
            if (!performOrStall(client, session.start(), packetDelayMs, session.stage)) {
                stalledAfter = 0
            }
            out.emit(DfuProgress.Transferring(0, pkg.imageSize))

            // Every step is bounded. A legacy DFU has no way to ask a
            // peer whether it is still there — the notifications simply
            // stop — so an unbounded wait is a progress bar that runs
            // for ever. Seen on hardware: a transfer ran cleanly to
            // 14,800 of 372,044 bytes and then waited on a packet
            // receipt that never came, showing nothing but the last
            // number it had reached, with no way out but killing the
            // app. Meshtastic's legacy DFU bounds each step for the
            // same reason.
            val conclusion = if (stalledAfter != null) null else coroutineScope {
                val inbox = Channel<ByteArray>(Channel.UNLIMITED)
                val pump = launch {
                    client.notifications.collect { inbox.send(it) }
                    inbox.close()
                }
                var result: List<DfuAction>? = null
                try {
                    while (true) {
                        // The budget is per step, because the steps are
                        // not alike: a stock single-bank bootloader
                        // erases the whole application bank before it
                        // answers the start, and the SoftDevice
                        // time-slices each page erase against radio
                        // events, so 30-50 s of silence there is health.
                        val delivery = withTimeoutOrNull(stallBudgetMs(session.stage)) {
                            inbox.receiveCatching()
                        }
                        if (delivery == null) {
                            stalledAfter = session.bytesSent
                            break
                        }
                        // Closed rather than timed out: the link dropped,
                        // which is a different failure with a different
                        // message.
                        val bytes = delivery.getOrNull() ?: break
                        val wasBeforeTheImage = session.stage != DfuStage.SendingImage
                        val actions = session.onNotification(bytes)
                        // Immediately before the image starts moving, and
                        // not a moment earlier — and then again as it
                        // runs.
                        //
                        // It used to be requested right after connecting
                        // — which is on the far side of a full-bank erase
                        // that takes 30-50 seconds, and Android stacks
                        // are known to let a priority request lapse. Both
                        // Nordic's legacy implementation and Meshtastic's
                        // ask for it here, after the init packet is
                        // accepted, with the stream about to begin.
                        //
                        // Asking once is still a bet on the stack holding
                        // it for the whole transfer, which on a 372 KB
                        // image is minutes. It is a cheap, idempotent
                        // request, so it is re-asserted every
                        // [PRIORITY_REFRESH_BATCHES] receipts: the cost
                        // of being wrong about how long it lasts is a
                        // transfer that quietly slows to a stop, and
                        // there is no way to read the interval back to
                        // find out.
                        if (wasBeforeTheImage && session.stage == DfuStage.SendingImage) {
                            batchesSincePriority = 0
                            client.requestHighThroughput()
                        } else if (session.stage == DfuStage.SendingImage &&
                            ++batchesSincePriority >= PRIORITY_REFRESH_BATCHES
                        ) {
                            batchesSincePriority = 0
                            client.requestHighThroughput()
                        }
                        // Bounded, because a write is not a safe place to
                        // wait for ever either. See [performOrStall].
                        if (!performOrStall(
                                client,
                                actions,
                                packetDelayMs,
                                session.stage,
                            )
                        ) {
                            stalledAfter = session.bytesSent
                            break
                        }
                        when (session.stage) {
                            DfuStage.SendingImage ->
                                out.emit(
                                    DfuProgress.Transferring(session.bytesSent, pkg.imageSize),
                                )

                            DfuStage.Validating -> out.emit(DfuProgress.Verifying)
                            else -> {}
                        }
                        if (actions.any { it is DfuAction.Complete || it is DfuAction.Fail }) {
                            result = actions
                            break
                        }
                    }
                } finally {
                    pump.cancel()
                }
                result
            }

            val failure = session.failure
                ?: stalledAfter?.let { DfuFailure.Stalled(it, pkg.imageSize) }
            when {
                failure != null -> {
                    // Hand the node back in a state it can start from.
                    // The bootloader keeps the DFU state of an abandoned
                    // transfer until it reboots, so without this every
                    // later attempt — including the "retry more slowly"
                    // this app itself recommends — is refused before it
                    // begins. The write is not acknowledged: the peer
                    // reboots inside the handler.
                    retryReason = failure.message
                    val abort =
                        if (applicationAlreadyErased) emptyList() else session.abort()
                    wasReset = abort.isNotEmpty()
                    runCatching { perform(client, abort) }
                    when {
                        failure is DfuFailure.StaleSession && allowRestart ->
                            retryWith = options

                        // The one failure with a documented remedy.
                        // Nordic's own legacy implementation reads status
                        // 6 during the image step as "data sent too fast
                        // — reduce PRN", and reducing it is something
                        // this app can do without asking. Making the
                        // operator find a button for it means the node
                        // sits erased in the meantime, and every attempt
                        // costs another full flash erase.
                        options.tooFast(failure) && allowRestart ->
                            retryWith = options.gentler()

                        // A node that went quiet mid-stream. Meshtastic
                        // switches to its RECOVERY profile after a
                        // mid-stream drop for the same reason: whatever
                        // the peer could not keep up with, it is worth
                        // asking for less of it before giving up.
                        failure is DfuFailure.Stalled && allowRestart ->
                            retryWith = options.gentler()

                        else -> out.emit(
                            DfuProgress.Failed(failure.message, Recovery.forFailure(failure)),
                        )
                    }
                }

                conclusion == null -> out.emit(
                    DfuProgress.Failed(
                        "The node stopped responding part-way through the update.",
                        Recovery.INTERRUPTED,
                    ),
                )

                else -> out.emit(DfuProgress.Finished)
            }
        } catch (e: Exception) {
            out.emit(
                DfuProgress.Failed(
                    e.message ?: "The update failed.",
                    Recovery.INTERRUPTED,
                ),
            )
        } finally {
            runCatching { client.close() }
        }

        val retryOptions = retryWith ?: return
        out.emit(
            DfuProgress.Retrying(
                retryReason ?: "The transfer did not finish.",
                retryOptions.receiptInterval,
            ),
        )

        out.emit(DfuProgress.FindingNode)
        if (!wasReset) {
            // Nothing was reset, so there is nothing to wait for: the
            // peer is still sitting in the same bootloader on the same
            // address. See [LegacyDfuSession.abort] for why a node whose
            // bank is already erased is deliberately left alone.
            val same = scanner.findBootloader(
                BootloaderExpectation(exactAddress = peer.address, nameHint = peer.name),
                options.scanTimeoutMs,
            )
            if (same == null) {
                out.emit(
                    DfuProgress.Failed(
                        "The node stopped advertising after the transfer failed.",
                        Recovery.NODE_NOT_FOUND,
                    ),
                )
                return
            }
            runTransfer(
                pkg,
                same,
                retryOptions,
                out,
                allowRestart = false,
                applicationAlreadyErased = true,
            )
            return
        }

        // The node was reset a moment ago and is coming back up. It
        // still has its application — the reset is only ever sent
        // before the bank is erased — so it may come back as either the
        // firmware or the bootloader.
        // Let it reboot before looking for it.
        //
        // A reset is not instant and the peer does not vanish politely:
        // it tears the link down inside the handler, restarts, runs its
        // bootloader init, and only then begins advertising again. A
        // scan started in the same breath spends its first seconds
        // watching an address that is not transmitting, and on hardware
        // a 30-second window from that starting point expired with the
        // node coming back a moment later — reported as "it did not come
        // back", about a node that had.
        delay(REBOOT_SETTLE_MS)
        val rescanMs = maxOf(options.scanTimeoutMs, REBOOT_SCAN_TIMEOUT_MS)
        val again = scanner.findBootloader(
            BootloaderExpectation(exactAddress = peer.address, nameHint = peer.name),
            rescanMs,
        )
        if (again == null) {
            out.emit(
                DfuProgress.Failed(
                    "The node was restarted to clear an interrupted update, but it did not " +
                        "come back within ${(REBOOT_SETTLE_MS + rescanMs) / 1000} seconds.",
                    Recovery.NODE_NOT_FOUND,
                ),
            )
            return
        }
        runTransfer(pkg, again, retryOptions, out, allowRestart = false)
    }

    /**
     * [perform], bounded by the same budget the peer's silence is.
     * False when it ran out.
     *
     * The stall watchdog originally covered only the wait for a
     * notification, which is half of the loop. The other half is a
     * batch of packet writes, and on Android every one of those waits
     * for `onCharacteristicWrite` — a callback from a vendor Bluetooth
     * stack. When one does not arrive the transfer suspends inside the
     * write, where no timeout was looking, and the screen holds the last
     * byte count for ever. A live ProMicro presented exactly that at
     * 14,800 of 372,044 bytes: no error, no disconnect, no progress, and
     * nothing to do but kill the app.
     *
     * Whether that particular stall was a lost write callback or a
     * silent peer is still unknown. This makes the difference visible
     * instead of indistinguishable, which is the prerequisite for
     * knowing.
     */
    private suspend fun performOrStall(
        client: DfuGattClient,
        actions: List<DfuAction>,
        packetDelayMs: Long,
        stage: DfuStage,
    ): Boolean = withTimeoutOrNull(stallBudgetMs(stage)) {
        perform(client, actions, packetDelayMs)
        true
    } ?: false

    private suspend fun perform(
        client: DfuGattClient,
        actions: List<DfuAction>,
        packetDelayMs: Long = 0,
    ) {
        for (action in actions) {
            when (action) {
                // A write the peer reboots inside cannot be
                // acknowledged, so its failure is the node obeying. See
                // [LegacyDfu.rebootsInsideTheHandler] — letting this one
                // escape reported a finished flash as an error on its
                // very last write.
                is DfuAction.WriteControl ->
                    if (LegacyDfu.rebootsInsideTheHandler(action.bytes)) {
                        runCatching { client.writeControl(action.bytes) }
                    } else {
                        client.writeControl(action.bytes)
                    }
                is DfuAction.WritePacket -> {
                    client.writePacket(action.bytes)
                    if (packetDelayMs > 0) delay(packetDelayMs)
                }

                is DfuAction.Fail, DfuAction.Complete -> {}
            }
        }
    }

    /**
     * Why the node would not switch into its bootloader.
     *
     * The default here used to be [Recovery.NO_DFU_SERVICE] — "this
     * radio does not offer over-the-air updates" — which is a claim
     * about the hardware, and it was printed for a GATT status 133: a
     * connection that dropped. It sent the reader off to check their
     * board and firmware version when the answer was "stand closer and
     * press it again". A message must only say what it knows.
     */
    private fun rebootFailure(e: Exception): DfuProgress.Failed {
        val recovery = when {
            e is StaleBondException -> Recovery.STALE_BOND
            // Only when the service was genuinely absent is this a
            // statement about what the node supports.
            e is NoDfuServiceException -> Recovery.NO_DFU_SERVICE
            else -> Recovery.LINK_LOST
        }
        return DfuProgress.Failed(
            e.message ?: "The node would not switch to firmware-update mode.",
            recovery,
        )
    }
}

/**
 * Write something whose answer is the node rebooting, and do not wait to
 * be thanked for it.
 *
 * **Two writes in this protocol can never be acknowledged**, and both
 * have been reported as failures here at some point:
 *
 * - the app-mode jump, which `BLEDfu.cpp` handles by saving the bond
 *   keys, disabling the SoftDevice and calling
 *   `bootloader_util_app_start()`;
 * - [LegacyDfu.SYSTEM_RESET], which `dfu_transport_ble.c` handles by
 *   closing the transport and calling `dfu_reset()`.
 *
 * Either way the radio is gone before an ATT response can be sent. What
 * a client sees is the write callback failing with status 133 and the
 * link timing out five seconds later — indistinguishable from a crash,
 * and exactly what this code used to print.
 *
 * So a lost link here is expected, and the proof is never the write: it
 * is the peer turning up again (or not) afterwards. What is NOT ignored
 * is the node actively **refusing** — a stale bond, or the CCCD error
 * that means we never subscribed — because those say the write was
 * rejected rather than obeyed.
 */
suspend fun DfuGattClient.writeExpectingAReboot(bytes: ByteArray) {
    try {
        writeControl(bytes)
    } catch (e: StaleBondException) {
        throw e
    } catch (e: NoDfuServiceException) {
        throw e
    } catch (e: Exception) {
        // Swallowed on purpose. What happens next decides.
    }
}

/**
 * Thrown when the radio refuses the app-mode jump because the bond
 * predates its DFU service. See [Recovery.STALE_BOND].
 */
class StaleBondException(message: String) : Exception(message)

/**
 * The peer has no DFU service — the one failure that IS a statement
 * about what the node supports, and the only one allowed to say so.
 */
class NoDfuServiceException(message: String) : Exception(message)

/**
 * What to tell someone whose update did not work. Every one of these is
 * a real failure mode of this protocol, and the difference between them
 * is the difference between a two-minute retry and a trip up a tower
 * with a USB cable.
 */
object Recovery {
    const val NODE_NOT_FOUND =
        "The node advertises under a different name in update mode (usually ending in " +
            "\"_OTA\"). Check it is powered and in range. A node that has already been " +
            "told to update is WAITING, not broken — it stays in update mode until it is " +
            "flashed or power-cycled."

    const val NOT_ADVERTISING =
        "A node runs `start ota` and then advertises for an update under a name ending in " +
            "\"OTA\" — it keeps repeating until something connects to it. Check the command " +
            "was accepted, that you are within Bluetooth range, and that this is an nRF52 " +
            "board: on ESP32 the same command raises a WiFi hotspot instead."

    const val LINK_LOST =
        "The Bluetooth link failed rather than the node refusing. Move closer, make sure " +
            "nothing else is connected to it, and try again — the node is unchanged and " +
            "still running whatever it was running before."

    const val CONNECT_FAILED =
        "Try again from closer in. If it keeps failing, forget the node in the system " +
            "Bluetooth settings first — a stale pairing blocks the update connection."

    const val STALE_BOND =
        "This node was paired before it carried a firmware-update service, so the pairing " +
            "has to be redone: forget it in the system Bluetooth settings, reconnect, then " +
            "try again."

    const val NO_DFU_SERVICE =
        "This radio does not offer over-the-air updates. Companion firmware v1.15 or newer " +
            "is needed, and only nRF52 boards support it — ESP32 boards update over USB or " +
            "their own WiFi hotspot."

    const val INTERRUPTED =
        "The node is still in update mode and can be flashed again — it is waiting, not " +
            "bricked. Stay close to it and retry."

    const val CRC =
        "The image that arrived did not match its checksum. Retry from closer in, and if it " +
            "keeps happening lower the packet-receipt interval."

    const val REJECTED =
        "The node refused the package. Check it is the right file for this board, and that " +
            "the bootloader is current — the OTAFIX bootloader is strongly recommended for " +
            "over-the-air updates."

    /**
     * The image step failing with the catch-all is, on this bootloader,
     * almost always its receive pool overflowing — it is being sent data
     * faster than it can write it to flash.
     */
    const val TOO_FAST =
        "The node could not keep up: it takes each packet into a small buffer and writes it " +
            "to flash, and that buffer filled. Nothing is wrong with the package or the " +
            "board. Retrying more slowly is the fix — it takes longer but usually works."

    /**
     * Shown only when the automatic recovery has already been tried and
     * the node still will not start — so it does not repeat the advice
     * the app has just acted on.
     */
    const val STALE_SESSION =
        "A bootloader remembers an interrupted update until it restarts, and refuses a new " +
            "one until then. It has been told to restart once already without effect, so " +
            "power-cycle the node and try again. Nothing is wrong with the package or the " +
            "board — the node is waiting, not bricked."

    fun forFailure(failure: DfuFailure): String = when (failure) {
        DfuFailure.StaleSession -> STALE_SESSION

        is DfuFailure.Rejected -> when {
            failure.result == LegacyDfu.RESP_CRC_ERROR -> CRC
            // `NRF_ERROR_NO_MEM` from the bootloader's receive pool is
            // translated to this catch-all, and during the image step it
            // means one thing in practice: too fast.
            failure.procedure == LegacyDfu.OP_RECEIVE_FW &&
                failure.result == LegacyDfu.RESP_OPER_FAILED -> TOO_FAST

            else -> REJECTED
        }

        is DfuFailure.ByteCountMismatch -> INTERRUPTED
        is DfuFailure.Stalled -> INTERRUPTED
        is DfuFailure.Malformed -> REJECTED
        is DfuFailure.OutOfOrder -> REJECTED
    }
}
