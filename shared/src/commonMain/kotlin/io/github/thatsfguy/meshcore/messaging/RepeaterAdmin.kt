package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.LoginOutcome
import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.presentation.floodRegionsRoute
import io.github.thatsfguy.meshcore.presentation.floodRegionsSummary
import io.github.thatsfguy.meshcore.presentation.BatteryLevel
import io.github.thatsfguy.meshcore.presentation.TelemetryFetch
import io.github.thatsfguy.meshcore.presentation.UnitSystem
import io.github.thatsfguy.meshcore.presentation.Units
import io.github.thatsfguy.meshcore.protocol.AccessList
import io.github.thatsfguy.meshcore.protocol.TelemetryReading
import io.github.thatsfguy.meshcore.util.fixed
import io.github.thatsfguy.meshcore.protocol.BinaryRequestBudget
import io.github.thatsfguy.meshcore.protocol.Neighbours
import io.github.thatsfguy.meshcore.protocol.PathRecovery
import io.github.thatsfguy.meshcore.protocol.CliExchange
import io.github.thatsfguy.meshcore.protocol.CliResend
import io.github.thatsfguy.meshcore.protocol.RepeaterStatus
import io.github.thatsfguy.meshcore.protocol.SendRetry
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * Repeater admin for a platform that cannot see Kotlin default
 * arguments. Swift would otherwise have to restate every default the
 * engine chose — retry counts, budgets, which CLI reads may be resent —
 * and a restated default is a default that drifts. This only forwards,
 * with the engine's own values.
 */
class RepeaterAdmin(private val engine: MeshCoreEngine) {

    /** Sign in with the login retry ladder (a refusal stops it at once). */
    suspend fun signIn(keyHex: String, password: String): LoginOutcome {
        val key = hexToBytesOrNull(keyHex) ?: return LoginOutcome.NoAnswer
        return engine.sendLoginWithRetry(key, password, SendRetry.DEFAULT_MAX_ATTEMPTS, true)
    }

    suspend fun status(keyHex: String): RepeaterStatus? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        return engine.repeaterStatus(key, BinaryRequestBudget.MAX_BUDGET_MS, null)
    }

    /** One CLI command; the reply text, or null on silence. Admin only — see CLAUDE.md. */
    suspend fun cli(keyHex: String, command: String): String? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        val sends = if (CliExchange.isRead(command)) CliResend.MAX_SENDS else 1
        return engine.sendCliAndAwaitReply(key, command, 15_000, sends, CliResend.RESEND_AFTER_MS)
    }

    // --- Phase 5 (2026-11-18): the rest of the admin surface, with the
    // engine's defaults restated so Swift never has to know them. ---

    /**
     * The last request's note when recovery ran out or a pinned route
     * stopped it (PathRecovery's sentences), else null. Read after a
     * call that returned null.
     */
    var lastNote: String? = null
        private set

    /**
     * Run [request] with Android's path-recovery gate: once if not signed
     * in or the route is pinned; otherwise reset the path and retry when
     * silent. iOS keeps no password, so re-authentication never runs
     * (PathRecovery.escalate skips it without one).
     */
    private suspend fun <T> recovered(
        key: ByteArray,
        signedIn: Boolean,
        pinned: Boolean,
        request: suspend () -> T?,
    ): T? {
        lastNote = null
        if (!signedIn) return request()
        if (!PathRecovery.shouldAttempt(pinned)) {
            return request().also { if (it == null) lastNote = PathRecovery.PINNED_MESSAGE }
        }
        var ran = false
        val out = engine.withPathRecovery(key, null, { ran = true }) { request() }
        if (out == null && ran) lastNote = PathRecovery.EXHAUSTED_MESSAGE
        return out
    }

    /** Status with path recovery. */
    suspend fun statusRecovered(keyHex: String, signedIn: Boolean, pinned: Boolean): RepeaterStatus? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        return recovered(key, signedIn, pinned) { engine.repeaterStatus(key, BinaryRequestBudget.MAX_BUDGET_MS, null) }
    }

    /** A CLI command sent without waiting for its reply (reboot, probe, clock). */
    suspend fun sendOnly(keyHex: String, command: String): Boolean {
        val key = hexToBytesOrNull(keyHex) ?: return false
        return runCatching { engine.sendCliCommand(key, command) != null }.getOrDefault(false)
    }

    /** The node's access list; the firmware answers only an admin. */
    suspend fun accessList(keyHex: String, signedIn: Boolean, pinned: Boolean): List<AccessList.BinEntry>? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        return recovered(key, signedIn, pinned) { engine.requestAccessList(key, BinaryRequestBudget.MAX_BUDGET_MS, null) }
    }

    /** "Owner" on the guest card, as the sentence to show. */
    suspend fun ownerLine(keyHex: String, signedIn: Boolean, pinned: Boolean): String {
        val key = hexToBytesOrNull(keyHex) ?: return OWNER_SILENT
        val reply = recovered(key, signedIn, pinned) { engine.requestOwnerInfo(key, BinaryRequestBudget.MAX_BUDGET_MS, null) }
            ?: return lastNote ?: OWNER_SILENT
        return reply.ownerInfo.ifEmpty { "No owner info set" } + "\nFirmware ${reply.firmwareVersion}"
    }

    /**
     * "Regions" on the guest card: the anonymous flood-regions request,
     * which a node answers only when sent DIRECT — so it is never sent
     * without a stored route — and only 4 times per 3 minutes.
     */
    suspend fun floodRegionsLine(keyHex: String): String {
        val c = engine.contacts.value[keyHex] ?: return REGIONS_NO_ROUTE
        val route = floodRegionsRoute(c.pathLen) ?: return REGIONS_NO_ROUTE
        val list = runCatching {
            engine.requestFloodRegions(c.publicKey, c.storedPath, route.hops, route.hashWidth, 30_000)
        }.getOrNull() ?: return REGIONS_SILENT
        return floodRegionsSummary(list) + "\nRegions it doesn't flood aren't shared. The full setup needs an admin."
    }

    /** One page of the node's neighbour table, newest first. */
    suspend fun neighbours(keyHex: String, offset: Int, signedIn: Boolean, pinned: Boolean): Neighbours.Table? {
        val key = hexToBytesOrNull(keyHex) ?: return null
        val request = Neighbours.Request(offset, Neighbours.Order.NEWEST_FIRST, Neighbours.DEFAULT_PREFIX_BYTES, null)
        return recovered(key, signedIn, pinned) {
            engine.requestNeighbours(key, request, BinaryRequestBudget.MAX_BUDGET_MS, null)
        }
    }

    /**
     * Fetch the node's whole neighbour table (up to the page limit) into
     * [book]; the sentence to show. Sign-in is the caller's: a blank
     * password works on a node that already knows this radio.
     */
    suspend fun collectNeighbours(keyHex: String, signedIn: Boolean, pinned: Boolean, book: NeighbourBook, nowMs: Long): String =
        book.collect(keyHex, nowMs) { offset -> neighbours(keyHex, offset, signedIn, pinned) }.message

    /** What a telemetry request produced: rows to show, or the sentence saying why there are none. */
    data class TelemetryView(val rows: List<io.github.thatsfguy.meshcore.presentation.RadioFacts.Fact>, val message: String?)

    /**
     * The node's telemetry. Room servers and sensors check a client
     * table rather than a sign-in, so they are asked with recovery even
     * when not signed in (Android's gate). An empty answer is no answer:
     * a node that replies always sends at least its own voltage.
     */
    suspend fun telemetry(keyHex: String, contactType: Int, signedIn: Boolean, pinned: Boolean, deviceCountry: String?,
                          /** The units setting ([UnitSystem.FOLLOW_SYSTEM] etc.); null follows the phone. */
                          unitsPreference: String? = null): TelemetryView {
        val key = hexToBytesOrNull(keyHex) ?: return TelemetryView(emptyList(), TelemetryFetch.NoAnswer(false).message)
        val gated = TelemetryFetch.usesClientTable(contactType)
        val readings = recovered(key, signedIn || gated, pinned) {
            engine.requestTelemetry(key, BinaryRequestBudget.MAX_BUDGET_MS, null).ifEmpty { null }
        } ?: return TelemetryView(emptyList(), lastNote ?: TelemetryFetch.NoAnswer(gated).message)
        return TelemetryView(telemetryRows(readings, UnitSystem.resolve(unitsPreference, deviceCountry)), null)
    }

    companion object {
        /**
         * A console line as it is SHOWN. The console is in memory only,
         * but a screen is screenshotted: a private key or password typed
         * into `set …` must not sit in the scrollback. The shared
         * LogRedaction rule (one copy, not two).
         */
        fun displayed(line: String): String = io.github.thatsfguy.meshcore.util.LogRedaction.redactSecrets(line)

        const val OWNER_SILENT = "No answer — out of range, or firmware too old to report it."
        const val REGIONS_NO_ROUTE =
            "Not asked — no direct route to this node is stored, and it only answers a direct request."
        const val REGIONS_SILENT =
            "No answer — out of range, or asked too often (it answers 4 anonymous requests per 3 minutes)."

        /** Telemetry as label/value rows — Android's StatusPanel formatting, shared. */
        fun telemetryRows(readings: List<TelemetryReading>, system: UnitSystem): List<io.github.thatsfguy.meshcore.presentation.RadioFacts.Fact> =
            readings.map { t ->
                val (value, unit) = Units.reading(t.value, t.unit, system)
                val shown = when {
                    BatteryLevel.isSelfBattery(t) -> BatteryLevel.label(BatteryLevel.millivolts(t))
                    unit.isEmpty() -> fixed(value, 2)
                    else -> "${fixed(value, 2)} $unit"
                }
                io.github.thatsfguy.meshcore.presentation.RadioFacts.Fact("${t.label} (ch ${t.channel})", shown)
            }

        /** Sets the node's clock to the phone's. */
        fun clockSyncCommand(nowSeconds: Long): String = "time $nowSeconds"
    }
}
