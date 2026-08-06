package io.github.thatsfguy.meshcore.platform

import io.github.thatsfguy.meshcore.transport.IncomingFrame
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS BLE transport over the Nordic UART Service, mirroring Android's
 * `BleTransport` so the engine treats the two interchangeably.
 *
 * iOS supports BLE fully through CoreBluetooth with no entitlement.
 * (What it does NOT give third-party apps is *classic* Bluetooth —
 * BR/EDR and the SPP serial profile — which needs the MFi programme.
 * MeshCore radios speak BLE NUS, so that restriction never applies.)
 *
 * ## The one thing that must not be copied from the sibling
 *
 * This is a port of reticulum-mobile-app's `IosBleTransport`, and the
 * critical divergence is framing. That app speaks KISS and may chunk a
 * frame across writes because KISS delimiters let the peer reassemble.
 * **MeshCore has no framing over BLE at all**: one write is one
 * companion frame, and one notification is one companion frame
 * (MESHCORE_PROTOCOL §2). Chunking here would not truncate a message —
 * it would silently hand the radio two malformed frames.
 *
 * So an oversized frame is REFUSED rather than split, exactly as
 * Android refuses it. Android negotiates MTU 247 and asserts against
 * `mtu - 3`; CoreBluetooth does not expose MTU negotiation, so we ask
 * it directly for `maximumWriteValueLengthForType`, which is the same
 * number arrived at from the other side.
 *
 * ## Caller responsibilities
 *
 * Same as Android's: this class does not scan. It takes an
 * already-discovered `CBPeripheral` and connects. The app needs the
 * `bluetooth-central` background mode in Info.plist for the link to
 * survive backgrounding, which is what the foreground service does on
 * Android.
 *
 * ## Threading
 *
 * CoreBluetooth serialises delegate callbacks on the queue passed to
 * `CBCentralManager.init(delegate:queue:)` — the main queue by default
 * — so the continuation fields below need no lock of their own.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBleTransport(
    private val central: CBCentralManager,
    private val peripheral: CBPeripheral,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingFrame>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingFrame> = _incoming.asSharedFlow()

    /** BLE is a local link, never plaintext-over-a-network. */
    override val isPlaintextLink: Boolean get() = false

    private var writeChar: CBCharacteristic? = null
    private var notifyChar: CBCharacteristic? = null

    private val writeLock = Mutex()

    /**
     * [connect] parks here for the whole chain — CB connect → service
     * discovery → characteristic discovery → notifications enabled —
     * and is resumed from `didUpdateNotificationStateFor`.
     */
    private var readyContinuation: CancellableContinuation<Unit>? = null

    /**
     * A writer parks here when CoreBluetooth's queue is full.
     *
     * Carried over from the sibling because it was expensive to learn:
     * a `.withoutResponse` write issued while the queue is full is
     * **silently dropped**. On that app it swallowed the radio-config
     * burst right after connect. Here it would swallow a command frame
     * with no error anywhere.
     */
    private var writeReadyContinuation: CancellableContinuation<Unit>? = null

    /**
     * Gate on `canSendWriteWithoutResponse` only AFTER the first write.
     *
     * CoreBluetooth reports it false immediately after connect and only
     * starts delivering the readiness callback once a write has been
     * issued — so gating before the first write deadlocks until the
     * timeout. Also carried over rather than rediscovered.
     */
    private var hasWrittenOnce = false

    // One delegate object for both protocols, held strongly: CB keeps
    // only weak references to delegates.
    private val bleDelegate = object : NSObject(),
        CBCentralManagerDelegateProtocol,
        CBPeripheralDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            // Only meaningful before connect. If the radio is off, CB
            // fails the connect and didFailToConnectPeripheral surfaces
            // it — no need to duplicate the reporting here.
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.delegate = this
            didConnectPeripheral.discoverServices(listOf(NUS_SERVICE_UUID))
        }

        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            failConnect("BLE connect failed: ${error?.localizedDescription ?: "unknown"}")
        }

        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            _state.value = TransportState.Disconnected
            error?.let { failConnect("BLE disconnected during connect: ${it.localizedDescription}") }
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                failConnect("Service discovery failed: ${didDiscoverServices.localizedDescription}")
                return
            }
            val nus = peripheral.services?.filterIsInstance<CBService>()
                ?.firstOrNull { it.UUID == NUS_SERVICE_UUID }
            if (nus == null) {
                failConnect("This device does not advertise the MeshCore serial service")
                return
            }
            peripheral.discoverCharacteristics(
                listOf(NUS_WRITE_UUID, NUS_NOTIFY_UUID),
                forService = nus,
            )
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                failConnect("Characteristic discovery failed: ${error.localizedDescription}")
                return
            }
            val chars = didDiscoverCharacteristicsForService.characteristics
                ?.filterIsInstance<CBCharacteristic>()
                ?: emptyList()
            writeChar = chars.firstOrNull { it.UUID == NUS_WRITE_UUID }
            notifyChar = chars.firstOrNull { it.UUID == NUS_NOTIFY_UUID }
            val notify = notifyChar
            if (writeChar == null || notify == null) {
                failConnect("MeshCore serial characteristics missing on this device")
                return
            }
            peripheral.setNotifyValue(true, forCharacteristic = notify)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                failConnect("Enabling notifications failed: ${error.localizedDescription}")
                return
            }
            _state.value = TransportState.Connected
            val cont = readyContinuation
            readyContinuation = null
            cont?.resume(Unit)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) return
            val data = didUpdateValueForCharacteristic.value ?: return
            val bytes = data.toByteArray()
            // ONE notification is ONE complete companion frame. No
            // parser, no reassembly — see the class note on framing.
            if (bytes.isNotEmpty()) _incoming.tryEmit(IncomingFrame(bytes))
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            val cont = writeReadyContinuation
            writeReadyContinuation = null
            cont?.resume(Unit)
        }
    }

    init {
        central.delegate = bleDelegate
    }

    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    override suspend fun connect() {
        if (_state.value == TransportState.Connected) return
        _state.value = TransportState.Connecting
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                readyContinuation = cont
                cont.invokeOnCancellation { disconnectInternal() }
                central.connectPeripheral(peripheral, options = null)
            }
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            disconnectInternal()
            throw t
        }
    }

    private fun failConnect(reason: String) {
        _state.value = TransportState.Error
        val cont = readyContinuation
        readyContinuation = null
        cont?.resumeWithException(IllegalStateException(reason))
    }

    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    override suspend fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        runCatching { central.cancelPeripheralConnection(peripheral) }
        writeChar = null
        notifyChar = null
        _state.value = TransportState.Disconnected
    }

    /**
     * Write one companion frame.
     *
     * Never chunked. If the frame does not fit one write it is refused,
     * because MeshCore's BLE link has no framing and a split frame is
     * two malformed frames rather than one delayed one.
     */
    override suspend fun send(frame: ByteArray) {
        val tx = writeChar
        if (tx == null || peripheral.state != CBPeripheralStateConnected) {
            // The link went away — most often while the app was
            // suspended. Flip state so the engine's collector tears the
            // link down, and return rather than throwing: a throw here
            // propagates up an engine coroutine.
            _state.value = TransportState.Disconnected
            return
        }
        val maxWrite = peripheral
            .maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse)
            .toInt()
            .coerceAtLeast(MIN_WRITE_BUDGET)
        require(frame.size <= maxWrite) {
            "Frame of ${frame.size} B exceeds the BLE write limit of $maxWrite B"
        }

        writeLock.withLock {
            if (hasWrittenOnce && !peripheral.canSendWriteWithoutResponse) {
                awaitWriteReady()
            }
            val wrote = runCatching {
                peripheral.writeValue(
                    frame.toNSData(),
                    forCharacteristic = tx,
                    type = CBCharacteristicWriteWithoutResponse,
                )
            }
            if (wrote.isFailure) {
                _state.value = TransportState.Disconnected
                return
            }
            hasWrittenOnce = true
        }
    }

    /**
     * Park until CoreBluetooth will accept another write.
     *
     * Timeout backstop so a missed wakeup cannot stall sending for ever;
     * falling through risks one dropped frame, which is the behaviour we
     * would have had anyway without the gate.
     */
    private suspend fun awaitWriteReady() {
        if (peripheral.canSendWriteWithoutResponse) return
        withTimeoutOrNull(WRITE_READY_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                writeReadyContinuation = cont
                cont.invokeOnCancellation { writeReadyContinuation = null }
                // Re-check after registering: the callback can fire on
                // CB's queue between the check above and this line,
                // which would otherwise park us until the timeout.
                if (peripheral.canSendWriteWithoutResponse) {
                    writeReadyContinuation = null
                    cont.resume(Unit)
                }
            }
        }
    }

    companion object {
        /** Backstop for [awaitWriteReady] — only trips on a stuck link. */
        private const val WRITE_READY_TIMEOUT_MS = 5_000L

        /** The BLE minimum (ATT MTU 23 − 3 header bytes). */
        private const val MIN_WRITE_BUDGET = 20

        val NUS_SERVICE_UUID: CBUUID =
            CBUUID.UUIDWithString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

        /** Client → radio. */
        val NUS_WRITE_UUID: CBUUID =
            CBUUID.UUIDWithString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

        /** Radio → client (notify). */
        val NUS_NOTIFY_UUID: CBUUID =
            CBUUID.UUIDWithString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    }
}

// ---- NSData <-> ByteArray -------------------------------------------

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), this.size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = this.length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, len.convert())
    }
    return out
}
