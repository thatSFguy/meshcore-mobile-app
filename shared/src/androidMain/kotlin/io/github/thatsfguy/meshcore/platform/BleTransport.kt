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
import io.github.thatsfguy.meshcore.transport.IncomingFrame
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingFrame>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingFrame> = _incoming.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23 // ATT minimum

    private val writeLock = Mutex()

    // Each callback completion resumes the corresponding suspending operation.
    private var servicesContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var mtuContinuation: kotlinx.coroutines.CancellableContinuation<Int>? = null
    private var descWriteContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = TransportState.Connecting
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = TransportState.Disconnected
                    servicesContinuation?.resumeWithException(
                        IllegalStateException("BLE disconnected before ready (status=$status)"),
                    )
                    servicesContinuation = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesContinuation?.resumeWithException(
                    IllegalStateException("Service discovery failed: $status"),
                )
            } else {
                servicesContinuation?.resume(Unit)
            }
            servicesContinuation = null
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                mtuContinuation?.resume(mtu)
            } else {
                mtuContinuation?.resume(negotiatedMtu) // proceed with default
            }
            mtuContinuation = null
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descWriteContinuation?.resume(Unit)
            } else {
                descWriteContinuation?.resumeWithException(
                    IllegalStateException("CCCD write failed: $status"),
                )
            }
            descWriteContinuation = null
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
    }

    override suspend fun connect() {
        if (_state.value == TransportState.Connected) return
        _state.value = TransportState.Connecting
        try {
            connectAndDiscover()
            requestMtu(247)
            findNusCharacteristics()
            enableNotifications()
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            disconnectInternal()
            throw t
        }
    }

    private suspend fun connectAndDiscover() {
        val g = device.connectGatt(context, false, callback)
        gatt = g
        // The connect callback chains into service discovery; suspend on
        // the services callback rather than the raw connection.
        suspendCancellableCoroutine<Unit> { cont ->
            servicesContinuation = cont
            cont.invokeOnCancellation { disconnectInternal() }
        }
    }

    private suspend fun requestMtu(target: Int): Int =
        suspendCancellableCoroutine { cont ->
            mtuContinuation = cont
            val ok = gatt?.requestMtu(target) ?: false
            if (!ok) {
                mtuContinuation = null
                cont.resume(negotiatedMtu)
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
        suspendCancellableCoroutine<Unit> { cont ->
            descWriteContinuation = cont
            if (!g.writeDescriptor(cccd)) {
                descWriteContinuation = null
                cont.resumeWithException(IllegalStateException("writeDescriptor returned false"))
            }
        }
    }

    override suspend fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
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
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        /** Client → radio (write). */
        val NUS_WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        /** Radio → client (notify). */
        val NUS_NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Resolve a BLE [BluetoothDevice] by MAC address. Caller holds BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun deviceByAddress(context: Context, address: String): BluetoothDevice {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            return mgr.adapter.getRemoteDevice(address)
        }
    }
}
