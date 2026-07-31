package io.github.thatsfguy.meshcore.transport

import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * Companion-frame stream framing for USB serial and TCP.
 *
 * Wire format (both directions): `[start][len_lo][len_hi][payload…]`
 * where start is '<' (0x3C) for client → radio and '>' (0x3E) for
 * radio → client, and len is a little-endian u16 payload length capped
 * at MAX_FRAME_SIZE.
 *
 * NOTE: MESHCORE_PROTOCOL.md §2 described the USB path as "COBS-framed";
 * the MeshCore Open reference client (usb_serial_frame_codec.dart) —
 * validated against real firmware — actually uses this start-byte +
 * length framing for BOTH USB serial and TCP. This port follows the
 * reference client. BLE needs no framing (one frame per GATT write /
 * notification).
 */
object SerialFraming {
    const val TX_FRAME_START = 0x3C // '<'
    const val RX_FRAME_START = 0x3E // '>'
    const val HEADER_LENGTH = 3
    const val MAX_PAYLOAD_LENGTH = Codes.MAX_FRAME_SIZE

    /** Wrap an outbound companion frame for the serial/TCP link. */
    fun wrapTx(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_LENGTH) {
            "payload ${payload.size} exceeds $MAX_PAYLOAD_LENGTH bytes"
        }
        val out = ByteArray(HEADER_LENGTH + payload.size)
        out[0] = TX_FRAME_START.toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, HEADER_LENGTH)
        return out
    }
}

class DecodedSerialFrame(val frameStart: Int, val payload: ByteArray) {
    val isRxFrame: Boolean get() = frameStart == SerialFraming.RX_FRAME_START
}

/**
 * Streaming decoder: feed arbitrary byte chunks, get complete frames.
 * Resyncs on garbage by scanning forward to the next start byte, and
 * drops frames whose length field exceeds MAX_PAYLOAD_LENGTH (ported
 * from the reference client's UsbSerialFrameDecoder).
 */
class SerialFrameDecoder {
    private val rxBuffer = ArrayList<Byte>(512)
    private var startIndex = 0

    fun reset() {
        rxBuffer.clear()
        startIndex = 0
    }

    fun ingest(bytes: ByteArray): List<DecodedSerialFrame> {
        if (bytes.isEmpty()) return emptyList()
        for (b in bytes) rxBuffer.add(b)
        val packets = ArrayList<DecodedSerialFrame>()

        while (true) {
            if (startIndex >= rxBuffer.size) {
                rxBuffer.clear()
                startIndex = 0
                return packets
            }

            val head = rxBuffer[startIndex].toInt() and 0xFF
            if (head != SerialFraming.RX_FRAME_START && head != SerialFraming.TX_FRAME_START) {
                startIndex++
                compactIfNeeded()
                continue
            }

            val available = rxBuffer.size - startIndex
            if (available < SerialFraming.HEADER_LENGTH) {
                compactIfNeeded(force = true)
                return packets
            }

            val payloadLength = (rxBuffer[startIndex + 1].toInt() and 0xFF) or
                ((rxBuffer[startIndex + 2].toInt() and 0xFF) shl 8)
            if (payloadLength > SerialFraming.MAX_PAYLOAD_LENGTH) {
                // Not a real header — resync one byte forward.
                startIndex++
                compactIfNeeded()
                continue
            }

            val packetLength = SerialFraming.HEADER_LENGTH + payloadLength
            if (available < packetLength) {
                compactIfNeeded(force = true)
                return packets
            }

            val payload = ByteArray(payloadLength)
            for (i in 0 until payloadLength) {
                payload[i] = rxBuffer[startIndex + SerialFraming.HEADER_LENGTH + i]
            }
            packets.add(DecodedSerialFrame(head, payload))
            startIndex += packetLength
        }
    }

    /** Trim consumed bytes so the buffer can't grow without bound. */
    private fun compactIfNeeded(force: Boolean = false) {
        if (startIndex == 0) return
        if (force || startIndex > 4096) {
            val remaining = rxBuffer.subList(startIndex, rxBuffer.size).toMutableList()
            rxBuffer.clear()
            rxBuffer.addAll(remaining)
            startIndex = 0
        }
    }
}
