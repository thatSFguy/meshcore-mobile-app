package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex

/**
 * Decoded repeater/room status (PUSH_CODE_STATUS_RESPONSE, 0x87).
 *
 * Wire layout, ported from the reference client's
 * `_handleStatusResponse`:
 * ```
 * [0]      0x87
 * [1]      reserved
 * [2..7]   sender pubkey prefix (6)
 * [8..]    u16 battery_mv · u16 queue_len · i16 noise_floor · i16 last_rssi
 *          u32 packets_recv · u32 packets_sent · u32 tx_air_secs
 *          u32 uptime_secs · u32 flood_tx · u32 direct_tx
 *          u32 flood_rx · u32 direct_rx
 *          u16 err_events · i16 last_snr(×4) · u16 direct_dups · u16 flood_dups
 *          u32 rx_air_secs
 * ```
 */
data class RepeaterStatus(
    val senderPrefixHex: String,
    val batteryMillivolts: Int,
    val queueLength: Int,
    val noiseFloor: Int,
    val lastRssi: Int,
    val packetsReceived: Long,
    val packetsSent: Long,
    val txAirSeconds: Long,
    val uptimeSeconds: Long,
    val floodTx: Long,
    val directTx: Long,
    val floodRx: Long,
    val directRx: Long,
    val errorEvents: Int,
    val lastSnr: Double,
    val directDuplicates: Int,
    val floodDuplicates: Int,
    val rxAirSeconds: Long,
) {
    /** Fraction of uptime spent transmitting or receiving, as a percent. */
    val channelUtilizationPercent: Double
        get() = if (uptimeSeconds <= 0) 0.0
        else (txAirSeconds + rxAirSeconds) * 100.0 / uptimeSeconds

    val batteryVolts: Double get() = batteryMillivolts / 1000.0
}

object StatusCodec {

    /** Payload begins after code + reserved + 6-byte sender prefix. */
    private const val PAYLOAD_OFFSET = 8

    /** Full stats block: 2+2+2+2 +4*8 +2+2+2+2 +4 = 52 bytes. */
    private const val STATS_SIZE = 52

    /** Parse a status push; null when truncated or not a status frame. */
    fun parse(frame: ByteArray): RepeaterStatus? {
        if (frame.size < PAYLOAD_OFFSET + STATS_SIZE) return null
        return try {
            val r = BufferReader(frame)
            r.skipBytes(2) // code + reserved
            val prefix = r.readBytes(6).toHex()
            RepeaterStatus(
                senderPrefixHex = prefix,
                batteryMillivolts = r.readUInt16LE(),
                queueLength = r.readUInt16LE(),
                noiseFloor = r.readInt16LE(),
                lastRssi = r.readInt16LE(),
                packetsReceived = r.readUInt32LE(),
                packetsSent = r.readUInt32LE(),
                txAirSeconds = r.readUInt32LE(),
                uptimeSeconds = r.readUInt32LE(),
                floodTx = r.readUInt32LE(),
                directTx = r.readUInt32LE(),
                floodRx = r.readUInt32LE(),
                directRx = r.readUInt32LE(),
                errorEvents = r.readUInt16LE(),
                lastSnr = r.readInt16LE() / 4.0,
                directDuplicates = r.readUInt16LE(),
                floodDuplicates = r.readUInt16LE(),
                rxAirSeconds = r.readUInt32LE(),
            )
        } catch (_: TruncatedFrameException) {
            null
        }
    }

    /** Human-readable uptime ("3d 4h 12m"). */
    fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "—"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (d > 0 || h > 0) append("${h}h ")
            append("${m}m")
        }.trim()
    }
}

/**
 * Cayenne LPP telemetry (PUSH_CODE_TELEMETRY_RESPONSE, 0x8B) — the
 * subset MeshCore sensors actually emit.
 *
 * Each record is `[channel][type][data…]`; unknown types abort parsing
 * of the remainder (their length is unknown), which keeps a malformed
 * or newer-firmware payload from being silently misread.
 */
data class TelemetryReading(
    val channel: Int,
    val type: Int,
    val label: String,
    val value: Double,
    val unit: String,
)

object CayenneLpp {

    private const val DIGITAL_INPUT = 0x00
    private const val DIGITAL_OUTPUT = 0x01
    private const val ANALOG_INPUT = 0x02
    private const val ANALOG_OUTPUT = 0x03
    private const val LUMINOSITY = 0x65
    private const val PRESENCE = 0x66
    private const val TEMPERATURE = 0x67
    private const val HUMIDITY = 0x68
    private const val ACCELEROMETER = 0x71
    private const val BAROMETER = 0x73
    private const val VOLTAGE = 0x74
    private const val CURRENT = 0x75
    private const val POWER = 0x80
    private const val ALTITUDE = 0x79
    private const val GPS = 0x88

    fun parse(payload: ByteArray): List<TelemetryReading> {
        val out = ArrayList<TelemetryReading>()
        val r = BufferReader(payload)
        try {
            while (r.remaining >= 2) {
                val channel = r.readByte()
                when (val type = r.readByte()) {
                    DIGITAL_INPUT ->
                        out.add(reading(channel, type, "Digital in", r.readByte().toDouble(), ""))
                    DIGITAL_OUTPUT ->
                        out.add(reading(channel, type, "Digital out", r.readByte().toDouble(), ""))
                    ANALOG_INPUT ->
                        out.add(reading(channel, type, "Analog in", r.readInt16LEBig() / 100.0, ""))
                    ANALOG_OUTPUT ->
                        out.add(reading(channel, type, "Analog out", r.readInt16LEBig() / 100.0, ""))
                    LUMINOSITY ->
                        out.add(reading(channel, type, "Luminosity", r.readUInt16BE().toDouble(), "lux"))
                    PRESENCE ->
                        out.add(reading(channel, type, "Presence", r.readByte().toDouble(), ""))
                    TEMPERATURE ->
                        out.add(reading(channel, type, "Temperature", r.readInt16LEBig() / 10.0, "°C"))
                    HUMIDITY ->
                        out.add(reading(channel, type, "Humidity", r.readByte() / 2.0, "%"))
                    BAROMETER ->
                        out.add(reading(channel, type, "Pressure", r.readUInt16BE() / 10.0, "hPa"))
                    VOLTAGE ->
                        out.add(reading(channel, type, "Voltage", r.readUInt16BE() / 100.0, "V"))
                    CURRENT ->
                        out.add(reading(channel, type, "Current", r.readUInt16BE() / 1000.0, "A"))
                    POWER ->
                        out.add(reading(channel, type, "Power", r.readUInt16BE().toDouble(), "W"))
                    ALTITUDE ->
                        out.add(reading(channel, type, "Altitude", r.readInt16LEBig().toDouble(), "m"))
                    ACCELEROMETER -> {
                        val x = r.readInt16LEBig() / 1000.0
                        val y = r.readInt16LEBig() / 1000.0
                        val z = r.readInt16LEBig() / 1000.0
                        out.add(reading(channel, type, "Accel X", x, "g"))
                        out.add(reading(channel, type, "Accel Y", y, "g"))
                        out.add(reading(channel, type, "Accel Z", z, "g"))
                    }
                    GPS -> {
                        // 3 × 24-bit big-endian.
                        val lat = r.readInt24BE() / 10_000.0
                        val lon = r.readInt24BE() / 10_000.0
                        val alt = r.readInt24BE() / 100.0
                        out.add(reading(channel, type, "Latitude", lat, "°"))
                        out.add(reading(channel, type, "Longitude", lon, "°"))
                        out.add(reading(channel, type, "Altitude", alt, "m"))
                    }
                    // Unknown type: its length is unknown, so stop rather
                    // than misinterpret the rest of the buffer.
                    else -> return out
                }
            }
        } catch (_: TruncatedFrameException) {
            return out
        }
        return out
    }

    private fun reading(channel: Int, type: Int, label: String, value: Double, unit: String) =
        TelemetryReading(channel, type, label, value, unit)
}

/** Cayenne uses big-endian; these helpers keep the call sites readable. */
private fun BufferReader.readUInt16BE(): Int {
    val hi = readByte()
    val lo = readByte()
    return (hi shl 8) or lo
}

private fun BufferReader.readInt16LEBig(): Int {
    val v = readUInt16BE()
    return if (v >= 0x8000) v - 0x10000 else v
}

private fun BufferReader.readInt24BE(): Int {
    val v = (readByte() shl 16) or (readByte() shl 8) or readByte()
    return if (v and 0x800000 != 0) v - 0x1000000 else v
}
