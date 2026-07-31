package io.github.thatsfguy.meshcore.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Android JVM implementation of [TcpSocket] backed by [java.net.Socket].
 * Carried over from reticulum-mobile-app (TcpSocket.android.kt):
 * 5s connect timeout, no read timeout, Nagle off, OS keepalive with
 * short probe timings where ExtendedSocketOptions is available.
 */
actual class TcpSocket actual constructor(
    actual val host: String,
    actual val port: Int,
) {
    private var socket: Socket? = null

    actual suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5_000)
        s.tcpNoDelay = true
        // Half-open connections (NAT idle timeout) otherwise sit dead
        // until the next write; JDK default keepalive idle is 2h.
        s.keepAlive = true
        runCatching {
            val cls = Class.forName("jdk.net.ExtendedSocketOptions")
            val get: (String) -> java.net.SocketOption<Int>? = { name ->
                @Suppress("UNCHECKED_CAST")
                runCatching { cls.getField(name).get(null) as java.net.SocketOption<Int> }.getOrNull()
            }
            get("TCP_KEEPIDLE")?.let { s.setOption(it, 5) }
            get("TCP_KEEPINTVL")?.let { s.setOption(it, 2) }
            get("TCP_KEEPCNT")?.let { s.setOption(it, 12) }
        }
        socket = s
    }

    actual suspend fun close() = withContext(Dispatchers.IO) {
        socket?.close()
        socket = null
    }

    actual suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val s = socket ?: error("TcpSocket not connected")
        val out = s.getOutputStream()
        out.write(bytes)
        out.flush()
    }

    actual fun incoming(): Flow<ByteArray> = flow {
        val s = socket ?: error("TcpSocket not connected — call connect() first")
        val input = s.getInputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            emit(buf.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)
}
