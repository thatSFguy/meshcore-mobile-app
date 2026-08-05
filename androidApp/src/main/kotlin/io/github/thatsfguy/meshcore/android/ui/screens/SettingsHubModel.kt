package io.github.thatsfguy.meshcore.android.ui.screens

import io.github.thatsfguy.meshcore.engine.EngineState

/**
 * One row on the Settings hub. [route] is the sub-route appended to
 * `settings/`.
 *
 * [needsRadio] rows are the ones that read or write the connected
 * node. They stay visible and tappable when nothing is connected —
 * their subtitle says so, and the screen behind says so — because
 * hiding them would make the app look like it has fewer settings than
 * it does, and a user who cannot find the radio page cannot tell
 * whether the app has one.
 */
data class SettingsTile(
    val route: String,
    val title: String,
    val subtitle: String,
    val needsRadio: Boolean = false,
)

/** A titled run of tiles on the hub. */
data class SettingsGroup(
    val title: String,
    val tiles: List<SettingsTile>,
)

/**
 * The Settings information architecture.
 *
 * This replaced a single screen of eleven expandable sections, several
 * of which (App in particular) were a second mega-screen inside the
 * first: theme, storage-encryption status, map tiles, notifications
 * and their two sub-switches, the diagnostics log AND its viewer,
 * backup, blocking and retention, all under one chevron.
 *
 * That is the accretion LESSONS §13 describes — adding to an existing
 * screen is free, creating one feels like work, and it is the other
 * way round. Every group below is one screen; "a new one" was the
 * right answer to "where does this live?" about fifteen times.
 *
 * Subtitles here are the STATIC fallback. The hub replaces them with
 * live values where there is one worth showing — see
 * [connectionSubtitle] and friends, which is why those are pure
 * functions rather than inline string building.
 */
fun settingsGroups(): List<SettingsGroup> = listOf(
    SettingsGroup(
        "Radio link",
        listOf(
            SettingsTile("connection", "Connection", "Connect, disconnect, saved radios"),
            SettingsTile("transports", "Transports", "Which link types may be used"),
        ),
    ),
    SettingsGroup(
        "This node",
        listOf(
            SettingsTile("identity", "Identity", "Name, position, advert, QR", needsRadio = true),
            SettingsTile("radio", "Radio", "Frequency, bandwidth, spreading, power", needsRadio = true),
            SettingsTile("clock", "Clock", "Read and correct the radio's clock", needsRadio = true),
            SettingsTile("policies", "Mesh policies", "Adverts, telemetry access, regions", needsRadio = true),
            SettingsTile("autoadd", "Auto-add contacts", "Which adverts become contacts", needsRadio = true),
            SettingsTile("customvars", "Custom variables", "GPS and other firmware variables", needsRadio = true),
        ),
    ),
    SettingsGroup(
        "Messaging",
        listOf(
            SettingsTile("channels", "Channels", "Shared-key groups"),
            SettingsTile("blocking", "Blocked senders", "Whose messages are dropped"),
        ),
    ),
    SettingsGroup(
        "App",
        listOf(
            // Appearance, notifications, privacy and the diagnostics
            // switch are one screen, not four.
            //
            // The first cut gave each its own. On a 384dp phone that
            // produced pages like "Privacy and network": one toggle, two
            // lines of text, and 70% empty screen — a spoke holding less
            // than the tile that led to it. Splitting a mega-screen is
            // right; splitting past the content is just a different way
            // to make someone tap more.
            SettingsTile("app", "Appearance and alerts", "Theme, notifications, privacy, logs"),
            SettingsTile("backup", "Backup", "Export or restore configuration"),
            SettingsTile("data", "Data and storage", "How long messages are kept"),
            SettingsTile("about", "About", "Version, licences, what this app is"),
        ),
    ),
)

/** Every route the hub can reach — used to keep the NavHost honest. */
fun settingsRoutes(): List<String> = settingsGroups().flatMap { it.tiles }.map { it.route }

// ----------------------------------------------------------------------
// Live subtitles
//
// A tile that reports the current value is worth several taps: it
// answers the question without opening anything. Pure functions so the
// wording is testable, and so a wrong unit shows up in a test rather
// than on a screen (LESSONS §5 — the frequency label was wrong for
// months).
// ----------------------------------------------------------------------

fun connectionSubtitle(state: EngineState, label: String?): String = when (state) {
    EngineState.Ready -> "Connected to ${label ?: "a radio"}"
    EngineState.Handshaking -> "Handshaking with ${label ?: "a radio"}…"
    EngineState.Connecting -> "Connecting…"
    EngineState.Detached -> "Not connected"
}

fun transportsSubtitle(ble: Boolean, usb: Boolean, tcp: Boolean): String {
    val on = buildList {
        if (ble) add("Bluetooth")
        if (usb) add("USB")
        if (tcp) add("TCP")
    }
    return when {
        on.isEmpty() -> "All transports disabled"
        // TCP is named last and named explicitly: it is the only one
        // that is plaintext, and the hub is the place a user scans for
        // "is anything unsafe switched on".
        tcp -> on.joinToString(", ") + " — TCP is unencrypted"
        else -> on.joinToString(", ")
    }
}

fun identitySubtitle(name: String?): String =
    if (name.isNullOrBlank()) "No advertised name set" else name

/**
 * Frequency in **kHz**, rendered as MHz.
 *
 * `freqKhz` is kHz because the reference client computes it
 * `freqMHz * 1000` — the field is named `freqHz` across the ecosystem
 * and that name cost this project every regional preset for months
 * (LESSONS §5). Dividing by 1000 here is the only correct reading.
 */
fun radioSubtitle(freqKhz: Long?, sf: Int?, txPowerDbm: Int?): String {
    if (freqKhz == null || freqKhz <= 0) return "Connect to read radio parameters"
    val mhz = freqKhz / 1000.0
    return buildString {
        append("%.3f MHz".format(mhz))
        if (sf != null && sf > 0) append(" · SF$sf")
        if (txPowerDbm != null && txPowerDbm > 0) append(" · ${txPowerDbm}dBm")
    }
}

fun channelsSubtitle(count: Int): String = when (count) {
    0 -> "None configured"
    1 -> "1 channel"
    else -> "$count channels"
}

fun blockingSubtitle(count: Int): String = when (count) {
    0 -> "Nobody blocked"
    1 -> "1 sender blocked"
    else -> "$count senders blocked"
}

fun appearanceSubtitle(theme: String): String = when (theme) {
    "light" -> "Light"
    "dark" -> "Dark"
    else -> "Follow the system"
}

fun notificationsSubtitle(enabled: Boolean, direct: Boolean, channels: Boolean): String {
    if (!enabled) return "Off"
    val kinds = buildList {
        if (direct) add("direct messages")
        if (channels) add("channels")
    }
    return if (kinds.isEmpty()) "On, but no message type selected" else "On — " + kinds.joinToString(" and ")
}

fun privacySubtitle(mapTilesEnabled: Boolean, storageEncrypted: Boolean): String = when {
    // The storage warning outranks the tile's normal subtitle: an
    // unencrypted database is the one thing on this screen a user must
    // not have to open a page to discover.
    !storageEncrypted -> "⚠ Message storage is NOT encrypted"
    mapTilesEnabled -> "Map tiles on — the app's only outbound HTTP"
    else -> "Map tiles off — no outbound HTTP"
}

fun diagnosticsSubtitle(enabled: Boolean): String =
    if (enabled) "On — secrets redacted before logging" else "Off"

/**
 * The one App row, summarising four sections.
 *
 * An unencrypted database still wins outright: it is the single fact
 * on this screen nobody should have to open a page to discover, and
 * folding privacy into a shared row must not bury it. Otherwise the
 * row reports the two things that change most — theme and whether
 * anything will interrupt you.
 */
fun appSubtitle(
    theme: String,
    notificationsEnabled: Boolean,
    storageEncrypted: Boolean,
): String {
    if (!storageEncrypted) return "⚠ Message storage is NOT encrypted"
    val alerts = if (notificationsEnabled) "notifications on" else "notifications off"
    return "${appearanceSubtitle(theme)} · $alerts"
}
