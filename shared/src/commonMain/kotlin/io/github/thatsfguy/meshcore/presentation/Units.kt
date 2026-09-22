package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.util.fixed

/**
 * Metric or US customary, for the handful of readings where the choice
 * is real.
 *
 * **Conversion happens at DISPLAY time and nowhere else.** The mesh
 * speaks metric: a Cayenne LPP temperature is tenths of a degree
 * Celsius and an altitude is metres, both fixed by the sensor encoding,
 * and a distance is computed from coordinates. Storing a converted
 * value — or converting in the parser, where the unit string is
 * currently attached — would put the user's preference into a record of
 * what a node actually sent, and that record would be wrong the moment
 * the preference changed. So the wire value stays canonical and this
 * object is the last thing to touch it.
 */
enum class UnitSystem {
    Metric,
    Imperial,
    ;

    companion object {
        /**
         * The system a country conventionally uses.
         *
         * Three countries do not use metric for everyday measures —
         * the United States, Liberia and Myanmar — and everywhere else
         * does. That is the whole rule; it is small enough to state
         * outright and stable enough not to need a table.
         *
         * An unknown or malformed code falls to [Metric], because the
         * odds say so and because a wrong guess here is a cosmetic
         * annoyance the user can fix in one tap.
         */
        fun forCountry(code: String?): UnitSystem {
            val c = code?.trim()?.uppercase() ?: return Metric
            return if (c in setOf("US", "LR", "MM")) Imperial else Metric
        }

        /** Preference spellings; [FOLLOW_SYSTEM] defers to the device. */
        const val FOLLOW_SYSTEM = "system"
        const val METRIC = "metric"
        const val IMPERIAL = "imperial"

        /**
         * Resolve a stored preference against the device's country.
         *
         * Anything unrecognised — including a value written by a newer
         * build and restored into an older one — follows the device
         * rather than failing or picking a side.
         */
        fun resolve(preference: String?, deviceCountry: String?): UnitSystem =
            when (preference?.trim()?.lowercase()) {
                METRIC -> Metric
                IMPERIAL -> Imperial
                else -> forCountry(deviceCountry)
            }
    }
}

/**
 * The conversions themselves, and the short list of what is NOT
 * converted.
 *
 * Untouched on purpose: **hPa** (millibars are what every pressure
 * sensor and every forecast outside a US weather bulletin reports),
 * **lux**, **V**, **A**, **W**, **g**, **%**, **°** of latitude, and
 * every radio figure — dB, dBm, MHz, kHz. None of those has a US
 * customary counterpart a mesh operator would rather read; converting
 * them would be change for its own sake, and inHg in particular would
 * make a pressure reading harder to compare with the node next to it.
 */
object Units {

    const val FEET_PER_METRE = 3.280839895013123
    const val METRES_PER_MILE = 1609.344

    /** Canonical unit strings as the protocol layer attaches them. */
    const val CELSIUS = "°C"
    const val METRES = "m"

    /**
     * A distance, in the unit the reader asked for.
     *
     * One switch point per system, and they mirror each other: metres
     * below a kilometre, feet below a mile. Mesh work genuinely cares
     * about the small end — whether a node is across the street or
     * across town — so the fine unit is kept right up to the boundary
     * rather than switching at some tenth of a mile.
     *
     * Past 100 the fraction is dropped: a tenth of a kilometre is
     * noise on a 150 km link, and it costs a digit that the row does
     * not have room for.
     */
    fun distance(metres: Double, system: UnitSystem): String = when (system) {
        UnitSystem.Metric -> when {
            metres < 1_000 -> "${fixed(metres, 0)} m"
            metres < 100_000 -> "${fixed(metres / 1_000, 1)} km"
            else -> "${fixed(metres / 1_000, 0)} km"
        }
        UnitSystem.Imperial -> {
            val miles = metres / METRES_PER_MILE
            when {
                metres < METRES_PER_MILE -> "${fixed(metres * FEET_PER_METRE, 0)} ft"
                miles < 100 -> "${fixed(miles, 1)} mi"
                else -> "${fixed(miles, 0)} mi"
            }
        }
    }

    /** Celsius in, the reader's scale out. */
    fun temperatureValue(celsius: Double, system: UnitSystem): Double =
        if (system == UnitSystem.Imperial) celsius * 9.0 / 5.0 + 32.0 else celsius

    /** Metres in, the reader's unit out. */
    fun altitudeValue(metres: Double, system: UnitSystem): Double =
        if (system == UnitSystem.Imperial) metres * FEET_PER_METRE else metres

    /**
     * Convert one telemetry reading, returning the value and the unit
     * to print beside it.
     *
     * Keyed on the canonical unit string the parser attached, so a
     * sensor type this app has never seen passes through untouched
     * rather than being converted on a guess. Anything not in the short
     * list above comes back exactly as it went in.
     */
    fun reading(value: Double, unit: String, system: UnitSystem): Pair<Double, String> =
        when (unit) {
            CELSIUS -> temperatureValue(value, system) to
                if (system == UnitSystem.Imperial) "°F" else CELSIUS
            METRES -> altitudeValue(value, system) to
                if (system == UnitSystem.Imperial) "ft" else METRES
            else -> value to unit
        }

    /** What the Settings row says the current choice is. */
    fun label(system: UnitSystem): String =
        if (system == UnitSystem.Imperial) "miles, feet, °F" else "kilometres, metres, °C"
}
