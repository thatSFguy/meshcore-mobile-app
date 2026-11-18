package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.engine.LoginOutcome
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.NodeRole
import io.github.thatsfguy.meshcore.protocol.RepeaterStatus
import io.github.thatsfguy.meshcore.protocol.StatusCodec
import io.github.thatsfguy.meshcore.util.fixed

/**
 * The pieces of repeater admin that are decisions rather than plumbing,
 * pure so iOS gets them tested: which role a contact type is, what a
 * login answer grants, how a sign-in failure reads, and the Status
 * screen as labelled rows (the same labels and units Android's
 * RepeaterStatusPanel shows).
 */
object RepeaterReadout {

    /**
     * [AdminSession.None] under a name Swift can reach unambiguously —
     * `.none` on a Kotlin enum collides with `Optional.none` there.
     */
    val signedOut: AdminSession get() = AdminSession.None

    data class Row(val label: String, val value: String)
    data class Group(val title: String, val rows: List<Row>)

    fun roleOf(advType: Int): NodeRole = when (advType) {
        Codes.ADV_TYPE_REPEATER -> NodeRole.Repeater
        Codes.ADV_TYPE_ROOM -> NodeRole.Room
        Codes.ADV_TYPE_SENSOR -> NodeRole.Sensor
        else -> NodeRole.Companion
    }

    /** What a login answer grants, or [AdminSession.None] when it granted nothing. */
    fun session(advType: Int, outcome: LoginOutcome): AdminSession =
        if (!outcome.accepted) {
            AdminSession.None
        } else {
            grantedSession(roleOf(advType), outcome.isAdmin, outcome.permissions, outcome.aclPermissions)
        }

    /**
     * Why sign-in did not work, in words. A refusal and silence look
     * alike on some firmware (CLAUDE.md, 2026-08-24), so "no answer"
     * must not claim the password was wrong.
     */
    fun failure(outcome: LoginOutcome): String? = when {
        outcome.accepted -> null
        outcome.answered -> "The node refused that password."
        else -> "No answer from the node. It may be out of range, or it may have refused — " +
            "some firmware says nothing to a wrong password."
    }

    fun status(s: RepeaterStatus): List<Group> = listOf(
        Group(
            "Status",
            listOf(
                Row("Battery", BatteryLevel.label(s.batteryMillivolts)),
                Row("Uptime", StatusCodec.formatUptime(s.uptimeSeconds)),
                Row("Queue length", s.queueLength.toString()),
                Row("Last RSSI / SNR", "${s.lastRssi} dBm / ${fixed(s.lastSnr, 1)} dB"),
                // dBm: an absolute power, like RSSI. SNR is the ratio.
                Row("Noise floor", "${s.noiseFloor} dBm"),
                Row("Channel utilisation", "${fixed(s.channelUtilizationPercent, 1)} %"),
            ),
        ),
        Group(
            "Packets",
            listOf(
                Row("Received / sent", "${s.packetsReceived} / ${s.packetsSent}"),
                Row("Flood tx / rx", "${s.floodTx} / ${s.floodRx}"),
                Row("Direct tx / rx", "${s.directTx} / ${s.directRx}"),
                Row("Duplicates (direct/flood)", "${s.directDuplicates} / ${s.floodDuplicates}"),
                Row("Airtime tx / rx", "${s.txAirSeconds}s / ${s.rxAirSeconds}s"),
                Row("Error events", s.errorEvents.toString()),
            ),
        ),
    )
}
