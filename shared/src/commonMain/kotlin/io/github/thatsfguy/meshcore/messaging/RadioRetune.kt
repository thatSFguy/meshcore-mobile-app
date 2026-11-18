package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.protocol.RadioPresets
import io.github.thatsfguy.meshcore.protocol.ShareUri

/**
 * Retuning the connected radio from a regional preset or a scanned
 * settings code — the multi-step writes, in the order that is safe, with
 * every step's answer checked.
 *
 * Lifted from Android's ViewModel for iOS (2026-11-18). Android's
 * scanned-code path ignored the results of the radio-params and
 * hash-width writes, so a refusal was still reported as "Applied"; here
 * a refused step is reported as refused.
 */
class RadioRetune(private val radio: Radio) {

    interface Radio {
        suspend fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): Boolean
        suspend fun setTxPower(dbm: Int): Boolean
        suspend fun setPathHashMode(mode: Int): Boolean
        suspend fun setFloodScope(region: String?): Boolean
    }

    /** What happened, in a sentence; [ok] is false when nothing useful changed. */
    data class Outcome(val ok: Boolean, val message: String)

    /**
     * Frequency, bandwidth, SF and CR first, THEN power: a refused power
     * leaves the radio on the right band at its old power, which is
     * recoverable; the other order could leave it transmitting at a
     * power chosen for a band it is no longer on.
     */
    suspend fun applyPreset(preset: RadioPresets.Preset): Outcome {
        val params = step {
            radio.setRadioParams(preset.frequencyKhz, preset.bandwidthHz, preset.spreadingFactor, preset.codingRate)
        }
        if (!params) return Outcome(false, "Radio rejected the ${preset.name} parameters")
        val power = step { radio.setTxPower(preset.txPowerDbm) }
        return if (power) {
            Outcome(true, "Applied ${preset.name}")
        } else {
            Outcome(true, "Applied ${preset.name}, but TX power stayed unchanged — set it by hand")
        }
    }

    /**
     * A scanned settings code: radio params, then hop-hash width, then —
     * only if the code named a valid region AND the person left it
     * ticked ([pickedRegion]) — the flood scope. TX power is never set
     * from a code: it is the scanner's legal limit, not the sharer's.
     */
    suspend fun applyScanned(config: ShareUri.Decoded.RadioConfig, pickedRegion: String?): Outcome {
        val label = config.name.ifBlank { "scanned settings" }
        val params = step {
            radio.setRadioParams(config.frequencyKhz, config.bandwidthHz, config.spreadingFactor, config.codingRate)
        }
        if (!params) return Outcome(false, "The radio refused the settings in $label; nothing was changed")
        val width = step { radio.setPathHashMode(config.pathHashMode) }
        val base = if (width) {
            "Applied $label"
        } else {
            "Applied $label, but the radio refused the hop-hash width — older firmware may not support it"
        }
        val region = config.regionToApply(pickedRegion) ?: return Outcome(true, base)
        return if (step { radio.setFloodScope(region) }) {
            Outcome(true, "$base · region #$region")
        } else {
            Outcome(true, "$base, but the radio refused region #$region")
        }
    }

    private suspend fun step(write: suspend () -> Boolean): Boolean =
        runCatching { write() }.getOrDefault(false)

    companion object {
        fun over(engine: MeshCoreEngine): RadioRetune = RadioRetune(object : Radio {
            override suspend fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int) =
                engine.setRadioParams(freqKhz, bwHz, sf, cr)
            override suspend fun setTxPower(dbm: Int) = engine.setTxPower(dbm)
            override suspend fun setPathHashMode(mode: Int) = engine.setPathHashMode(mode)
            override suspend fun setFloodScope(region: String?) = engine.setFloodScope(region)
        })
    }
}
