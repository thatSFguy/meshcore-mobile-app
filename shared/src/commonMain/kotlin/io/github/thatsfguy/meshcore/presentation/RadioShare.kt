package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.PathHashMode
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.ShareUri

/**
 * The mesh-settings code this phone would hand someone: its own radio's
 * values, as `meshcore://radio/set`.
 *
 * It refuses rather than guesses. [PathHashMode.modeFor] clamps an
 * unrecognised width, which is right for a chip on this phone's own
 * screen — wrong by one, locally. In a code it is wrong for everyone who
 * scans it, and the hop-hash width is the mistake this codebase has made
 * four times.
 */
object RadioShare {
    fun uriFor(
        frequencyKhz: Long,
        bandwidthHz: Long,
        spreadingFactor: Int,
        codingRate: Int,
        /** DEVICE_INFO's width in bytes; 0 or null when the radio didn't say. */
        pathHashByteWidth: Int?,
        /** This phone's own region; blank or null for none. */
        region: String?,
        /** The preset the radio matches, when exactly one does. */
        meshName: String?,
    ): String? {
        val width = pathHashByteWidth ?: return null
        val mode = width - 1
        if (!PathHashMode.isValid(mode)) return null
        return ShareUri.encodeRadio(
            name = meshName.orEmpty(),
            frequencyKhz = frequencyKhz,
            bandwidthHz = bandwidthHz,
            spreadingFactor = spreadingFactor,
            codingRate = codingRate,
            pathHashMode = mode,
            region = region?.let { Regions.canonical(it) },
        )
    }
}
