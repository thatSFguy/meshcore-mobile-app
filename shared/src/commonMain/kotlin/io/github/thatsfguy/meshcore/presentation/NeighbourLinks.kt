package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Neighbours
import io.github.thatsfguy.meshcore.util.RelativeTime
import io.github.thatsfguy.meshcore.util.isPlausiblePosition

/**
 * A repeater's neighbour reading, kept — and the map lines drawn from
 * it (PARITY §6, the `RepeaterNeighboursMapScreen` row).
 *
 * ## Why a collected-at stamp is not optional
 *
 * The wire carries `heard_seconds_ago`: an ELAPSED time, measured on
 * the repeater's own clock at the moment it answered
 * (MESHCORE_PROTOCOL §11). It is not a timestamp, and it starts going
 * stale the instant it is parsed. Stored on its own it would say "heard
 * 4 minutes ago" tomorrow morning, about a reading taken last night —
 * confidently, and wrong by a day.
 *
 * So a stored row is the pair: what the node said, and when we asked.
 * [secondsAgoAt] adds the elapsed local time back on, which is the only
 * arithmetic that survives being saved. Crossing clocks that way is
 * safe because both halves are *intervals* — the node's clock is only
 * ever asked how long, never when, which matters because a repeater
 * commonly has no RTC worth trusting.
 *
 * ## What a line on the map means
 *
 * It means: this repeater heard that node's advert directly, once, at
 * the SNR shown. It does NOT mean the two can talk now, that the link
 * works the other way, or that anything was routed over it. The table
 * is also hearsay — what the repeater says it hears, relayed by that
 * repeater — and every entry is a key PREFIX, so a line is drawn only
 * where exactly one known node matches and has a position. Everything
 * else is reported as undrawable with the reason, because a line to the
 * wrong node is worse than no line.
 */
data class NeighbourRecord(
    /** The repeater whose table this row came from. */
    val repeaterKeyHex: String,
    /** Key prefix, hex — NOT an identity. See [Neighbours]. */
    val keyPrefixHex: String,
    /** SNR in dB, as the repeater heard it. */
    val snr: Double,
    /** `heard_seconds_ago` exactly as reported, valid at [collectedAt]. */
    val heardSecondsAgo: Long,
    /** Local epoch millis when the reading was taken. */
    val collectedAt: Long,
) {
    /**
     * How long ago the repeater heard this node, as of [nowMillis].
     *
     * The node's elapsed count plus ours since. A clock that has stepped
     * backwards clamps to zero rather than making a reading younger.
     */
    fun secondsAgoAt(nowMillis: Long): Long =
        heardSecondsAgo + (nowMillis - collectedAt).coerceAtLeast(0) / 1000
}

/**
 * SNR bands for the line drawn on the map.
 *
 * LoRa demodulates well below the noise floor, so the useful range runs
 * from about +10 dB down to -20 dB and the interesting part is all
 * negative — a scale that treats "below zero" as bad would paint a
 * healthy mesh red. Bands are set against the spreading-factor floors:
 * a link at -7 dB still decodes at SF7, one at -15 dB needs SF11+, and
 * past -20 dB nothing decodes at all.
 *
 * [argb] travels with the band because the same colours are wanted on
 * both platforms; nothing here touches a UI framework.
 */
enum class LinkQuality(val label: String, val argb: Int) {
    /** ≥ 5 dB — above the noise, decodes at any spreading factor. */
    Strong("Strong", 0xFF2E7D32.toInt()),

    /** 0 … 5 dB — a solid link with headroom. */
    Good("Good", 0xFF7CB342.toInt()),

    /** -7 … 0 dB — below the noise and still fine for LoRa. */
    Fair("Fair", 0xFFF9A825.toInt()),

    /** -15 … -7 dB — decoding on the slower spreading factors only. */
    Weak("Weak", 0xFFEF6C00.toInt()),

    /** < -15 dB — at the edge of what any spreading factor recovers. */
    Marginal("Marginal", 0xFFC62828.toInt()),
    ;

    companion object {
        fun of(snr: Double): LinkQuality = when {
            snr >= 5.0 -> Strong
            snr >= 0.0 -> Good
            snr >= -7.0 -> Fair
            snr >= -15.0 -> Weak
            else -> Marginal
        }
    }
}

/** A node the map can put a pin on — the end of a neighbour line. */
data class NeighbourEndpoint(
    val keyHex: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
)

/** Why a neighbour the repeater reported cannot be drawn. */
enum class UndrawableLink(val reason: String) {
    /** No contact starts with that prefix — a node we don't know. */
    Unknown("not a known node"),

    /** More than one contact matches the prefix; picking one would lie. */
    Ambiguous("prefix matches more than one node"),

    /** Known, but it has never advertised a position. */
    NoPosition("no position"),
}

/**
 * One neighbour, resolved as far as it can be, ready to draw or to
 * explain why not.
 */
data class NeighbourLink(
    val keyPrefixHex: String,
    /** Name when a single node matched, otherwise the bare prefix. */
    val label: String,
    val snr: Double,
    val quality: LinkQuality,
    /** Aged to "now" — see [NeighbourRecord.secondsAgoAt]. */
    val secondsAgo: Long,
    /** Set only when exactly one known node matches AND it has a fix. */
    val endpoint: NeighbourEndpoint?,
    val undrawable: UndrawableLink?,
) {
    val isDrawable: Boolean get() = endpoint != null

    /** "-4.2 dB" — the reading, on its own. */
    val snrLabel: String get() = formatSnr(snr)

    /**
     * "Fair · -4.2 dB" — what the chip on the line says.
     *
     * The band is spelled out ON the line rather than mapped to it by a
     * legend somewhere else. A key at the edge of the screen makes the
     * reader hold five colours in their head and look away from the
     * thing they are reading; saying "Fair" where the line is costs a
     * word and needs nothing remembered. Colour still carries it too,
     * for the glance that doesn't stop to read.
     */
    val mapLabel: String get() = "${quality.label} · $snrLabel"

    /** "Fair · -4.2 dB · 12m ago" for the list under the map. */
    val summary: String
        get() = "${quality.label} · $snrLabel · ${Neighbours.heardAgoLabel(secondsAgo)}"
}

/**
 * The neighbour rows of one repeater, resolved against the nodes we
 * know and aged to [nowMillis].
 *
 * Strongest first: the question a coverage map answers is which links
 * carry, so the best ones are read first and the drawing order puts
 * them on top. Ties keep the fresher reading ahead.
 */
fun neighbourLinks(
    records: List<NeighbourRecord>,
    nodes: List<NeighbourEndpoint>,
    nowMillis: Long,
): List<NeighbourLink> = records
    .map { record ->
        val matches = nodes.filter {
            it.keyHex.startsWith(record.keyPrefixHex, ignoreCase = true)
        }
        val single = matches.singleOrNull()
        val positioned = single?.takeIf { isPlausiblePosition(it.latitude, it.longitude) }
        NeighbourLink(
            keyPrefixHex = record.keyPrefixHex,
            label = Neighbours.label(
                Neighbours.Neighbour(record.keyPrefixHex, record.heardSecondsAgo, record.snr),
                matches.map { it.name.ifBlank { it.keyHex.take(8) } },
            ),
            snr = record.snr,
            quality = LinkQuality.of(record.snr),
            secondsAgo = record.secondsAgoAt(nowMillis),
            endpoint = positioned,
            undrawable = when {
                positioned != null -> null
                matches.isEmpty() -> UndrawableLink.Unknown
                matches.size > 1 -> UndrawableLink.Ambiguous
                else -> UndrawableLink.NoPosition
            },
        )
    }
    .sortedWith(compareByDescending<NeighbourLink> { it.snr }.thenBy { it.secondsAgo })

/**
 * "Collected 12 min ago" for a set of rows, or null when there are
 * none.
 *
 * Pages fetched minutes apart are one table on screen but not one
 * reading, so a span is reported as a span rather than being flattened
 * to its newest edge — which is the direction that overstates.
 */
fun collectedLabel(records: List<NeighbourRecord>, nowMillis: Long): String? {
    if (records.isEmpty()) return null
    val newest = records.maxOf { it.collectedAt }
    val oldest = records.minOf { it.collectedAt }
    val newestAgo = RelativeTime.agoMillis(nowMillis - newest)
    if (newest - oldest < SPAN_TOLERANCE_MS) return "Collected $newestAgo"
    return "Collected ${RelativeTime.agoMillis(nowMillis - oldest)} to $newestAgo"
}

/** Two pages of one sweep land within a few seconds of each other. */
private const val SPAN_TOLERANCE_MS = 60_000L

/** "-4.2 dB", one decimal, sign kept. */
internal fun formatSnr(snr: Double): String {
    val tenths = (snr * 10).let { if (it < 0) -((-it) + 0.5).toLong() else (it + 0.5).toLong() }
    val whole = tenths / 10
    val frac = if (tenths < 0) -(tenths % 10) else tenths % 10
    val sign = if (tenths < 0 && whole == 0L) "-" else ""
    return "$sign$whole.$frac dB"
}

/**
 * What the map's node popup can offer for neighbours.
 *
 * Null for anything that is not a repeater: only repeater firmware
 * keeps a neighbour table — there is no `0x06` handler on a room
 * server or a sensor at all (MESHCORE_PROTOCOL §11) — so the control
 * could only ever time out into "no reply, are you in range?", which
 * blames the link for a question the node cannot be asked.
 *
 * Stored rows are offered whether or not a radio is attached. That is
 * the point of keeping them: a coverage picture is worth looking at on
 * the kitchen table, and the reading is stamped so it can say how old
 * it is rather than pretending to be live.
 */
data class NeighbourOffer(
    val storedCount: Int,
    /** [collectedLabel] for the stored rows, null when there are none. */
    val collected: String?,
    val canFetch: Boolean,
    val fetchLabel: String,
    /** One line: how the fetch will sign in, or why it cannot. */
    val fetchHint: String,
) {
    val hasStored: Boolean get() = storedCount > 0
}

fun neighbourOffer(
    isRepeater: Boolean,
    connected: Boolean,
    session: AdminSession,
    hasSavedPassword: Boolean,
    storedCount: Int,
    collected: String?,
): NeighbourOffer? {
    if (!isRepeater) return null
    return NeighbourOffer(
        storedCount = storedCount,
        collected = collected,
        canFetch = connected,
        fetchLabel = if (storedCount > 0) "Fetch again" else "Fetch neighbours",
        // Say which credential is about to be spent, before it is spent.
        // A blank password is the ordinary way in — the firmware falls
        // through to the guest slot, which ships empty — but it is still
        // a login attempt against someone's repeater, and the reading is
        // guest-readable either way (no `isAdmin()` gate on `0x06`).
        fetchHint = when {
            !connected -> "Not connected to a radio."
            session.signedIn -> "Uses the session you are already signed in with."
            hasSavedPassword -> "Signs in with the password saved for this node."
            else -> "Signs in with a blank password, for read-only access."
        },
    )
}

/** The outcome of one neighbour collection, as the user should hear it. */
sealed class NeighbourFetch(val message: String) {
    data object NotConnected : NeighbourFetch("Not connected to a radio.")

    /**
     * The login did not produce a session.
     *
     * ⚠ A refusal and a silence CANNOT be told apart here, and the
     * message must not pretend otherwise. `handleLoginReq` returns 0 for
     * a password it does not accept, and the caller's next line is
     * `if (reply_len == 0) return;` — so a MeshCore repeater sends
     * NOTHING when it turns a password down
     * (`examples/simple_repeater/MyMesh.cpp:90-107`, `:594`). A wrong
     * password and a node behind a hill look identical on the wire.
     *
     * Which also answers the common case: a blank password gets in only
     * where the operator left the guest slot empty, or where this node
     * is already in that repeater's ACL from an earlier sign-in. A
     * repeater with a guest password set simply goes quiet.
     */
    class SignInRefused(blank: Boolean, answered: Boolean) : NeighbourFetch(
        when {
            answered && blank -> "The node answered but would not take a blank password."
            answered -> "The node answered but refused the saved password."
            blank ->
                "No answer to a blank password. A repeater stays silent when it refuses one, " +
                    "so it may want a guest password — or be out of reach."
            else ->
                "No answer to the saved password. A repeater stays silent when it refuses one, " +
                    "so it may be the wrong password — or out of reach."
        },
    )

    data object NoAnswer : NeighbourFetch("No reply from the node — is it in range?")

    /**
     * The node claims rows and returned an empty first page. A correct
     * request cannot produce this, so say it is wrong rather than
     * inviting a retry that cannot help.
     */
    class Rejected(total: Int) : NeighbourFetch(
        "The node says it knows $total neighbour(s) but returned none. " +
            "That is a rejected request, not a paged table.",
    )

    /** Answered. [count] rows recorded; [total] is what it claims to know. */
    class Collected(val count: Int, val total: Int) : NeighbourFetch(
        when {
            count == 0 -> "The node reported no neighbours."
            count < total -> "Recorded $count of the $total neighbours it knows."
            count == 1 -> "Recorded 1 neighbour."
            else -> "Recorded $count neighbours."
        },
    )
}

/** What to do with a page of neighbours before it is stored. */
data class NeighbourWrite(val clearFirst: Boolean, val store: Boolean)

/**
 * How one reply page joins what is already stored.
 *
 * Three cases, and the middle one is the reason this is a function
 * rather than an `if`:
 *
 *  - **A first page replaces the table.** The firmware never expires an
 *    entry, so merging a new read into an old one would accumulate
 *    nodes the repeater has stopped reporting and draw them as current
 *    links. That includes an honest empty answer: a node that now
 *    reports nobody must not keep yesterday's lines on the map.
 *  - **A REJECTED page changes nothing.** `isEmptyButNotEmpty` means
 *    the node claimed rows and returned none — a malformed request, not
 *    a finding. Clearing on it would spend a real reading to record the
 *    fact that we asked wrong.
 *  - **A later page adds to the sweep it belongs to**, never clearing.
 */
fun neighbourWrite(offset: Int, entryCount: Int, rejected: Boolean): NeighbourWrite = when {
    rejected -> NeighbourWrite(clearFirst = false, store = false)
    offset == 0 -> NeighbourWrite(clearFirst = true, store = entryCount > 0)
    else -> NeighbourWrite(clearFirst = false, store = entryCount > 0)
}
