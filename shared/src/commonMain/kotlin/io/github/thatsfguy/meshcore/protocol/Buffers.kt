package io.github.thatsfguy.meshcore.protocol

/**
 * Bounds-checked sequential reader over a frame. Every read throws
 * [TruncatedFrameException] on short input — parsers catch it at the
 * frame boundary so a hostile/truncated frame can never crash the RX
 * path (MESHCORE_PROTOCOL.md §12 "guard every parse").
 */
class BufferReader(private val buffer: ByteArray) {
    private var pointer = 0

    val remaining: Int get() = buffer.size - pointer

    fun readByte(): Int {
        ensure(1)
        return buffer[pointer++].toInt() and 0xFF
    }

    fun readInt8(): Int {
        ensure(1)
        return buffer[pointer++].toInt()
    }

    fun readBytes(count: Int): ByteArray {
        ensure(count)
        val out = buffer.copyOfRange(pointer, pointer + count)
        pointer += count
        return out
    }

    fun skipBytes(count: Int) {
        ensure(count)
        pointer += count
    }

    fun readRemainingBytes(): ByteArray = readBytes(remaining)

    fun readUInt16LE(): Int {
        ensure(2)
        val v = (buffer[pointer].toInt() and 0xFF) or
            ((buffer[pointer + 1].toInt() and 0xFF) shl 8)
        pointer += 2
        return v
    }

    fun readUInt32LE(): Long {
        ensure(4)
        val v = (buffer[pointer].toLong() and 0xFF) or
            ((buffer[pointer + 1].toLong() and 0xFF) shl 8) or
            ((buffer[pointer + 2].toLong() and 0xFF) shl 16) or
            ((buffer[pointer + 3].toLong() and 0xFF) shl 24)
        pointer += 4
        return v
    }

    fun readInt32LE(): Int {
        ensure(4)
        val v = (buffer[pointer].toInt() and 0xFF) or
            ((buffer[pointer + 1].toInt() and 0xFF) shl 8) or
            ((buffer[pointer + 2].toInt() and 0xFF) shl 16) or
            ((buffer[pointer + 3].toInt() and 0xFF) shl 24)
        pointer += 4
        return v
    }

    /**
     * Read up to the next NUL (or [maxLength]/end of buffer) as UTF-8.
     * Consumes the terminating NUL when present.
     */
    fun readCString(maxLength: Int = -1): String {
        val bytes = ArrayList<Byte>(32)
        val maxLen = if (maxLength >= 0) minOf(maxLength, remaining) else remaining
        var counter = 0
        while (counter < maxLen) {
            val b = buffer[pointer++]
            if (b.toInt() == 0) break
            bytes.add(b)
            counter++
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * Read a fixed [width]-byte field and decode the bytes before the
     * first NUL as UTF-8 (the "cstr(n)" wire form). Always consumes
     * exactly [width] bytes.
     */
    fun readFixedCString(width: Int): String {
        val raw = readBytes(width)
        val end = raw.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) raw.size else it }
        return raw.copyOfRange(0, end).decodeToString()
    }

    private fun ensure(count: Int) {
        if (pointer + count > buffer.size) {
            throw TruncatedFrameException(
                "Read of $count bytes at offset $pointer overruns buffer of ${buffer.size}",
            )
        }
    }
}

class TruncatedFrameException(message: String) : Exception(message)

/** Accumulating little-endian frame builder. */
class BufferWriter {
    private val bytes = ArrayList<Byte>(64)

    fun toBytes(): ByteArray = bytes.toByteArray()

    fun writeByte(b: Int) {
        bytes.add((b and 0xFF).toByte())
    }

    fun writeBytes(data: ByteArray) {
        for (b in data) bytes.add(b)
    }

    fun writeUInt16LE(v: Int) {
        writeByte(v)
        writeByte(v ushr 8)
    }

    fun writeUInt32LE(v: Long) {
        writeByte((v and 0xFF).toInt())
        writeByte(((v ushr 8) and 0xFF).toInt())
        writeByte(((v ushr 16) and 0xFF).toInt())
        writeByte(((v ushr 24) and 0xFF).toInt())
    }

    fun writeInt32LE(v: Int) {
        writeByte(v)
        writeByte(v ushr 8)
        writeByte(v ushr 16)
        writeByte(v ushr 24)
    }

    fun writeString(s: String) {
        writeBytes(s.encodeToByteArray())
    }

    /** Write [s] into a fixed [width]-byte NUL-padded field (last byte
     *  always NUL, matching the reference client's writeCString). */
    fun writeFixedCString(s: String, width: Int) {
        val encoded = s.encodeToByteArray()
        for (i in 0 until width) {
            bytes.add(if (i < width - 1 && i < encoded.size) encoded[i] else 0)
        }
    }

    /** Write [data] zero-padded (or truncated) to exactly [totalLength] bytes. */
    fun writeBytesPadded(data: ByteArray, totalLength: Int) {
        for (i in 0 until totalLength) {
            bytes.add(if (i < data.size) data[i] else 0)
        }
    }
}
