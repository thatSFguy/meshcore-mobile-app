package io.github.thatsfguy.meshcore.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [Transport] over TCP to a WiFi/Ethernet-attached MeshCore radio
 * (common default hint 192.168.40.10:5000).
 *
 * ⚠️ SECURITY: this link is UNENCRYPTED and UNAUTHENTICATED. Message
 * text and the repeater login password cross the network in the clear,
 * and anyone who can reach host:port can drive the radio. The app keeps
 * this transport off by default behind a stern-warning toggle
 * (SCOPE.md) and flags the connection as plaintext while up
 * ([isPlaintextLink]).
 *
 * Wire framing: the same `<`/`>` + u16-length frames as USB serial
 * ([SerialFraming]), per the MeshCore Open reference client.
 *
 * Structure carried over from reticulum-mobile-app's TcpInterface
 * (including the @Throws/K-N NSError notes and routine-teardown
 * classification).
 */
class TcpInterface(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val socketFactory: (String, Int) -> TcpSocket = ::TcpSocket,
    private val logger: (String) -> Unit = {},
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingFrame>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incoming: Flow<IncomingFrame> = _incoming.asSharedFlow()

    override val isPlaintextLink: Boolean get() = true

    private var socket: TcpSocket? = null
    private var readJob: Job? = null
    private val decoder = SerialFrameDecoder()

    /** Serializes outbound writes so concurrent send() calls can't
     *  interleave framed bytes mid-frame. */
    private val writeMutex = Mutex()

    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    override suspend fun connect() {
        if (_state.value == TransportState.Connected ||
            _state.value == TransportState.Connecting
        ) return

        _state.value = TransportState.Connecting
        try {
            val s = socketFactory(host, port).also { socket = it }
            s.connect()
            decoder.reset()

            readJob = scope.launch {
                try {
                    s.incoming().collect { chunk ->
                        for (packet in decoder.ingest(chunk)) {
                            // '>'-framed packets are radio → client; anything
                            // else on the stream is noise.
                            if (packet.isRxFrame && packet.payload.isNotEmpty()) {
                                _incoming.tryEmit(IncomingFrame(packet.payload))
                            }
                        }
                    }
                    logger("TCP: read loop ended (remote closed) — supervisor will reconnect")
                    _state.value = TransportState.Disconnected
                } catch (t: Throwable) {
                    // Routine OS-initiated teardown (NAT timeout, Doze
                    // socket kill) shouldn't read like a crash.
                    val msg = t.message.orEmpty().lowercase()
                    val isRoutine =
                        msg.contains("connection abort") ||
                            msg.contains("connection reset") ||
                            msg.contains("broken pipe") ||
                            msg.contains("econnaborted") ||
                            msg.contains("econnreset") ||
                            msg.contains("epipe")
                    if (isRoutine) {
                        logger("TCP: read loop ended (${t.message}) — supervisor will reconnect")
                    } else {
                        logger("TCP: read loop crashed: ${t::class.simpleName}: ${t.message}")
                    }
                    _state.value = TransportState.Error
                }
            }
            _state.value = TransportState.Connected
        } catch (t: Throwable) {
            _state.value = TransportState.Error
            socket?.close()
            socket = null
            throw t
        }
    }

    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    override suspend fun disconnect() {
        readJob?.cancel()
        readJob = null
        socket?.close()
        socket = null
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(frame: ByteArray) {
        val s = socket ?: error("TcpInterface not connected")
        val framed = SerialFraming.wrapTx(frame)
        writeMutex.withLock { s.write(framed) }
    }
}
