package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.TelemetryReading

/**
 * Asking a node for its telemetry, and what came of it.
 *
 * **Repeaters, room servers and sensors answer only signed-in clients.**
 * A request is decrypted with a secret looked up in the node's client
 * table, and a sender not in it is dropped without a word
 * (`simple_repeater/MyMesh.cpp:663-669`, `onPeerDataRecv` →
 * `matching_peer_indexes` from `searchPeersByHash`, which walks only the
 * ACL). Signing in is what puts us in that table. So for those three
 * the fetch signs in first, the same way the map's neighbour fetch does
 * — saved password, else blank, never saved by this — and a node that
 * would not have us says so rather than looking like one out of range.
 * Found on hardware 2026-10-08: SpartaMI returned nothing until signed in.
 *
 * A chat node (companion) is different: it grants telemetry per contact
 * from its own flags, and has no sign-in to attempt.
 */
sealed class TelemetryFetch(val message: String?) {
    data object NotConnected : TelemetryFetch("Not connected to a radio.")

    class SignInRefused(blank: Boolean, answered: Boolean) :
        TelemetryFetch(signInRefusedMessage(blank, answered))

    /** No reply. [signedIn] says whether access can be ruled out. */
    class NoAnswer(signedIn: Boolean) : TelemetryFetch(
        if (signedIn) {
            "No reply from the node. It may be out of reach."
        } else {
            "No reply. The node may publish no telemetry, or may not grant this device " +
                "permission to read it."
        },
    )

    class Readings(val readings: List<TelemetryReading>) : TelemetryFetch(null)

    companion object {
        /** True for node types whose requests are gated on their client table. */
        fun needsSignIn(contactType: Int): Boolean = when (contactType) {
            Codes.ADV_TYPE_REPEATER, Codes.ADV_TYPE_ROOM, Codes.ADV_TYPE_SENSOR -> true
            else -> false
        }
    }
}
