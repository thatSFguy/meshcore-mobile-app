package io.github.thatsfguy.meshcore.protocol

/**
 * Regional LoRa presets (PARITY.md §1,
 * `SuggestedRadioSettingsSelectorBottomSheet`).
 *
 * Radio parameters are how a mesh agrees to exist: a node on the wrong
 * frequency, bandwidth, spreading factor or coding rate is not on a
 * worse mesh, it is on no mesh. Typing four numbers correctly is a poor
 * way to join one, hence presets.
 *
 * ## Where these came from, and what they are not
 *
 * Mostly transcribed from the MeshCore Open reference client's own table
 * (`lib/models/radio_settings.dart`), which is what the local community
 * in each area is actually using. Entries marked LOCAL below are this
 * project's own additions — meshes the author runs or has been told
 * about, which no published table carries. Either way they are a good
 * default and NOT a statement of law:
 *
 *  - **Frequency allocations and duty-cycle rules are jurisdictional.**
 *    A preset named for a country is what people there run, not
 *    permission to transmit there.
 *  - **[txPowerDbm] carries the same caveat.** The 14 dBm entries
 *    reflect the EU ERP limit; the 20 dBm ones do not apply everywhere.
 *
 * The UI says this out loud before applying one. Getting it wrong is
 * not a bug report, it is an offence in most places.
 */
object RadioPresets {

    data class Preset(
        val name: String,
        val frequencyMhz: Double,
        val bandwidthKhz: Double,
        val spreadingFactor: Int,
        val codingRate: Int,
        val txPowerDbm: Int,
    ) {
        /**
         * **kHz** — the unit CMD_SET_RADIO_PARAMS actually wants for
         * frequency, despite every name in the ecosystem saying Hz.
         *
         * The reference client calls its variable `freqHz` and then
         * computes `(freqMHz * 1000)`; it reads the value back with
         * `currentFreqHz / 1000.0` to get MHz. Both are kHz. The name is
         * simply wrong, and copying the name is how this app came to
         * send 910525000 to a radio expecting 910525 — which it
         * rejected, which is how it was found.
         *
         * The giveaway sits three lines below the reference's own send:
         * `validRepeatFreqsKHz = {433000, 869000, 918000}`, compared
         * against that same "Hz" variable.
         */
        val frequencyKhz: Long get() = (frequencyMhz * 1_000).toLong()

        /**
         * **Hz** — and this one really is Hz. The wire format is
         * asymmetric: frequency in kHz, bandwidth in Hz. The reference
         * client's bandwidth enum is explicit about it (62.5 kHz →
         * 62500), so do not "fix" this to match the line above.
         */
        val bandwidthHz: Long get() = (bandwidthKhz * 1_000).toLong()

        /** The `set radio` CSV form: "freq,bw,sf,cr". */
        fun toRadioCsv(): String {
            val f = if (frequencyMhz == frequencyMhz.toLong().toDouble()) {
                frequencyMhz.toLong().toString()
            } else {
                frequencyMhz.toString()
            }
            val bw = if (bandwidthKhz == bandwidthKhz.toLong().toDouble()) {
                bandwidthKhz.toLong().toString()
            } else {
                bandwidthKhz.toString()
            }
            return "$f,$bw,$spreadingFactor,$codingRate"
        }

        /** One-line summary for a list row. */
        fun summary(): String =
            "$frequencyMhz MHz · ${bandwidthKhz}kHz · SF$spreadingFactor · CR4/$codingRate · ${txPowerDbm}dBm"
    }

    private fun P(
        name: String,
        freq: Double,
        bw: Double,
        sf: Int,
        cr: Int,
        tx: Int,
    ) = Preset(name, freq, bw, sf, cr, tx)

    /** Every preset, in the reference client's order. */
    val ALL: List<Preset> = listOf(
        P("Australia", 915.8, 250.0, 10, 5, 20),
        P("Australia (Narrow)", 916.575, 62.5, 7, 5, 20),
        P("Australia (Mid)", 915.075, 125.0, 9, 5, 20),
        P("Australia SA, WA, QLD", 923.125, 62.5, 8, 5, 20),
        P("Czech Republic", 869.432, 62.5, 7, 5, 14),
        P("EU 433MHz", 433.65, 250.0, 11, 5, 20),
        P("EU/UK (Long Range)", 869.525, 250.0, 11, 5, 14),
        P("EU/UK (Medium Range)", 869.525, 250.0, 10, 5, 14),
        P("EU/UK (Narrow)", 869.618, 62.5, 8, 5, 14),
        P("New Zealand", 917.375, 250.0, 11, 5, 20),
        P("New Zealand (Narrow)", 917.375, 62.5, 7, 5, 20),
        P("Portugal 433", 433.375, 62.5, 9, 5, 20),
        P("Portugal 869", 869.618, 62.5, 7, 5, 14),
        P("Russia Artyom (VVO)", 864.281, 62.5, 8, 6, 20),
        P("Russia Biysk (BSK)", 869.0, 62.5, 8, 5, 20),
        P("Russia Chelyabinsk (CEK)", 868.731, 62.5, 8, 6, 20),
        P("Russia Cherepovets (CEE)", 868.57, 62.5, 7, 8, 20),
        P("Russia Irkutsk (IKT)", 868.731, 62.5, 7, 7, 20),
        P("Russia Ivanovo (IWA)", 868.731, 62.5, 8, 8, 20),
        P("Russia Izhevsk (IJK)", 868.732, 62.5, 8, 8, 20),
        P("Russia Kaluga (KLF)", 868.731, 62.5, 7, 7, 20),
        P("Russia Kazan (KZN)", 868.731, 62.5, 8, 6, 20),
        P("Russia Khabarovsk (KHV)", 864.281, 62.5, 8, 6, 20),
        P("Russia Kirov (KVX)", 868.731, 62.5, 8, 8, 20),
        P("Russia Lipetsk (LPK)", 868.95, 62.5, 9, 7, 20),
        P("Russia Moscow (MOW)", 868.731, 62.5, 7, 7, 20),
        P("Russia Nizhny Novgorod (GOJ)", 868.731, 62.5, 8, 6, 20),
        P("Russia Novosibirsk (OVB)", 869.0, 62.5, 9, 8, 20),
        P("Russia Rostov-on-Don (ROV)", 868.731, 62.5, 9, 7, 20),
        P("Russia Ryazan (RZN)", 868.88, 62.5, 9, 5, 20),
        P("Russia Samara (KUF)", 864.281, 62.5, 8, 7, 20),
        P("Russia Saratov (GSV)", 864.281, 62.5, 8, 7, 20),
        P("Russia St. Petersburg (LED)", 868.856, 62.5, 7, 7, 20),
        P("Russia Tambov (TBW)", 868.95, 62.5, 10, 5, 20),
        P("Russia Tula (TYA)", 868.731, 62.5, 8, 7, 20),
        P("Russia Tver (KLD)", 869.169, 62.5, 8, 8, 20),
        P("Russia Ufa (UFA)", 868.732, 62.5, 8, 8, 20),
        P("Russia Volgograd (VOG)", 869.525, 62.5, 7, 7, 20),
        P("Russia Voronezh (VOZ)", 868.731, 62.5, 8, 6, 20),
        P("Russia Yekaterinburg (SVX)", 869.046, 62.5, 7, 7, 20),
        P("Switzerland", 869.618, 62.5, 8, 5, 14),
        P("USA Arizona", 908.205, 62.5, 10, 5, 20),
        // LOCAL — not in the reference table. A wide-bandwidth, higher-SF
        // profile for sparse rural coverage, where hops are long and few:
        // 250kHz keeps airtime down at SF9, which buys range over the
        // narrow 62.5kHz USA/Canada profile without the airtime cost of
        // going slower still.
        P("USA Rural", 906.375, 250.0, 9, 5, 22),
        P("USA/Canada", 910.525, 62.5, 7, 5, 20),
        P("Vietnam", 920.25, 250.0, 11, 5, 20),
        P("Off-Grid 433", 433.0, 250.0, 11, 8, 20),
        P("Off-Grid 869", 869.0, 250.0, 11, 8, 14),
        P("Off-Grid 918", 918.0, 250.0, 11, 8, 20),
    )

    /** Case-insensitive lookup by exact name; null when unknown. */
    fun byName(name: String?): Preset? =
        ALL.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

    /**
     * Presets matching the given live radio parameters, so the settings
     * screen can show "you are on USA/Canada" rather than four numbers.
     * More than one can match — the Russian city presets overlap
     * heavily — so this returns all of them and never picks.
     */
    fun matching(
        /** kHz, exactly as SELF_INFO reports it — see [Preset.frequencyKhz]. */
        frequencyKhz: Long,
        /** Hz, exactly as SELF_INFO reports it. */
        bandwidthHz: Long,
        spreadingFactor: Int,
        codingRate: Int,
    ): List<Preset> = ALL.filter {
        it.frequencyKhz == frequencyKhz && it.bandwidthHz == bandwidthHz &&
            it.spreadingFactor == spreadingFactor && it.codingRate == codingRate
    }

    /** Coarse grouping for a picker, derived from the name. */
    fun region(preset: Preset): String = when {
        preset.name.startsWith("Russia") -> "Russia"
        preset.name.startsWith("Australia") -> "Australia"
        preset.name.startsWith("USA") -> "North America"
        preset.name.startsWith("New Zealand") -> "New Zealand"
        preset.name.startsWith("Off-Grid") -> "Off-grid (no repeaters)"
        preset.name.startsWith("EU") || preset.name in EUROPE_NAMES -> "Europe"
        else -> "Other"
    }

    private val EUROPE_NAMES = setOf(
        "Czech Republic", "Portugal 433", "Portugal 869", "Switzerland",
    )

    /** The warning the UI must show before applying any of these. */
    const val REGULATORY_CAVEAT =
        "These are what local communities run, not legal advice. Frequency, duty cycle " +
            "and transmit power are regulated where you are, and getting them wrong is " +
            "an offence in most places — check before you transmit."
}
