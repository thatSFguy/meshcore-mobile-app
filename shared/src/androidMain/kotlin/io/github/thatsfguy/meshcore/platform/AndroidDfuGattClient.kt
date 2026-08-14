package io.github.thatsfguy.meshcore.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.thatsfguy.meshcore.firmware.DfuGattClient
import io.github.thatsfguy.meshcore.firmware.DfuPeer
import io.github.thatsfguy.meshcore.firmware.LegacyDfu
import io.github.thatsfguy.meshcore.firmware.WRITE_CONFIRMATION_TIMEOUT_MS
import io.github.thatsfguy.meshcore.firmware.NoDfuServiceException
import io.github.thatsfguy.meshcore.firmware.StaleBondException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GATT client for a node sitting in bootloader DFU mode.
 *
 * Deliberately NOT part of [BleTransport]. That class is the MeshCore
 * companion link — one frame per write, NUS characteristics, a
 * reconnect supervisor above it — and none of that applies to a peer
 * that has no frames, a different service, and exactly one job before it
 * reboots. The GATT callback-to-continuation plumbing is the same shape,
 * and that is where the similarity ends.
 *
 * Permissions are the caller's problem, as with [BleTransport]:
 * BLUETOOTH_CONNECT must already be held.
 */
@SuppressLint("MissingPermission")
class AndroidDfuGattClient(
    private val context: Context,
    private val peer: DfuPeer,
    /**
     * The device object the scan produced, when there is one.
     *
     * Strongly preferred over resolving the address: see
     * [AndroidDfuScanner.deviceFor]. Rebuilding a device from its
     * address string assumes a PUBLIC address, and an nRF board
     * advertising from a random static one then fails to connect with
     * status 133 and nothing else to go on.
     */
    private val scanned: BluetoothDevice? = null,
    private val log: (String) -> Unit = {},
) : DfuGattClient {

    /**
     * A channel rather than a `SharedFlow`, because the peer answers
     * before anyone is listening.
     *
     * The start step is written and the bootloader's response comes back
     * while the updater is still emitting the writes that provoked it —
     * so with `replay = 0` and no subscriber yet, `tryEmit` succeeds and
     * throws the response away. The transfer then waits for a
     * notification that has already been and gone and is reported as
     * "the node stopped responding part-way through". A channel holds
     * everything from the moment the client exists, whenever collection
     * starts.
     */
    private val _notifications = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications: Flow<ByteArray> = _notifications.receiveAsFlow()

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var packetChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23

    private val writeLock = Mutex()

    /**
     * Packet writes the stack never returned a buffer credit for.
     *
     * Not an error — see [write] — but the number is worth having: it
     * is the difference between "this stack confirms no-response writes"
     * and "it never does", and neither is visible any other way.
     */
    private var unconfirmedPackets = 0

    private var servicesContinuation: CancellableContinuation<Unit>? = null
    private var mtuContinuation: CancellableContinuation<Int>? = null
    private var descWriteContinuation: CancellableContinuation<Unit>? = null
    private var charWriteContinuation: CancellableContinuation<Unit>? = null
    private var readContinuation: CancellableContinuation<ByteArray?>? = null

    /**
     * Set once the peer drops the link. Expected at the end of a
     * transfer — activate-and-reset IS a disconnect — and a failure
     * before that.
     */
    @Volatile
    private var disconnected = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    disconnected = true
                    servicesContinuation?.resumeWithException(
                        IllegalStateException(
                            "The node dropped the connection before the update started " +
                                "(status $status).",
                        ),
                    )
                    servicesContinuation = null
                    // A write in flight when the node reboots is not an
                    // error — it is the last write of the update.
                    charWriteContinuation?.resume(Unit)
                    charWriteContinuation = null
                    readContinuation?.resume(null)
                    readContinuation = null
                    // Nothing more can arrive. Ending the flow turns a
                    // link that died mid-transfer into "the node stopped
                    // responding" rather than a wait with no end.
                    _notifications.close()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesContinuation?.resumeWithException(
                    IllegalStateException("Service discovery failed ($status)."),
                )
            } else {
                servicesContinuation?.resume(Unit)
            }
            servicesContinuation = null
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            mtuContinuation?.resume(negotiatedMtu)
            mtuContinuation = null
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descWriteContinuation?.resume(Unit)
            } else {
                descWriteContinuation?.resumeWithException(gattWriteError(status))
            }
            descWriteContinuation = null
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                charWriteContinuation?.resume(Unit)
            } else {
                charWriteContinuation?.resumeWithException(gattWriteError(status))
            }
            charWriteContinuation = null
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            readContinuation?.resume(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
            readContinuation = null
        }

        @Deprecated("Pre-API-33 read callback, kept for minSdk 26.")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val value = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
            readContinuation?.resume(value)
            readContinuation = null
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (value.isNotEmpty()) _notifications.trySend(value)
        }

        @Deprecated("Pre-API-33 callback, kept for compatibility with minSdk 26.")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val data = characteristic.value ?: return
            if (data.isNotEmpty()) _notifications.trySend(data)
        }
    }

    override suspend fun connect() {
        val device = scanned ?: run {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            log("no scanned device for ${peer.address}; resolving by address")
            mgr.adapter.getRemoteDevice(peer.address)
        }
        // Status 133 is Android's catch-all and is frequently transient:
        // a stack still tearing down the previous link, or a peer that
        // has only just started advertising. One retry costs a second
        // and turns a dead end into a connection more often than not.
        // Let the stack settle before the first attempt. Connecting
        // immediately after a scan stops is one of the documented ways
        // to earn a 133 on Samsung hardware — the scan teardown and the
        // connection race each other.
        delay(SETTLE_AFTER_SCAN_MS)
        var attempt = 0
        while (true) {
            try {
                // Escalate rather than repeat: a direct connection is
                // fastest when it works, and `autoConnect` hands the
                // problem to the stack to retry in the background, which
                // succeeds against peers a direct connect will not hold.
                connectOnce(device, autoConnect = attempt >= 1)
                break
            } catch (e: Exception) {
                closeInternal()
                attempt++
                log("connect attempt $attempt failed: ${e.message}")
                if (attempt >= CONNECT_ATTEMPTS) throw e
                delay(1_500L * attempt)
            }
        }
        // A bigger MTU means fewer writes, but the chunk size stays the
        // caller's decision — a stock bootloader will negotiate happily
        // and then choke on the larger packets.
        requestMtu(247)
        log("connected to ${peer.address}, MTU $negotiatedMtu")
        val service = gatt?.getService(SERVICE_UUID)
            ?: throw NoDfuServiceException(
                "This node is not in firmware-update mode: it has no DFU service.",
            )
        controlPoint = service.getCharacteristic(CONTROL_POINT_UUID)
            ?: throw IllegalStateException("The DFU service has no control point.")
        packetChar = service.getCharacteristic(PACKET_UUID)
            ?: throw IllegalStateException("The DFU service has no packet characteristic.")
    }

    private suspend fun connectOnce(device: BluetoothDevice, autoConnect: Boolean) {
        disconnected = false
        log("connecting to ${device.address} (autoConnect=$autoConnect)")
        // Two details, both of which cause 133 when got wrong:
        //
        // TRANSPORT_LE explicitly — the default is TRANSPORT_AUTO, which
        // on several stacks tries BR/EDR first against an LE-only peer.
        //
        // And connectGatt is issued on the MAIN thread. Several vendor
        // stacks, Samsung's among them, fail a connection started from a
        // background thread with the same opaque status.
        withContext(Dispatchers.Main) {
            gatt = device.connectGatt(
                context,
                autoConnect,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }
        // autoConnect has no timeout of its own: the stack will wait for
        // the peer indefinitely, so the wait is bounded here instead.
        val ready = withTimeoutOrNull(if (autoConnect) AUTO_CONNECT_TIMEOUT_MS else DIRECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                servicesContinuation = cont
                cont.invokeOnCancellation { closeInternal() }
            }
        }
        if (ready == null) {
            servicesContinuation = null
            throw IllegalStateException("The node did not answer the connection.")
        }
    }

    override suspend fun subscribeToControlPoint() {
        val g = gatt ?: error("not connected")
        val chr = controlPoint ?: error("not connected")
        if (!g.setCharacteristicNotification(chr, true)) {
            throw IllegalStateException("Could not subscribe to the DFU control point.")
        }
        val cccd = chr.getDescriptor(CCCD_UUID)
            ?: throw IllegalStateException("The DFU control point has no CCCD.")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        suspendCancellableCoroutine<Unit> { cont ->
            descWriteContinuation = cont
            if (!g.writeDescriptor(cccd)) {
                descWriteContinuation = null
                cont.resumeWithException(IllegalStateException("writeDescriptor returned false"))
            }
        }
    }

    override suspend fun readDfuRevision(): Int? {
        val g = gatt ?: return null
        val chr = g.getService(SERVICE_UUID)?.getCharacteristic(REVISION_UUID) ?: return null
        val value = suspendCancellableCoroutine<ByteArray?> { cont ->
            readContinuation = cont
            if (!g.readCharacteristic(chr)) {
                readContinuation = null
                cont.resume(null)
            }
        }
        if (value == null || value.size < 2) return null
        val revision = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        log("DFU revision 0x${revision.toString(16)}")
        return revision
    }

    override suspend fun writeControl(bytes: ByteArray) =
        write(controlPoint, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    override suspend fun writePacket(bytes: ByteArray) =
        write(packetChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    /** ATT MTU minus the 3-byte ATT header. */
    override suspend fun maxWriteLength(): Int {
        val length = negotiatedMtu - 3
        // Logged because the number decides how long the transfer takes:
        // 20 means the MTU exchange did not happen and every packet
        // costs twelve writes instead of one. See
        // [LegacyDfu.packetSizeFor].
        log("packet size from MTU $negotiatedMtu: ${LegacyDfu.packetSizeFor(length)} bytes")
        return length
    }

    override suspend fun requestHighThroughput(): Boolean {
        val g = gatt ?: return false
        val accepted = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        log("connection priority HIGH requested -> $accepted")
        return accepted
    }

    /**
     * One write at a time, each awaited before the next is queued.
     *
     * Even a no-response write has to be paced: Android's stack accepts
     * a bounded number of outstanding buffers and returns false when it
     * is full, and firing a whole image at it regardless is how a
     * transfer silently loses packets.
     *
     * **The two write types are not waited for the same way**, and the
     * difference was earned on hardware. A control-point write is a
     * write WITH response: the peer's ATT acknowledgement is what
     * completes it, so its confirmation is a fact about the peer and
     * worth waiting a long time for. A packet write is a write WITHOUT
     * response, and `onCharacteristicWrite` for one is only the local
     * stack saying it has a buffer free again — which it is under no
     * obligation to say promptly, or at all.
     *
     * On a live ProMicro's bootloader (MTU 23, so 20-byte packets) that
     * distinction decided everything: every control write completed
     * instantly, including a system-reset issued seconds later, while
     * the FIRST no-response packet write after them never completed.
     * One attempt got a single packet through and hung on the next;
     * another hung on the first and sat there for the full 90-second
     * step budget with a healthy link on both sides of it.
     *
     * So a packet write waits only briefly for its buffer credit and
     * then carries on. Nothing is lost by that: flow control for the
     * image is the peer's receipt notification, which is a fact about
     * what the peer RECEIVED rather than about a local buffer, and the
     * stack still refuses a write outright when it genuinely has no
     * room — which is handled below and is the real backpressure.
     */
    private suspend fun write(
        chr: BluetoothGattCharacteristic?,
        bytes: ByteArray,
        writeType: Int,
    ) {
        val characteristic = chr ?: error("not connected")
        val g = gatt ?: error("not connected")
        val noResponse = writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writeLock.withLock {
            if (disconnected) throw IllegalStateException("The node disconnected.")
            var attempts = 0
            while (true) {
                try {
                    // Bounded, because `onCharacteristicWrite` is a
                    // callback from a vendor stack and not a promise. A
                    // write that is never confirmed used to suspend for
                    // ever, holding [writeLock] with it, so the whole
                    // transfer stopped inside a single packet with no
                    // error and no disconnect — indistinguishable, from
                    // the outside, from a node that had gone quiet.
                    //
                    // Deliberately far longer than any step budget: this
                    // is the backstop, and the caller's per-step budget
                    // is what actually decides a transfer is dead. See
                    // [WRITE_CONFIRMATION_TIMEOUT_MS] for what happened
                    // when it was the other way round.
                    val budget =
                        if (noResponse) BUFFER_CREDIT_TIMEOUT_MS else WRITE_CONFIRMATION_TIMEOUT_MS
                    val done = withTimeoutOrNull(budget) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            charWriteContinuation = cont
                            characteristic.writeType = writeType
                            characteristic.value = bytes
                            if (!g.writeCharacteristic(characteristic)) {
                                charWriteContinuation = null
                                cont.resumeWithException(BusyStackException())
                            }
                        }
                    }
                    if (done == null) {
                        // Cleared before the next write can install its
                        // own, so a late callback finds nothing to
                        // resume rather than completing someone else's
                        // wait.
                        charWriteContinuation = null
                        if (noResponse) {
                            // Expected on some stacks; the peer's receipt
                            // notification is the flow control that
                            // matters. Counted rather than narrated —
                            // one line per packet would be 20,000 lines.
                            unconfirmedPackets++
                            return@withLock
                        }
                        log("no control-point confirmation for ${bytes.size} bytes in ${budget}ms")
                        throw IllegalStateException(
                            "The Bluetooth stack never confirmed a ${bytes.size}-byte write. " +
                                "The link is up but not moving data.",
                        )
                    }
                    return@withLock
                } catch (e: BusyStackException) {
                    attempts++
                    if (attempts >= 5) {
                        throw IllegalStateException(
                            "The Bluetooth stack refused ${bytes.size} bytes $attempts times.",
                        )
                    }
                    delay(50)
                }
            }
        }
    }

    private suspend fun requestMtu(target: Int) {
        suspendCancellableCoroutine<Int> { cont ->
            mtuContinuation = cont
            if (gatt?.requestMtu(target) != true) {
                mtuContinuation = null
                cont.resume(negotiatedMtu)
            }
        }
    }

    override suspend fun close() {
        if (unconfirmedPackets > 0) {
            log("$unconfirmedPackets packet writes went unconfirmed by the Bluetooth stack")
        }
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        controlPoint = null
        packetChar = null
    }

    /** Retryable: the stack's write queue was momentarily full. */
    private class BusyStackException : Exception("The Bluetooth stack is busy.")

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString(LegacyDfu.SERVICE_UUID)
        val CONTROL_POINT_UUID: UUID = UUID.fromString(LegacyDfu.CONTROL_POINT_UUID)
        val PACKET_UUID: UUID = UUID.fromString(LegacyDfu.PACKET_UUID)

        /** Read to tell an app-mode peer from a bootloader. */
        val REVISION_UUID: UUID = UUID.fromString(LegacyDfu.REVISION_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * A GATT write status turned into something a user can act on.
         *
         * The authentication family is the "unpair and try again" case:
         * the DFU characteristics require an encrypted, MITM-protected
         * link (`SerialBLEInterface.cpp`,
         * `SECMODE_ENC_WITH_MITM`), so a bond made before the node
         * carried the service fails here and nowhere else. 0xFD is the
         * app-mode service refusing a write from a client that has not
         * subscribed.
         */
        fun gattWriteError(status: Int): Exception = when (status) {
            GATT_ERROR -> IllegalStateException(
                "The connection to the node failed (status 133). This is the Bluetooth " +
                    "stack's catch-all: usually a link that dropped, or a peer that is no " +
                    "longer advertising.",
            )

            LegacyDfu.CCCD_CONFIG_ERROR -> StaleBondException(
                "The node refused the update request (0xFD): its pairing predates the " +
                    "firmware-update service.",
            )

            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
            GATT_INSUFFICIENT_AUTHORIZATION,
            -> StaleBondException(
                "The node requires a re-pairing before it will accept a firmware update " +
                    "(status $status).",
            )

            else -> IllegalStateException("The write failed (status $status).")
        }

        /** Not exposed by [BluetoothGatt] on every API level. */
        private const val GATT_INSUFFICIENT_AUTHORIZATION = 8

        /**
         * Android's generic BLE failure. It means "something went wrong"
         * and nothing more — never a statement about what the peer
         * supports.
         */
        const val GATT_ERROR = 133

        /**
         * How long a no-response packet write waits for its local
         * buffer credit before carrying on. Short by design: this is a
         * courtesy pause that paces the stack when it answers, not a
         * correctness requirement. See [write].
         */
        private const val BUFFER_CREDIT_TIMEOUT_MS = 400L

        private const val CONNECT_ATTEMPTS = 3
        private const val SETTLE_AFTER_SCAN_MS = 1_000L
        private const val DIRECT_TIMEOUT_MS = 12_000L
        private const val AUTO_CONNECT_TIMEOUT_MS = 25_000L
    }
}
