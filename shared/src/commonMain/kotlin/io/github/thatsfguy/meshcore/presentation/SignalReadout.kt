package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.RadioStats

/**
 * Wording for the connected radio's own signal readings (issue #6), shown
 * on Settings → Radio.
 *
 * The rules here are about not overstating what the numbers are:
 *  - the noise floor is the one genuinely live value, but 0 means the
 *    firmware has not measured it yet and -120 is a clamp, not a reading;
 *  - "last packet" RSSI/SNR is whichever node the radio decoded last, and
 *    does not move until something new arrives — it is not a meter for
 *    any particular link (Ping is the tool for that).
 */
object SignalReadout {

    fun noiseFloor(dbm: Int): String = when {
        dbm == 0 -> "Measuring…"
        dbm <= RadioStats.NOISE_FLOOR_CLAMP -> "${RadioStats.NOISE_FLOOR_CLAMP} dBm or lower"
        else -> "$dbm dBm"
    }

    /**
     * The last packet heard, or that nothing has been. With packet counters
     * available, zero received is decisive; without them an all-zero
     * reading is the firmware's untouched initial state.
     */
    fun lastPacket(radio: RadioStats.Radio, packets: RadioStats.Packets?): String {
        val nothingYet = if (packets != null) {
            packets.received == 0L
        } else {
            radio.lastRssiDbm == 0 && radio.lastSnrDb == 0.0
        }
        if (nothingYet) return "Nothing received since the radio started"
        return "${radio.lastRssiDbm} dBm · ${MessageSignal.db(radio.lastSnrDb)}"
    }

    /** "1204 received · 9 sent · 3 receive errors", or null without counters. */
    fun counters(packets: RadioStats.Packets?): String? {
        packets ?: return null
        val errors = if (packets.receiveErrors == 1L) "1 receive error" else "${packets.receiveErrors} receive errors"
        return "${packets.received} received · ${packets.sent} sent · $errors"
    }

    /**
     * Noise-floor samples taken while the screen is open. Unmeasured (0)
     * samples are left out: a "max of 0 dBm" would be the firmware's
     * placeholder reported as the loudest moment.
     */
    class NoiseWatch {
        private val samples = ArrayList<Int>()

        fun add(dbm: Int) {
            if (dbm == 0) return
            samples += dbm
            if (samples.size > MAX_SAMPLES) samples.removeAt(0)
        }

        /** "low -118 · high -104 dBm over 12 readings", or null with too few. */
        fun range(): String? {
            if (samples.size < 2) return null
            return "low ${samples.min()} · high ${samples.max()} dBm over ${samples.size} readings"
        }

        companion object {
            /** Two minutes at the screen's 2 s poll. */
            const val MAX_SAMPLES = 60
        }
    }
}
