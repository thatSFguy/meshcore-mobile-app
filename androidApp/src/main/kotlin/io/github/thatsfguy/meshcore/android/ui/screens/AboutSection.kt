package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel

/**
 * About — PARITY.md §10 (`AboutScreen`, `ChangeLogScreen`).
 *
 * Small, but it is the one place the app's actual promises are written
 * down for the person using it rather than for whoever reads the repo.
 * The claims here are testable ones (no accounts, no analytics, one
 * outbound connection) — not slogans — because a security claim a user
 * can't check is just marketing.
 */
@Composable
fun AboutSection(vm: MeshCoreViewModel) {
    val context = LocalContext.current
    var changelog by remember { mutableStateOf(false) }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    Text("MeshCore Hardened", style = MaterialTheme.typography.titleSmall)
    HintText("Version $version")
    Spacer(Modifier.height(8.dp))
    HintText(
        "A minimal MeshCore client for off-grid encrypted LoRa messaging. No servers, no " +
            "accounts, no Google Play Services, no analytics, no crash reporting.",
    )
    Spacer(Modifier.height(8.dp))
    Text("What this app does and doesn't do", style = MaterialTheme.typography.labelLarge)
    for (line in listOf(
        "• Map tiles are the only outbound internet connection, and they can be turned off.",
        "• Messages, contacts and keys live on this phone and the radio. Nothing is uploaded.",
        "• Channels are obfuscated, not secure: AES-ECB with a 2-byte MAC, and the key never changes.",
        "• Advert signatures are verified before a contact is imported.",
        "• Secrets sit in the device keystore; the message database is encrypted.",
        "• \"Hardened\" describes this build's posture. It is not a protocol guarantee.",
    )) {
        Text(line, style = MaterialTheme.typography.bodySmall)
    }

    Spacer(Modifier.height(8.dp))
    ButtonFlowRow {
        TextButton(onClick = { changelog = true }) { Text("What's new") }
    }

    if (changelog) {
        AlertDialog(
            onDismissRequest = { changelog = false },
            title = { Text("What's new") },
            text = {
                Column {
                    for ((release, notes) in CHANGELOG) {
                        Text(release, style = MaterialTheme.typography.labelLarge)
                        for (note in notes) {
                            Text("• $note", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { changelog = false }) { Text("Close") } },
        )
    }
}

/**
 * Release notes, newest first.
 *
 * Kept in the app rather than pointed at a URL: the whole premise is
 * that this thing works with no network, and a changelog behind a link
 * is a changelog you can't read in the field.
 */
private val CHANGELOG: List<Pair<String, List<String>>> = listOf(
    "0.7.13" to listOf(
        "Blocking a repeater no longer breaks its console. It never could block a " +
            "repeater — traffic through one carries the original sender's key, so there " +
            "is nothing on the repeater's key to block. What it did do was swallow the " +
            "node's CLI replies silently, so the Console went quiet while the Settings " +
            "form carried on working.",
        "A block now never applies to a reply you asked for by name.",
        "The Block action is gone from repeaters and sensors, which send no messages of " +
            "their own. Rooms keep it — a room's chat really does arrive as direct " +
            "messages from the server. It also stays visible on anything currently " +
            "blocked, so nothing can be blocked with no way back.",
    ),
    "0.7.12" to listOf(
        "A repeater that goes quiet now repairs itself. The app clears the route, " +
            "re-establishes it and retries — what you were doing by hand with Sign out / " +
            "Sign in. The first repair is a login carrying no password, which is enough " +
            "for a node that already knows you, so your credential only goes back on the " +
            "air if that fails.",
        "There is no session to expire on a repeater: a login is permanent and survives a " +
            "reboot. What re-signing-in ever fixed was the route.",
        "Except when you pinned the route yourself, where it says so instead — a pinned " +
            "route cannot be repaired this way, so it fails fast and names the pin.",
        "The settings-QR generator estimates sensitivity and path loss across every " +
            "spreading factor and bandwidth, with airtime beside it. No distance: that " +
            "needs terrain, and a confident wrong number is worse than none.",
    ),
    "0.7.11" to listOf(
        "\"Scan settings QR…\" is on both radio screens, beside \"Use a regional " +
            "preset…\" — Settings → Radio for the radio in your hand, and a repeater's " +
            "own Radio panel for a node across the mesh.",
        "Scanning from the Chats button now does something. The confirmation dialogs " +
            "were drawn only by the Nodes screen, so a code scanned anywhere else set " +
            "everything up and then showed you nothing — contact cards and channel " +
            "shares included.",
        "The scanner opens the right way up, and can now read dark-mode QR codes from " +
            "every button. One launcher was built by hand and missed the configuration " +
            "carrying both, so it silently could not decode about half the codes in " +
            "circulation.",
    ),
    "0.7.10" to listOf(
        "The access list works — it never had. It asked over the air for something only " +
            "the node's own serial console can answer, so the node replied \"??: acl\", " +
            "which reads like old firmware rather than an unanswerable question.",
        "…and it no longer invents an entry. The first working version showed a fourth " +
            "row, 000000000000 Guest — an account with access to your repeater that does " +
            "not exist. The encrypted reply is padded, and the padding was exactly the " +
            "size of one more entry.",
        "Unused channel slots are out of the Chats list again. Two parts of the app " +
            "disagreed about what counts as a channel, and opening Settings → Channels " +
            "put the blank ones back. One rule now, which also drops a channel you have " +
            "just cleared instead of leaving a nameless row.",
        "Settings → Channels lists your real channels and an Add channel button, not " +
            "every empty slot the radio happens to have.",
    ),
    "0.7.9" to listOf(
        "Join a mesh by scanning a QR. A code can carry an area's frequency, bandwidth, " +
            "spreading factor, coding rate, path-hash width and flood region. Scanning " +
            "shows every value and asks first — nothing in a QR is signed.",
        "The code carries no transmit power and no channel keys. Power is the legal limit " +
            "where you are standing; a key would make the code a secret rather than " +
            "something safe to print.",
        "Applying radio settings to a repeater now offers to reboot it. The node saves " +
            "them and keeps running on the old ones until it restarts, so without this a " +
            "preset looked like it had done nothing. Your own radio applies immediately.",
        "Generate codes at thatsfguy.github.io/meshcore-mobile-app/settings-qr/ — it runs " +
            "in your browser, and the image carries the settings as readable text.",
    ),
    "0.7.8" to listOf(
        "New preset: USA Rural — 906.375 MHz, 250 kHz, SF9, CR4/5, 22 dBm. Wide " +
            "bandwidth at a higher spreading factor, for sparse coverage where hops are " +
            "long and few.",
        "Presets can be applied to a repeater or room, not just the radio in your hand. " +
            "The confirmation names the node it will retune, and warns that this radio " +
            "cannot reach it afterwards to undo a mistake.",
        "Applying a preset remotely sends TX power before the retune — anything sent " +
            "after it goes out on parameters the node has already left.",
    ),
    "0.7.7" to listOf(
        "Frequency is MHz and bandwidth is kHz on both the local and the remote radio " +
            "screens. They disagreed because the transports do — the companion API takes " +
            "kHz and Hz, the CLI takes MHz and kHz — and each screen showed its own. The " +
            "wire is unchanged; conversion happens at the edge.",
        "The frequency no longer reads 910.5250244. The node stores it as a 32-bit " +
            "float, 910.525 has no exact representation in one, and the CLI printed the " +
            "nearest value in full. It is shortened only when the shorter form is the " +
            "same number to the radio.",
    ),
    "0.7.6" to listOf(
        "A delivered message can no longer be reported as a failure. Each retry opened " +
            "its own listener, and the engine's event stream has no replay, so an ACK " +
            "arriving when nothing was listening was lost — during the backoff between " +
            "attempts, for an earlier attempt, or just after the last one gave up.",
        "A late ACK now corrects the message. It still goes to Failed when the attempts " +
            "run out, but the app listens for another 30 seconds and flips it to " +
            "Delivered if the reply arrives. A timeout is an estimate, not a deadline " +
            "the mesh agreed to.",
    ),
    "0.7.5" to listOf(
        "The \"4 B\" path-hash option never worked and is gone. Mode 3 is reserved and " +
            "the firmware refuses it at every layer, so tapping it did nothing and said " +
            "nothing. The real range is 1–3 bytes; the command help said \"0–3\" too.",
        "You can set the path hash width on a repeater from the app — the one radio " +
            "parameter that still needed the console. Settings → Radio on the node you " +
            "are administering. Every node on a mesh must match.",
        "Noise floor reads dBm, not dB — an absolute power, like the RSSI beside it. " +
            "SNR is the ratio and keeps dB.",
    ),
    "0.7.4" to listOf(
        "A contact's route is drawn where you edit it. \"Show route on map\" set a flag " +
            "the Map tab read and left you in the routing sheet — it did not navigate, " +
            "and the summary it drew over there was painted over by the map itself. " +
            "Tapping it did nothing you could see.",
        "The route now renders inline in the routing sheet, on the same component the " +
            "message info sheet uses. The Map tab is the node map and nothing else again.",
        "A route whose last hop IS the destination no longer stacks two pins on one " +
            "spot. A hop we cannot identify never stands in for the destination, however " +
            "well its hash matches.",
    ),
    "0.7.3" to listOf(
        "Fetch neighbours works — it never had. The request was one byte where the " +
            "firmware reads eleven, so the node read \"return zero entries\" out of " +
            "whatever followed it and answered with an empty table. \"Try again, the " +
            "table may be paged\" was wrong: it was not paging, and retrying could not " +
            "have helped.",
        "Neighbours ask for a 6-byte key prefix instead of 4, and each one shows how " +
            "long ago it was heard — elapsed time on the node's clock, which is what the " +
            "firmware actually sends.",
        "The section says what a neighbour table is: other repeaters, heard directly at " +
            "zero hops. Rooms, companions and sensors never appear, which is why a " +
            "healthy repeater lists two or three. Nodes that keep no table don't offer " +
            "the button.",
        "New: Probe. Nothing keeps a neighbour table current — it lists who advertised " +
            "since the node booted, not who is in range. Probe makes nearby repeaters " +
            "answer now; on a live node it found one at 3.5 dB that had been missing for " +
            "hours. It spends airtime, so it is its own button, admin only.",
        "The admin console shows console traffic only. It had rendered the whole thread " +
            "with the node, so a room's own chat appeared among the CLI replies — and " +
            "\"Clear console\" deleted those messages too. Clearing now takes the " +
            "console and nothing else.",
        "The console is ordered by when things arrived here, not by the timestamp the " +
            "node claims. Repeaters rarely have a correct clock, so a reply could sort " +
            "hours away from the command that caused it.",
    ),
    "0.7.2" to listOf(
        "Direct-message repeats actually work now — 0.7.1 credited one only when exactly " +
            "one message to that contact sat in a two-minute window, so sending a few in " +
            "a row discarded every result. Correlation is on echo timing instead.",
        "Confirmed on a live mesh: a message re-broadcast by two nodes, both copies heard " +
            "and credited to the right message.",
        "A message can read \u2717 (try 3) \u00b7 \u21bb 2 — failed, but two nodes " +
            "carried it. That tells you the mesh moved it and nobody answered, which the " +
            "delivery tick alone cannot.",
        "The bubble badge is the glyph and the count only; the details sheet names every " +
            "node in full.",
    ),
    "0.7.1" to listOf(
        "Repeats now show on the sent message itself, and the message details sheet " +
            "names each node under \"Repeated by\".",
        "Channel posts are exact: the app decrypts the echo and matches it to its own " +
            "outbox. Direct messages are correlated and refuse when two sent messages fit.",
        "No repeats heard shows nothing rather than \"0\" — a node that carried your " +
            "message onward without a copy coming back cannot be heard from here.",
        "Wording corrected to \"node\": the first live measurement was relayed by a room " +
            "server, and companions with client-repeat relay too.",
    ),
    "0.7.0" to listOf(
        "New: \"Who repeats me\" (Nodes → ⋮) — which repeaters are actually carrying your " +
            "traffic. Tap Send a flood advert and watch which ones send a copy back.",
        "Each row separates \"heard you\" from \"you heard it\": only the repeater whose " +
            "transmission reached this radio has a measured SNR, so only that one shows a " +
            "number. A repeater doing both is flagged Two-way.",
        "Every row comes from a copy of your own signed advert, so nobody else can put a " +
            "repeater on your list without your private key.",
        "It is a floor, not a coverage map — a repeater that carried your traffic onward " +
            "without a copy coming back cannot appear, and the screen says so.",
    ),
    "0.6.7" to listOf(
        "The app's \"Forget\" on a saved node is now \"Remove\" — it was being read as " +
            "Android's \"Forget device\", which is the one that drops the Bluetooth " +
            "pairing. Only Android can do that.",
        "After a PIN change and reboot, the app says so and offers to open Bluetooth " +
            "settings — otherwise the phone reconnects on the old pairing and the new PIN " +
            "looks like it did nothing.",
    ),
    "0.6.6" to listOf(
        "Forget now actually forgets — it left the radio in the auto-reconnect memory, so " +
            "it came back on the next connect and was then missing from Saved nodes.",
        "Any successful connection appears in Saved nodes, including automatic reconnects.",
    ),
    "0.6.5" to listOf(
        "Fixes the PIN screen from 0.6.4: it accepted PINs the radio refuses (anything " +
            "starting with 0), claimed the PIN could not be read when the node reports it, " +
            "and never said that a reboot is needed for the change to take effect.",
        "The screen now shows the configured PIN and offers to reboot straight after.",
    ),
    "0.6.4" to listOf(
        "Change the radio's Bluetooth pairing PIN — Settings, Radio link, Bluetooth PIN. " +
            "Nodes without a screen ship with 123456, which is public and identical on " +
            "every one of them.",
        "The current PIN is never shown: the firmware gives no way to read it back.",
        "Changing it invalidates your phone's existing pairing, so it says so first.",
    ),
    "0.6.3" to listOf(
        "Restoring a backup now actually restores contacts — the option existed, counted " +
            "them, and then never wrote them.",
        "Any MeshCore QR can be scanned from either scanner. A repeater's contact code " +
            "scanned from Chats used to answer \"Invalid community code\".",
    ),
    "0.6.2" to listOf(
        "The route map painted over the rows around it, and pinch-zooming threw the route " +
            "lines across the whole sheet. It is clipped to its own bounds now.",
        "Message info scrolls — the map had pushed the hop list out of reach.",
    ),
    "0.6.1" to listOf(
        "The route map drew no tiles unless you had opened the Map tab earlier in the same " +
            "session — it looked exactly like map tiles being switched off.",
        "A short route zoomed in past the deepest level that has tiles, producing the same " +
            "empty grid from a different cause.",
    ),
    "0.6.0" to listOf(
        "Message info now draws the route on a map — long-press a received message, then Info.",
        "Nodes that never advertised a position are placed approximately so the shape of the " +
            "route is visible: a hollow \"?\" pin on a dotted line, never a solid pin.",
        "A hop that could not be identified at all is never placed. The line across it is " +
            "dashed — no route is being claimed there.",
    ),
    "0.5.7" to listOf(
        "Tapping a message notification opens that conversation, instead of just opening " +
            "the app and leaving you to find it. Back from there lands on Chats.",
    ),
    "0.5.6" to listOf(
        "Reply notifications showed the quote and the answer run together — a reply of " +
            "\"good\" to \"yeah\" arrived as \">yeah good\". The line shown before you " +
            "expand is now the reply; expanding shows the quoted message above it.",
    ),
    "0.5.5" to listOf(
        "The conversation list showed the message being replied TO instead of the reply. A " +
            "reply is sent as the quoted text followed by what you actually said, and the " +
            "preview was taking the first 80 characters.",
        "Reactions now notify — a thumbs-up is often the whole reply, and it used to arrive " +
            "in silence. Only for reactions to your own messages.",
        "Opening a conversation clears its notification. It previously only cleared if you " +
            "tapped the notification itself.",
        "Send advert (0-hop) and Send advert (flood) are on the Chats menu — announcing " +
            "yourself is situational, and it had ended up three taps deep in settings.",
    ),
    "0.5.4" to listOf(
        "Signing in to a repeater now retries if nothing comes back, and waits as long as " +
            "the radio says to instead of a flat 20 seconds. The last attempt clears a dead " +
            "path and floods, the same as a message.",
        "A rejected password is never retried. It won't start working, and the password is " +
            "on the air in cleartext each time.",
        "\"The node rejected that password\" and \"No answer from the node\" are now " +
            "different messages, because they need different fixes.",
    ),
    "0.5.3" to listOf(
        "A direct message that fails twice is now retried as a flood, once — and the dead " +
            "path is cleared first, so your radio can learn a live one from the reply. " +
            "Before this, all three attempts went down the same broken route.",
        "This is MeshCore's documented default, and like the stock app it can be turned " +
            "off: Settings → Mesh policies → Flood on the last message retry.",
    ),
    "0.5.2" to listOf(
        "Command help said \"1 commands\" when a search matched exactly one.",
    ),
    // The first release in this project's history where the layout was
    // driven on a phone against a live repeater BEFORE it shipped,
    // rather than after.
    "0.5.1" to listOf(
        "Tapping a repeater, room or sensor now goes straight to signing in, and " +
            "signing in leads straight to its tools. Checking a repeater's status went " +
            "from five taps and a scroll to three.",
        "The Status screen asks the node for status when you open it, instead of showing " +
            "five \"Fetch\" buttons and waiting to be told.",
        "There is no longer a signed-out version of the admin screen to get stuck on: " +
            "you sign in, or you go back.",
        "Appearance, notifications, privacy and the diagnostics log are one screen again. " +
            "Splitting them gave four pages holding one switch each.",
        "Settings rows that need a radio are no longer greyed out — six of the first ten " +
            "rows looked broken before you had done anything.",
        "Long-press a node for its details (key, position, routing, rename, QR).",
    ),
    "0.5.0" to listOf(
        "Repeater, room and sensor administration is now a hub: one screen per tool " +
            "(Status, Settings, Regions, Identity, Console, Command help) instead of six " +
            "tabs sharing one screen.",
        "Signing in to a node is a dialog, and the node's answer — ADMIN or GUEST — is " +
            "shown on the hub. Nothing about the session is guessed from what you typed.",
        "Settings is a list of pages instead of eleven expandable sections. Each row " +
            "shows its current value, so which transports are on, what frequency the radio " +
            "is using and whether map tiles are being fetched are all answered without " +
            "opening anything.",
        "Long explanations are one line with a \"More\" tap rather than three sentences " +
            "on every row.",
        "This release changes how the app is laid out. It has not yet been run against a " +
            "radio.",
    ),
    "0.4.0" to listOf(
        "\"Arrived via\": the route a received message actually travelled, hop by hop. " +
            "Shown only when it is known — a flooded message says so instead of guessing.",
        "Manual routing is now pick-and-order: tap a repeater to add it, arrows to reorder. " +
            "No more typing hex.",
        "Config backup and restore, with secrets encrypted under a passphrase or left out.",
        "Message retention and a single purge-local-data action.",
        "Blocked contacts (by public key) and hidden channel names (by name — a filter, not a block).",
        "Regional radio presets, and a first-run setup flow.",
        "Contact permissions, active node discovery, and per-conversation notification levels.",
        "Repeater neighbour tables and identity-key management.",
        "Sensor nodes as a first-class type; routes drawn on the map, with gaps where a hop " +
            "can't be placed rather than a guessed line.",
        "Fixes: unconfigured channels appearing in Chats; path trace sending a request no node " +
            "would answer; \"Apply path\" silently doing nothing; hop counts that were really " +
            "byte counts.",
    ),
    // 0.3.0 shipped regions and nothing else. The rest of this list was
    // written into the changelog in the same run but landed AFTER the
    // tag, so the released 0.3.0 build never contained it — moved to
    // 0.4.0, where it actually ships.
    "0.3.0" to listOf(
        "Regions: named flood scopes, per-channel scoping, discovery from nearby repeaters.",
        "Repeater region administration.",
    ),
    "0.2.x" to listOf(
        "Repeater admin: access list, command help, noise floor, clock drift, position picker.",
        "Messaging: links, quote-replies, reactions, drafts, pinning, nicknames.",
        "Per-contact telemetry, channel QR sharing, room post authorship.",
    ),
    "0.1.0" to listOf(
        "First release: BLE/USB/TCP transports, direct messages and channels, node map.",
    ),
)
