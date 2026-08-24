package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.util.fixed
import io.github.thatsfguy.meshcore.protocol.HeardRepeats
import io.github.thatsfguy.meshcore.protocol.PathCodec
import io.github.thatsfguy.meshcore.util.RelativeTime
import io.github.thatsfguy.meshcore.util.hexToBytes

/**
 * Rows for the "who repeats me" list.
 *
 * Pure so the wording is tested rather than eyeballed on a phone, and
 * so the one judgement call in here — never naming an ambiguous hop —
 * is asserted rather than assumed.
 */
data class RepeatRow(
    val hashHex: String,
    /** "b389 Blue Ridge", "b389 Blue Ridge or Other", or the bare hash. */
    val label: String,
    val direction: String,
    val relayed: Int,
    /** "SNR 10.0 dB", or null when this repeater's link was never measured. */
    val snrText: String?,
    val ageText: String,
    val isTwoWay: Boolean,
    val isAmbiguous: Boolean,
)

/**
 * Build the list from [echoes], naming hops out of [contactsByKeyHex]
 * (full public-key hex → display name).
 *
 * [nowMillis] is the engine's MONOTONIC clock, the same one the echoes
 * were stamped with — mixing in a wall clock here would age a
 * just-heard repeat by however far the two have drifted.
 *
 * Hop naming goes through [PathCodec.resolveHops] rather than matching
 * keys here: a hop is a truncated hash, two bytes is 16 bits, and
 * collisions are ordinary. Exactly one match gets a name; more than one
 * names every candidate and resolves to none. Calling the shared
 * resolver instead of repeating
 * its four-line filter is deliberate — that filter is a rule, and a rule
 * copied is a rule that drifts.
 */
fun repeatRows(
    echoes: List<HeardRepeats.Echo>,
    contactsByKeyHex: Map<String, String>,
    nowMillis: Long,
): List<RepeatRow> = HeardRepeats.tally(echoes).map { relay ->
    // One hop's worth of "path", resolved by the shared rule.
    val hop = PathCodec.resolveHops(
        hexToBytes(relay.hashHex),
        hashWidth = relay.hashHex.length / 2,
        contactsByKeyHex = contactsByKeyHex,
    ).singleOrNull()
    val label = hop?.label ?: relay.hashHex
    RepeatRow(
        hashHex = relay.hashHex,
        label = label,
        direction = HeardRepeats.direction(relay),
        relayed = relay.relayed,
        // Stated only for the repeater whose transmission this radio
        // actually demodulated. Showing a number against the far end of
        // a path would be reporting a link nobody measured.
        snrText = relay.bestSnr?.let { "SNR " + fixed(it, 1) + " dB" },
        ageText = RelativeTime.agoMillis(nowMillis - relay.lastAtMillis),
        isTwoWay = relay.isTwoWay,
        isAmbiguous = hop?.isAmbiguous == true,
    )
}
