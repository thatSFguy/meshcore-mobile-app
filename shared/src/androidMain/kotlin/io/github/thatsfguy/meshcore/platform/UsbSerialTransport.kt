package io.github.thatsfguy.meshcore.platform

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.thatsfguy.meshcore.platform.usbserial.MESHCORE_USB_BAUD
import io.github.thatsfguy.meshcore.platform.usbserial.UsbSerialPort
import io.github.thatsfguy.meshcore.platform.usbserial.UsbSerialProber
import io.github.thatsfguy.meshcore.transport.IncomingFrame
import io.github.thatsfguy.meshcore.transport.SerialFrameDecoder
import io.github.thatsfguy.meshcore.transport.SerialFraming
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * USB-serial transport for a USB-attached MeshCore radio. Companion
 * frames ride the `<`/`>` + u16-length serial framing ([SerialFraming])
 * at 115200 8-N-1. A wired link can't be eavesdropped over the air and
 * needs no Bluetooth permissions.
 *
 * Plumbing carried over from reticulum-mobile-app's UsbSerialTransport
 * (CDC-ACM + CP210x via UsbSerialProber); only the framing changed
 * (KISS → MeshCore serial framing). USB permission MUST already be
 * granted before [connect] — the service handles that handshake.
 * Device-detach is detected by the service's USB-detach receiver, which
 * calls [disconnect] (a bulk read can't tell idle from unplug).
 */
class UsbSerialTransport(
    private val context: Context,
    private val device: UsbDevice,
    private val scope: CoroutineScope,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingFrame>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingFrame> = _incoming.asSharedFlow()

    private var port: UsbSerialPort? = null
    private var readJob: Job? = null
    private val writeLock = Mutex()
    private val decoder = SerialFrameDecoder()

    override suspend fun connect() {
        if (_state.value == TransportState.Connected ||
            _state.value == TransportState.Connecting
        ) return
        _state.value = TransportState.Connecting
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usbManager.hasPermission(device)) {
                error("USB permission not granted for ${device.deviceName}")
            }
            val p = withContext(Dispatchers.IO) {
                val conn = usbManager.openDevice(device)
                    ?: error("Failed to open USB device ${device.deviceName}")
                UsbSerialProber.open(device, conn, MESHCORE_USB_BAUD)
                    ?: run {
                        conn.close()
                        error(
                            "No USB-serial driver for VID=0x${device.vendorId.toString(16)} " +
                                "PID=0x${device.productId.toString(16)}",
                        )
                    }
            }
            port = p
            decoder.reset()

            readJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(4096)
                try {
                    while (isActive) {
                        val n = p.read(buf, READ_TIMEOUT_MS)
                        if (n > 0) {
                            for (packet in decoder.ingest(buf.copyOf(n))) {
                                if (packet.isRxFrame && packet.payload.isNotEmpty()) {
                                    _incoming.tryEmit(IncomingFrame(packet.payload))
                                }
                            }
                        }
                        // n <= 0 is an idle timeout (bulkTransfer can't
                        // distinguish idle from unplug); keep looping.
                    }
                } catch (t: Throwable) {
                    _state.value = TransportState.Error
                }
            }
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            closeQuietly()
            throw t
        }
    }

    override suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        closeQuietly()
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(frame: ByteArray) {
        val p = port ?: error("UsbSerialTransport not connected")
        val framed = SerialFraming.wrapTx(frame)
        writeLock.withLock {
            withContext(Dispatchers.IO) { p.write(framed, WRITE_TIMEOUT_MS) }
        }
    }

    private fun closeQuietly() {
        runCatching { port?.close() }
        port = null
    }

    companion object {
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 2000

        /** Resolve an attached [UsbDevice] by its system device name. */
        fun deviceByName(context: Context, deviceName: String): UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
        }
    }
}
