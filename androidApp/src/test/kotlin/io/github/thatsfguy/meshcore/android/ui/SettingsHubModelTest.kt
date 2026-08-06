package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.android.ui.screens.CONVERSATION_ROUTE
import io.github.thatsfguy.meshcore.android.ui.screens.appSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.conversationRoute
import io.github.thatsfguy.meshcore.android.ui.screens.appearanceSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.blockingSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.channelsSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.connectionSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.diagnosticsSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.identitySubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.notificationsSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.privacySubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.radioSubtitle
import io.github.thatsfguy.meshcore.android.ui.screens.settingsGroups
import io.github.thatsfguy.meshcore.android.ui.screens.settingsRoutes
import io.github.thatsfguy.meshcore.android.ui.screens.transportsSubtitle
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.protocol.RadioPresets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings information architecture and the live subtitles on its
 * tiles.
 */
class SettingsHubModelTest {

    // --- the navigation graph itself -------------------------------------

    @Test
    fun `every tile route is unique`() {
        val routes = settingsRoutes()
        assertEquals(routes.size, routes.distinct().size)
        assertTrue("no tiles at all", routes.size > 10)
    }

    /**
     * A tile pointing at a route with no destination is a tap that does
     * nothing, and neither the compiler nor any other test would catch
     * it. This reads the real NavHost rather than a copy of the route
     * list, which is the point — a value from outside the file under
     * test (REBUILD-PLAYBOOK §7.1).
     */
    @Test
    fun `every settings tile has a destination in the NavHost`() {
        val nav = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/MainActivity.kt")
        assertTrue("MainActivity.kt not found at ${nav.absolutePath}", nav.exists())
        val source = nav.readText()
        for (route in settingsRoutes()) {
            assertTrue(
                "settings/$route has a tile but no composable() in the NavHost",
                source.contains("composable(\"settings/$route\")"),
            )
        }
    }

    @Test
    fun `every repeater hub tile has a destination in the NavHost`() {
        val nav = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/MainActivity.kt")
        val source = nav.readText()
        // The console takes a query argument, so it is matched loosely.
        for (route in listOf("status", "settings", "regions", "identity", "help")) {
            assertTrue(
                "repeater/{key}/$route has no composable() in the NavHost",
                source.contains("composable(\"repeater/{key}/$route\")"),
            )
        }
        assertTrue(source.contains("\"repeater/{key}/console?prefill={prefill}\""))
    }

    /**
     * A notification tap deep-links to a conversation, so the route it
     * builds and the route the NavHost declares have to agree. They are
     * spelled in four places; this checks the generated one actually
     * fits the declared pattern, and that the NavHost still declares it.
     */
    @Test
    fun `the conversation route matches the pattern the NavHost declares`() {
        val nav = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/MainActivity.kt")
        val source = nav.readText()
        assertTrue(
            "NavHost no longer declares CONVERSATION_ROUTE",
            source.contains("composable(CONVERSATION_ROUTE)"),
        )
        assertEquals("conversation/{kind}/{peer}", CONVERSATION_ROUTE)

        // A built route must fill exactly the declared placeholders.
        val built = conversationRoute("dm", "b389548d314a")
        assertEquals("conversation/dm/b389548d314a", built)
        val pattern = Regex(
            "^" + CONVERSATION_ROUTE.replace("{kind}", "[^/]+").replace("{peer}", "[^/]+") + "$",
        )
        assertTrue("built route does not match the pattern: $built", pattern.matches(built))
        assertTrue(pattern.matches(conversationRoute("ch", "0")))
    }

    @Test
    fun `a notification tap carries both halves of the thread key`() {
        // kind and peerKey come straight from MessageRepository's
        // constants; a channel's peer is its index, not a key.
        assertEquals("conversation/ch/3", conversationRoute("ch", "3"))
        assertEquals("conversation/dm/abc", conversationRoute("dm", "abc"))
    }

    /**
     * Every QR scanner in the app dispatches through the one entry
     * point that classifies the code first.
     *
     * A scanner wired straight to a single decoder is what made a
     * repeater's contact card answer "Invalid community code" from the
     * Chats button — and nothing about that is visible in a unit test
     * of the classifier itself, because the classifier was never
     * reached.
     */
    @Test
    fun `both QR scanners route through the shared entry point`() {
        val screens = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens")
        val scanners = screens.listFiles { f: File -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { it.readText().contains("rememberLauncherForActivityResult(ScanContract())") ||
                it.readText().contains("ScanContract()") }
        assertTrue("no QR scanners found — has the scanner API changed?", scanners.isNotEmpty())
        for (file in scanners) {
            val source = file.readText()
            assertTrue(
                "${file.name} scans a QR but does not call importScannedCode",
                source.contains("importScannedCode"),
            )
            for (narrow in listOf("vm.joinCommunity(it)", "vm.importContactUri(it)")) {
                assertFalse(
                    "${file.name} still hands a scan straight to one decoder: $narrow",
                    source.contains(narrow),
                )
            }
        }
    }

    @Test
    fun `no group is large enough to need a screen inside it`() {
        // The rule that was broken: App held eight surfaces under one
        // chevron. Groups here are flat lists of tiles, and a group
        // growing past about seven is the signal to split it, not to
        // nest something (REBUILD-PLAYBOOK §6.2).
        for (group in settingsGroups()) {
            assertTrue(
                "group '${group.title}' has ${group.tiles.size} tiles",
                group.tiles.size in 1..7,
            )
        }
    }

    @Test
    fun `only the node's own settings are marked as needing a radio`() {
        val needs = settingsGroups().flatMap { it.tiles }.filter { it.needsRadio }.map { it.route }
        assertEquals(
            listOf("identity", "radio", "clock", "policies", "autoadd", "customvars"),
            needs,
        )
        // App and messaging settings are local and must work offline.
        val local = listOf("app", "channels", "blocking", "backup", "data", "about")
        for (route in local) {
            assertFalse(route in needs)
        }
    }

    @Test
    fun `static subtitles fit the one-line budget`() {
        for (tile in settingsGroups().flatMap { it.tiles }) {
            assertTrue("${tile.route}: '${tile.subtitle}'", tile.subtitle.length <= 60)
            assertFalse("${tile.route} has two sentences", tile.subtitle.contains(". "))
            assertTrue("${tile.route} has no subtitle", tile.subtitle.isNotBlank())
        }
    }

    // --- live subtitles ---------------------------------------------------

    @Test
    fun `connection subtitle names the radio when there is one`() {
        assertEquals("Connected to Node-3", connectionSubtitle(EngineState.Ready, "Node-3"))
        assertEquals("Not connected", connectionSubtitle(EngineState.Detached, "Node-3"))
        assertEquals("Connecting…", connectionSubtitle(EngineState.Connecting, null))
        // A missing label must not render "Connected to null".
        assertEquals("Connected to a radio", connectionSubtitle(EngineState.Ready, null))
    }

    @Test
    fun `transports subtitle always flags TCP as unencrypted`() {
        // Whenever TCP is on, the hub says so without being opened —
        // "is anything plaintext switched on" is the question this row
        // exists to answer.
        assertTrue(transportsSubtitle(ble = true, usb = false, tcp = true).contains("unencrypted"))
        assertTrue(transportsSubtitle(ble = false, usb = false, tcp = true).contains("unencrypted"))
        assertFalse(transportsSubtitle(ble = true, usb = true, tcp = false).contains("unencrypted"))
        assertEquals("Bluetooth, USB", transportsSubtitle(ble = true, usb = true, tcp = false))
        assertEquals("All transports disabled", transportsSubtitle(false, false, false))
    }

    @Test
    fun `radio subtitle reads freqKhz as kHz`() {
        // The captured value from the author's radio. `freqKhz` is kHz
        // despite every name in the ecosystem saying Hz, and reading it
        // as Hz is the defect that broke every regional preset for
        // months (LESSONS §5). 910525 kHz is 910.525 MHz.
        assertEquals("910.525 MHz · SF10 · 22dBm", radioSubtitle(910_525L, 10, 22))
        assertEquals("910.525 MHz", radioSubtitle(910_525L, null, null))
        assertEquals("Connect to read radio parameters", radioSubtitle(null, 10, 22))
        assertEquals("Connect to read radio parameters", radioSubtitle(0L, 10, 22))
    }

    @Test
    fun `every shipped preset renders inside a real LoRa band`() {
        // A constraint, not an example: the wrong unit would put every
        // one of these three orders of magnitude out, and no example
        // assertion I wrote myself would have noticed (§7.2).
        for (preset in RadioPresets.ALL) {
            val text = radioSubtitle(preset.frequencyKhz, preset.spreadingFactor, null)
            val mhz = text.substringBefore(" MHz").toDouble()
            assertTrue(
                "${preset.name} rendered as $mhz MHz",
                mhz in 300.0..2500.0,
            )
            assertEquals(preset.frequencyMhz, mhz, 0.001)
        }
    }

    @Test
    fun `counts are pluralised and zero says so in words`() {
        assertEquals("None configured", channelsSubtitle(0))
        assertEquals("1 channel", channelsSubtitle(1))
        assertEquals("4 channels", channelsSubtitle(4))
        assertEquals("Nobody blocked", blockingSubtitle(0))
        assertEquals("1 sender blocked", blockingSubtitle(1))
        assertEquals("3 senders blocked", blockingSubtitle(3))
    }

    @Test
    fun `an unnamed node says so rather than showing a blank row`() {
        assertEquals("No advertised name set", identitySubtitle(null))
        assertEquals("No advertised name set", identitySubtitle(""))
        assertEquals("No advertised name set", identitySubtitle("   "))
        assertEquals("Node-3", identitySubtitle("Node-3"))
    }

    @Test
    fun `an unencrypted database outranks everything else on its row`() {
        // This is the one fact on the Settings screen a user must not
        // have to open a page to discover, so it wins the subtitle
        // regardless of the map-tile setting.
        assertTrue(
            privacySubtitle(mapTilesEnabled = true, storageEncrypted = false).startsWith("⚠"),
        )
        assertTrue(
            privacySubtitle(mapTilesEnabled = false, storageEncrypted = false).startsWith("⚠"),
        )
        assertFalse(
            privacySubtitle(mapTilesEnabled = false, storageEncrypted = true).startsWith("⚠"),
        )
    }

    @Test
    fun `privacy subtitle states the network behaviour exactly`() {
        // REBUILD-PLAYBOOK §0.5 asks for network behaviour stated
        // exactly. Map tiles are the only outbound HTTP this app makes.
        assertEquals(
            "Map tiles off — no outbound HTTP",
            privacySubtitle(mapTilesEnabled = false, storageEncrypted = true),
        )
        assertEquals(
            "Map tiles on — the app's only outbound HTTP",
            privacySubtitle(mapTilesEnabled = true, storageEncrypted = true),
        )
    }

    @Test
    fun `notifications subtitle does not claim to be on when nothing is selected`() {
        assertEquals("Off", notificationsSubtitle(false, direct = true, channels = true))
        assertEquals(
            "On — direct messages and channels",
            notificationsSubtitle(true, direct = true, channels = true),
        )
        assertEquals("On — channels", notificationsSubtitle(true, direct = false, channels = true))
        assertEquals(
            "On, but no message type selected",
            notificationsSubtitle(true, direct = false, channels = false),
        )
    }

    @Test
    fun `an unencrypted database still wins the merged App row`() {
        // Privacy lost its own tile when four thin screens became one.
        // The warning must not have been folded away with it.
        assertTrue(
            appSubtitle("dark", notificationsEnabled = true, storageEncrypted = false)
                .startsWith("⚠"),
        )
        assertEquals(
            "Dark · notifications on",
            appSubtitle("dark", notificationsEnabled = true, storageEncrypted = true),
        )
        assertEquals(
            "Follow the system · notifications off",
            appSubtitle("system", notificationsEnabled = false, storageEncrypted = true),
        )
    }

    @Test
    fun `no spoke holds less than the tile that leads to it`() {
        // The rule learned by driving it: "Privacy and network" was a
        // whole screen for one toggle. Every remaining tile must lead to
        // a screen with more than a single control on it — encoded here
        // as: no route is a bare single-section wrapper.
        val singleSectionRoutes = setOf("appearance", "notifications", "privacy", "diagnostics")
        for (route in settingsRoutes()) {
            assertFalse(
                "$route was merged into the App screen and must not return as a tile",
                route in singleSectionRoutes,
            )
        }
        assertTrue("app" in settingsRoutes())
    }

    @Test
    fun `appearance and diagnostics report their current value`() {
        assertEquals("Follow the system", appearanceSubtitle("system"))
        assertEquals("Follow the system", appearanceSubtitle("anything unrecognised"))
        assertEquals("Light", appearanceSubtitle("light"))
        assertEquals("Dark", appearanceSubtitle("dark"))
        assertEquals("Off", diagnosticsSubtitle(false))
        assertTrue(diagnosticsSubtitle(true).startsWith("On"))
    }
}
