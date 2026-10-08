package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.TelemetryReading

/**
 * Asking a node for its telemetry, and what came of it.
 *
 * **Repeaters, room servers and sensors answer radios in their client
 * table.** A request is decrypted with a secret looked up there, and a
 * sender not in it is dropped without a word (`src/Mesh.cpp:147-152`,
 * `searchPeersByHash` → `simple_repeater/MyMesh.cpp:623`, which walks
 * only the ACL). Signing in is what puts a radio in that table — but the
 * entry is saved to flash and never expires, and the firmware has no
 * logout. So "signed in" in this app is not the gate: a node that has
 * ever had this radio answers it, whatever any app on it thinks.
 *
 * That was learned the hard way on 2026-10-08. SpartaMI returned nothing
 * until signed in, which read as "telemetry needs a login", and 0.10.14
 * signed in first — putting the saved password on the air before even
 * asking. The mainstream app gets the same node's telemetry with no
 * sign-in. What the sign-in had fixed was the ROUTE: a login floods, and
 * a flooded login is what makes a node drop a dead return path. So the
 * fetch asks first and repairs on silence ([usesClientTable]), the
 * repair order being [PathRecovery]'s: free probe before password.
 *
 * A chat node (companion) grants telemetry per contact from its own
 * flags, and has no client table or sign-in to repair with.
 */
sealed class TelemetryFetch(val message: String?) {
    data object NotConnected : TelemetryFetch("Not connected to a radio.")

    /**
     * No reply. [clientTable] true means route repair was tried too, so
     * what is left is reach, or a node that has never had this radio.
     */
    class NoAnswer(clientTable: Boolean) : TelemetryFetch(
        if (clientTable) {
            "No reply, even after repairing the route. The node may be out of reach, or may " +
                "not know this radio — sign in from its admin screen once and it will."
        } else {
            "No reply. The node may publish no telemetry, or may not grant this device " +
                "permission to read it."
        },
    )

    class Readings(val readings: List<TelemetryReading>) : TelemetryFetch(null)

    companion object {
        /**
         * True for node types that answer on their client table, and so
         * get route repair when they go quiet. False for a chat node,
         * which is asked once.
         */
        fun usesClientTable(contactType: Int): Boolean = when (contactType) {
            Codes.ADV_TYPE_REPEATER, Codes.ADV_TYPE_ROOM, Codes.ADV_TYPE_SENSOR -> true
            else -> false
        }
    }
}
