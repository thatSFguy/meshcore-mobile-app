package io.github.thatsfguy.meshcore.protocol

/**
 * The connected radio's own RF statistics: `CMD_GET_STATS` (56) answered
 * with `RESP_CODE_STATS` (24). Companion protocol v8+.
 *
 * Layouts from the firmware's writer, `examples/companion_radio/MyMesh.cpp`
 * (the `CMD_GET_STATS` branch, ~line 1914), which agrees byte for byte
 * with `docs/stats_binary_frames.md`. All little-endian. [DeviceEvent.Stats]
 * carries the bytes AFTER `[code][stats_type]`.
 *
 * A local query over BLE/USB to our own radio — it costs no airtime, which
 * is the difference from a repeater's noise-floor watch.
 */
object RadioStats {

    /**
     * STATS_TYPE_RADIO (1):
     * ```
     * i16 noise_floor · i8 last_rssi · i8 last_snr (×4) · u32 tx_air_secs · u32 rx_air_secs
     * ```
     */
    data class Radio(
        /**
         * dBm. **0 means not measured yet** — the firmware starts at 0 and
         * resets to 0 to reconverge (`RadioLibWrappers.cpp:37`, `:85`).
         * Clamped at [NOISE_FLOOR_CLAMP] (`:101-103`).
         */
        val noiseFloorDbm: Int,
        /**
         * The last packet decoded, from ANY node. Truncated to int8 by the
         * firmware, so nothing below -128 dBm can be reported.
         */
        val lastRssiDbm: Int,
        val lastSnrDb: Double,
        val txAirSecs: Long,
        val rxAirSecs: Long,
    )

    /**
     * STATS_TYPE_PACKETS (2): seven u32 counters since boot.
     * Only the totals and receive errors are shown; the rest are kept so
     * the frame is read whole and its length checked.
     */
    data class Packets(
        val received: Long,
        val sent: Long,
        val sentFlood: Long,
        val sentDirect: Long,
        val receivedFlood: Long,
        val receivedDirect: Long,
        val receiveErrors: Long,
    )

    /** The firmware's lower clamp on the noise floor. */
    const val NOISE_FLOOR_CLAMP = -120

    const val RADIO_PAYLOAD_SIZE = 12
    const val PACKETS_PAYLOAD_SIZE = 28

    /** Null unless [statsType] is RADIO and the payload is the full 12 bytes. */
    fun parseRadio(statsType: Int, payload: ByteArray): Radio? {
        if (statsType != Codes.STATS_TYPE_RADIO || payload.size < RADIO_PAYLOAD_SIZE) return null
        val r = BufferReader(payload)
        return Radio(
            noiseFloorDbm = r.readInt16LE(),
            lastRssiDbm = r.readInt8(),
            lastSnrDb = r.readInt8() / 4.0,
            txAirSecs = r.readUInt32LE(),
            rxAirSecs = r.readUInt32LE(),
        )
    }

    /** Null unless [statsType] is PACKETS and the payload is the full 28 bytes. */
    fun parsePackets(statsType: Int, payload: ByteArray): Packets? {
        if (statsType != Codes.STATS_TYPE_PACKETS || payload.size < PACKETS_PAYLOAD_SIZE) return null
        val r = BufferReader(payload)
        return Packets(
            received = r.readUInt32LE(),
            sent = r.readUInt32LE(),
            sentFlood = r.readUInt32LE(),
            sentDirect = r.readUInt32LE(),
            receivedFlood = r.readUInt32LE(),
            receivedDirect = r.readUInt32LE(),
            receiveErrors = r.readUInt32LE(),
        )
    }
}
