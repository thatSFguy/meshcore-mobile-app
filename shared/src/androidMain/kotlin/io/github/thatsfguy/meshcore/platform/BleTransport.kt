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
import io.github.thatsfguy.meshcore.firmware.BootloaderCapableTransport
import io.github.thatsfguy.meshcore.firmware.LegacyDfu
import io.github.thatsfguy.meshcore.transport.IncomingFrame
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Nordic UART Service (NUS) BLE transport for a MeshCore radio.
 *
 * GATT plumbing carried over from reticulum-mobile-app's BleTransport.
 * The framing differs from the RNode/KISS path: MeshCore companion
 * frames need NO link framing over BLE — each GATT write carries one
 * complete frame, and each notification IS one complete frame
 * (MESHCORE_PROTOCOL.md §2). We request MTU 247 so a max-size frame
 * (172 B) always fits one write.
 *
 * NUS direction naming follows the MeshCore convention: the 0x…02
 * characteristic is client → radio (write), 0x…03 is radio → client
 * (notify).
 *
 * Permissions are not requested here — the Activity/Service layer must
 * hold BLUETOOTH_CONNECT before constructing this transport.
 *
 * [autoConnect] picks WHICH of Android's two connect modes to use, and
 * it is the difference between a radio that comes back when the user
 * walks back into range and one that does not:
 *
 *  - `false` is a *direct* connect. The controller initiates now and
 *    gives up (status 133) after its own timeout. Fast when the radio
 *    is there; useless when it is not.
 *  - `true` is a *background* connect. The address goes on the
 *    controller's allow-list and the connection completes by itself the
 *    moment the radio advertises again — minutes or hours later,
 *    without the app having to poll. Slower to establish, so it is what
 *    the reconnect supervisor asks for once a direct attempt has
 *    already failed.
 *
 * Either way [connect] is bounded (see [DIRECT_CONNECT_TIMEOUT_MS] /
 * [BACKGROUND_CONNECT_TIMEOUT_MS]) so a wedged attempt is torn down and
 * re-armed rather than parking the supervisor behind it forever.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val autoConnect: Boolean = false,
    private val log: (String) -> Unit = {},
) : Transport, BootloaderCapableTransport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingFrame>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingFrame> = _incoming.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23 // ATT minimum

    private val writeLock = Mutex()

    /**
     * Every suspending GATT step registers here so the disconnect
     * callback can fail the lot in one place — see [PendingGattOps].
     * Only the firmware-update jump ever awaits a characteristic write;
     * ordinary frames go out no-response and never register.
     */
    private val pending = PendingGattOps()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = TransportState.Connecting
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected (status=$status)")
                    _state.value = TransportState.Disconnected
                    // The radio disconnects as a direct result of the
                    // firmware-update jump. That is the acknowledgement,
                    // not a failure — so this slot succeeds before the
                    // sweep below fails whatever else was in flight.
                    pending.succeed(PendingGattOps.Slot.CharacteristicWrite, Unit)
                    // Every other step is waiting on a callback that is
                    // never coming now. Failing them is what lets
                    // connect() return so the supervisor can try again.
                    pending.failAll(
                        IllegalStateException("BLE disconnected before ready (status=$status)"),
                    )
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pending.fail(
                    PendingGattOps.Slot.Services,
                    IllegalStateException("Service discovery failed: $status"),
                )
            } else {
                pending.succeed(PendingGattOps.Slot.Services, Unit)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            // A refused MTU is not fatal — carry on at the negotiated
            // (or default) size.
            pending.succeed(PendingGattOps.Slot.Mtu, negotiatedMtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pending.succeed(PendingGattOps.Slot.Descriptor, Unit)
            } else {
                pending.fail(
                    PendingGattOps.Slot.Descriptor,
                    IllegalStateException("CCCD write failed: $status"),
                )
            }
        }

        // API 33+ delivers notification bytes via this 3-arg callback; the
        // deprecated 2-arg sees a null characteristic.value there — keep
        // both (see reticulum-mobile-app's Galaxy A42 note).
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (value.isNotEmpty()) _incoming.tryEmit(IncomingFrame(value))
        }

        @Deprecated("Pre-API-33 callback, kept for compatibility with minSdk 26.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            if (data.isNotEmpty()) _incoming.tryEmit(IncomingFrame(data))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pending.succeed(PendingGattOps.Slot.CharacteristicWrite, Unit)
            } else {
                pending.fail(
                    PendingGattOps.Slot.CharacteristicWrite,
                    AndroidDfuGattClient.gattWriteError(status),
                )
            }
        }
    }

    /**
     * Ask the radio to reboot into its bootloader for a firmware update.
     *
     * MeshCore companion v1.15+ on nRF52 registers Adafruit's `BLEDfu`
     * service beside the NUS (`SerialBLEInterface.cpp`). Its control
     * point takes one byte — [LegacyDfu.ENTER_BOOTLOADER] — and only
     * from a client that has subscribed first: `BLEDfu.cpp` answers an
     * unsubscribed write with `ATTERR_CPS_CCCD_CONFIG_ERROR` before it
     * even looks at the payload.
     *
     * The radio then saves our bond keys, disconnects and resets. The
     * disconnect is the acknowledgement.
     */
    override fun offersFirmwareUpdates(): Boolean =
        gatt?.getService(DFU_APP_SERVICE_UUID) != null

    override suspend fun requestBootloaderReboot() {
        val g = gatt ?: throw IllegalStateException("Not connected to a radio.")
        val service = g.getService(DFU_APP_SERVICE_UUID)
            ?: throw io.github.thatsfguy.meshcore.firmware.NoDfuServiceException(
                "This radio does not offer over-the-air updates: it has no DFU service.",
            )
        val control = service.getCharacteristic(DFU_APP_CONTROL_UUID)
            ?: throw IllegalStateException("The radio's DFU service has no control point.")

        if (!g.setCharacteristicNotification(control, true)) {
            throw IllegalStateException("Could not subscribe to the radio's DFU control point.")
        }
        val cccd = control.getDescriptor(CCCD_UUID)
            ?: throw IllegalStateException("The radio's DFU control point has no CCCD.")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        pending.await<Unit>(PendingGattOps.Slot.Descriptor) {
            if (!g.writeDescriptor(cccd)) {
                throw IllegalStateException("Could not subscribe to the DFU control point.")
            }
        }

        // The radio cannot answer this write: handling it disables the
        // SoftDevice and jumps to the bootloader, so the link dies
        // first. A failure here is the expected shape of success, and
        // the caller proves it by finding the bootloader afterwards.
        runCatching {
            writeLock.withLock {
                pending.await<Unit>(PendingGattOps.Slot.CharacteristicWrite) {
                    control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    control.value = LegacyDfu.ENTER_BOOTLOADER
                    if (!g.writeCharacteristic(control)) {
                        throw IllegalStateException("The update request could not be sent.")
                    }
                }
            }
        }.onFailure { if (it is io.github.thatsfguy.meshcore.firmware.StaleBondException) throw it }
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected) return
        _state.value = TransportState.Connecting
        val budget = if (autoConnect) BACKGROUND_CONNECT_TIMEOUT_MS else DIRECT_CONNECT_TIMEOUT_MS
        log(
            if (autoConnect) "Arming a background connect to ${device.address}"
            else "Connecting to ${device.address}",
        )
        try {
            // Bounded on purpose. Android will happily leave a connect
            // attempt outstanding indefinitely, and an attempt that
            // began while the radio was walking away is exactly the one
            // that never completes — so without this the supervisor
            // parks inside connect() and never retries once the radio
            // is back. withTimeoutOrNull, not withTimeout: a
            // TimeoutCancellationException is a CancellationException,
            // which the supervisor is right to treat as "we are being
            // shut down" rather than as a failed attempt.
            val reached = withTimeoutOrNull(budget) {
                connectAndDiscover()
                requestMtu(247)
                findNusCharacteristics()
                enableNotifications()
                true
            }
            if (reached != true) {
                throw IllegalStateException(
                    "The radio did not answer within ${budget / 1_000}s.",
                )
            }
            log("Connected (MTU $negotiatedMtu)")
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            disconnectInternal()
            throw t
        }
    }

    private suspend fun connectAndDiscover() {
        // TRANSPORT_LE explicitly: left to choose, the stack can pick
        // BR/EDR for a dual-mode-looking address and fail with the
        // status-133 that gets misread as "the radio is gone".
        val g = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("Bluetooth is unavailable.")
        gatt = g
        // The connect callback chains into service discovery; suspend on
        // the services callback rather than the raw connection.
        pending.await<Unit>(PendingGattOps.Slot.Services) {}
    }

    private suspend fun requestMtu(target: Int): Int =
        pending.await(PendingGattOps.Slot.Mtu) {
            // A refused request never produces a callback, so resolve it
            // here rather than leaving the slot parked.
            if (gatt?.requestMtu(target) != true) {
                pending.succeed(PendingGattOps.Slot.Mtu, negotiatedMtu)
            }
        }

    private fun findNusCharacteristics() {
        val service = gatt?.getService(NUS_SERVICE_UUID)
            ?: throw IllegalStateException("NUS service $NUS_SERVICE_UUID not found on device")
        writeChar = service.getCharacteristic(NUS_WRITE_UUID)
            ?: throw IllegalStateException("NUS write characteristic not found")
        notifyChar = service.getCharacteristic(NUS_NOTIFY_UUID)
            ?: throw IllegalStateException("NUS notify characteristic not found")
    }

    private suspend fun enableNotifications() {
        val rx = notifyChar ?: error("notify char missing")
        val g = gatt ?: error("GATT missing")
        if (!g.setCharacteristicNotification(rx, true)) {
            throw IllegalStateException("setCharacteristicNotification(true) returned false")
        }
        val cccd = rx.getDescriptor(CCCD_UUID)
            ?: throw IllegalStateException("notify char has no CCCD")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        pending.await<Unit>(PendingGattOps.Slot.Descriptor) {
            if (!g.writeDescriptor(cccd)) {
                throw IllegalStateException("writeDescriptor returned false")
            }
        }
    }

    override suspend fun disconnect() {
        disconnectInternal()
    }

    /**
     * Tear the link down and release the GATT client.
     *
     * close() is not optional and not deferrable: Android hands out a
     * bounded number of GATT client interfaces per app, and a transport
     * that is rebuilt on every reconnect attempt will exhaust them
     * within an afternoon of a radio going in and out of range — after
     * which every further connectGatt fails with status 133 until the
     * user toggles Bluetooth. Failing the pending ops first means
     * nothing is left waiting on callbacks from a client we just closed.
     */
    private fun disconnectInternal() {
        pending.failAll(IllegalStateException("BLE link torn down"))
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        writeChar = null
        notifyChar = null
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(frame: ByteArray) {
        val tx = writeChar ?: error("BleTransport not connected")
        val g = gatt ?: error("BleTransport not connected")
        // One frame per write — MeshCore BLE has no link framing, so a
        // frame can never be split across writes. MTU 247 - 3 ATT
        // overhead covers MAX_FRAME_SIZE (172).
        val maxWrite = (negotiatedMtu - 3).coerceAtLeast(20)
        check(frame.size <= maxWrite) {
            "Frame of ${frame.size} B exceeds BLE write limit $maxWrite (MTU $negotiatedMtu)"
        }
        writeLock.withLock {
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            tx.value = frame
            // The BLE stack's no-response write queue can be briefly busy;
            // retry with backoff instead of failing the whole command.
            var attempts = 0
            while (!g.writeCharacteristic(tx)) {
                attempts++
                if (attempts >= 5) {
                    throw IllegalStateException(
                        "writeCharacteristic returned false after $attempts attempts " +
                            "(frame=${frame.size} B)",
                    )
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    companion object {
        /**
         * How long a direct (autoConnect=false) attempt is given. The
         * controller's own giving-up point is around 30s, so this is the
         * backstop for the case where it never reports one.
         */
        const val DIRECT_CONNECT_TIMEOUT_MS = 40_000L

        /**
         * How long a background (autoConnect=true) attempt is left armed
         * before it is torn down and re-armed. Long, because waiting is
         * the entire point — the radio may be a walk away — but finite,
         * so a pending connection the stack has quietly dropped is
         * refreshed instead of waited on forever.
         */
        const val BACKGROUND_CONNECT_TIMEOUT_MS = 300_000L

        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        /** Client → radio (write). */
        val NUS_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        /** Radio → client (notify). */
        val NUS_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * The app-mode DFU service — same UUIDs as the bootloader's, on
         * a radio that is still running MeshCore. Present only on nRF52
         * companions from v1.15.
         */
        val DFU_APP_SERVICE_UUID: UUID = UUID.fromString(LegacyDfu.SERVICE_UUID)
        val DFU_APP_CONTROL_UUID: UUID = UUID.fromString(LegacyDfu.CONTROL_POINT_UUID)

        /** Resolve a BLE [BluetoothDevice] by MAC address. Caller holds BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun deviceByAddress(context: Context, address: String): BluetoothDevice {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return mgr.adapter.getRemoteDevice(address)
        }
    }
}
