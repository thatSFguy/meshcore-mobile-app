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
        "A self-contained MeshCore client for off-grid encrypted LoRa messaging. No " +
            "servers, no accounts, no Google Play Services, no analytics, no crash " +
            "reporting.",
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
    "0.8.10" to listOf(
        "A repeater far away is given the time the radio says it needs. Fetching status, the " +
            "access list or the neighbour table waited a flat 30 seconds however far the node " +
            "was — while signing in to the same node already used the radio's own estimate. " +
            "That is why a distant repeater would let you log in and then fail every fetch.",
        "The wait now comes from the radio's own figure, computed from the airtime and hop " +
            "count of the path it just used, doubled because a fetch has to come back too, and " +
            "held between 20 and 90 seconds.",
        "The wait says what is actually known: \"Sent over the stored path · reply expected " +
            "within 34s\", counting down, and then \"past the radio's estimate, still " +
            "listening\" — rather than a spinner that reads the same at second 1 and second 60.",
        "No extra retry was added: a signed-in request already tries, resets the route and " +
            "tries again, re-authenticates and tries a third time. Each of those now gets the " +
            "proper wait, which was the actual problem.",
    ),
    "0.8.9" to listOf(
        "\"Last heard\" now means when your radio heard the node, not what the node claims. A " +
            "repeater heard this morning was showing as \"20688 days ago · Jan 1, 1970\", and " +
            "others as 830 days — those nodes are on the air; their clocks are wrong.",
        "Sort by \"Last heard\" and the \"Heard in last 24 h\" filter were the ones that " +
            "mattered: a node with a stopped clock could never look recently heard however " +
            "recently it was heard, and one claiming a future date sorted to the top for ever.",
        "The node list, the contact sheet and the map popup showed the same wrong age.",
        "A node whose clock is wrong is now told about rather than quietly corrected — the " +
            "contact sheet says what its own clock claims, but only when that disagrees with " +
            "what we observed by more than a day.",
    ),
    "0.8.8" to listOf(
        "The map draws a repeater's neighbours. Tapping a pin used to do nothing; it now opens " +
            "the node, and for a repeater it draws lines to every neighbour the app can place, " +
            "coloured by the signal that repeater heard it at, with the quality written on the " +
            "line itself — \"Strong · 12.0 dB\" — so there is no colour key to decode.",
        "Remove stale nodes: a slider from 3 to 30 days on the Nodes menu, and everything " +
            "nothing has been heard from for longer than that goes from the radio's contact " +
            "list. Favourites are never removed, whatever the slider says, and nor is a node " +
            "never heard from at all. The list is shown before the button is pressed.",
        "\"Last heard\" there means what THIS radio heard, not what the node claims. An advert " +
            "carries the sending node's own clock, and on this mesh that reported live nodes as " +
            "last heard 830 days ago — sweeping on it would have deleted them for having a bad " +
            "clock.",
        "A neighbour table is now kept, stamped with the moment it was read. The radio reports " +
            "how long ago it heard each node — an elapsed time on its own clock — so a stored " +
            "reading with no collection time would go on claiming \"4 minutes ago\" a week " +
            "later. The popup says how old the whole reading is.",
        "Every neighbour fetch is recorded, from whichever screen asked, so a read on a " +
            "repeater's Status screen also fills the map.",
        "Fetching from the map signs itself in: the session you already have, then the password " +
            "saved for that node, and otherwise a blank one, which is the ordinary read-only " +
            "way in. It says which it is about to use, and never saves a blank password.",
        "A repeater that turns a password down says nothing at all, so a silence is no longer " +
            "reported as a refusal — it may want a guest password, or be out of reach.",
        "A line is only drawn where the app is sure who it points at. A neighbour is named by " +
            "six bytes of its public key, so a row matching two known nodes, or none, or one " +
            "with no position, is listed with the reason rather than drawn at a guess.",
    ),
    "0.8.7" to listOf(
        "A long conversation opens at its newest message, not at its oldest. Leaving a big " +
            "chat and going back in put you at the very top, above \"Load older\", with the " +
            "whole thread to scroll back down.",
        "The \"Load older\" row is no longer shown before any messages are. The count and the " +
            "messages are separate database queries and counting is faster, so for a moment the " +
            "list held only that row — and a list remembers its position by which item was " +
            "in view, so it clung to it while the messages arrived in front of it.",
        "A conversation now lands on its newest message once its contents arrive, whatever the " +
            "list did while it was empty. Earlier fixes relied on the list anchoring itself " +
            "correctly through every way a thread can fill in; this states the rule outright. " +
            "It happens once per conversation, so loading older messages keeps your place.",
        "The message list is now tested on a real phone rather than by reading the source. " +
            "This bug has been reported five times, and every fix was guarded by a test that " +
            "checks the code still says the right thing — which cannot see layout, so every " +
            "version of the bug passed. Six tests now assert that the newest message is really " +
            "on screen; three of them fail against the build that shipped last.",
        "The README now records which reaction format this app sends and why — a survey of " +
            "the local mesh came back about 6:1 in favour of MeshCore Open's. If that changes, " +
            "the app switches. Both formats are read either way.",
    ),
    "0.8.6" to listOf(
        "Reactions from MeshCore One land on the right message. They were arriving as " +
            "ordinary messages reading an emoji, a name in brackets and eight " +
            "random-looking characters — which is what a reaction looks like to a " +
            "client that does not know the format. MeshCore has no reaction field, so " +
            "every app invents one; this one now reads MeshCore One's as well as the " +
            "format it already sent.",
        "We still send the same format as before, so nothing that worked stops working.",
        "A reaction whose target cannot be found now reads \"reacted to an earlier " +
            "message\" instead of raw wire text, whichever format it arrived in.",
        "A message that merely tags somebody is still a message: the reaction shape is " +
            "matched strictly, because a false match would hide what somebody typed.",
    ),
    "0.8.5" to listOf(
        "Chats open at the newest message again. A reversed message list packs its " +
            "content against the composer — that is what keeps the newest bubble " +
            "visible on entry, on arrival and when the keyboard opens — and the 4dp " +
            "gap between bubbles had quietly replaced that default. Short threads floated " +
            "at the top with a hole above the composer, and the keyboard pushed the newest " +
            "messages out of view.",
        "A thread no longer reopens wherever you last left it, which for a chat meant " +
            "opening days back and scrolling down through everything since.",
        "A route step that could be more than one node now names them, nearest first, " +
            "instead of saying \"(2 matches)\". A hop is a truncated hash, so several nodes " +
            "can answer to it; naming them all asserts nothing, and the nearest is the " +
            "likelier carrier. A node with no known position is listed last — unknown " +
            "is not near.",
    ),
    "0.8.4" to listOf(
        "Replacing a repeater's identity key can restart the node, and no longer leaves " +
            "you waiting for it. The confirmation dialog has a \"Restart the node now\" " +
            "tick box, on by default — a stored key does nothing until the node reboots.",
        "The new identity is added to your radio's contact list straight away, carrying " +
            "the old entry's name, favourite mark, Bluetooth address, nickname and saved " +
            "password. A rebooted node announces itself only to radios in direct range, " +
            "and its next flooded advert can be 47 hours away, so waiting for one is not " +
            "a plan. The old entry is left alone until you delete it: until the node " +
            "restarts, it is still the live one.",
        "The restart is reported honestly. A node never acknowledges a reboot, so the app " +
            "waits for it to come back and tries to sign in to the new identity — a " +
            "granted session is the only real proof. Silence says \"not yet confirmed\", " +
            "and says that is not yet a failure.",
        "The admin command box no longer autocorrects. It was an ordinary text field, so " +
            "Android capitalised the first letter and \"corrected\" the rest — on a screen " +
            "whose whole purpose is sending strings a node compares byte for byte. A " +
            "mangled command comes back as an error that names nothing, so the app looks " +
            "broken and the field looks right. Region name and parent are fixed the same " +
            "way.",
        "The Chats tab carries an unread count. Each conversation already had its own " +
            "badge inside the list, which is no use from the Nodes, Map or Settings tab. " +
            "The total stops at \"99+\", and there is no badge at all at zero.",
    ),
    "0.8.3" to listOf(
        "Generating a repeater identity key produced a key no node would accept. MeshCore " +
            "stores a 64-byte private key and reads exactly 128 hex characters; the app " +
            "sent the 32-byte seed as 64, so every generated key came back \"Error, bad " +
            "key\" — and every key read back from a node was 128 characters the app did " +
            "not recognise, reported as a refusal. Both directions are fixed, and the box " +
            "takes either form.",
        "\"Generate a new one\" now produces a key whose leading bytes are this node's " +
            "alone. A routed path names a repeater by one to three leading bytes of its " +
            "public key, so two repeaters sharing those bytes are one node as far as a " +
            "stored route is concerned. The generator asks the node how wide its path hash " +
            "is, avoids every node this phone knows about, and says which bytes the new " +
            "identity would answer to.",
        "When a mesh has no free name left, the clash is chosen rather than accepted. Every " +
            "candidate is scored by the worst node it would collide with — distance from " +
            "this radio where both have a position, hops otherwise, and an ordinary node " +
            "always ahead of a repeater — so the collision lands on something far away " +
            "instead of the repeater on the next hill. Whatever it settles for is named on " +
            "screen.",
        "Keys the firmware refuses outright — public keys beginning 00 or ff, about one in " +
            "128 — are never offered, and a key typed by hand is checked against the same " +
            "rules.",
        "Reading a key explains that a node only answers over its USB serial console, never " +
            "over the mesh, so the refusal is no longer indistinguishable from a bad link.",
    ),
    "0.8.2" to listOf(
        "The node list has a sort and filter menu beside the search box. Order by recent " +
            "activity, last heard, name or fewest hops; show only favourites, only unread, " +
            "or only nodes heard in the last 24 hours. The choice is remembered, and a " +
            "list emptied by a filter says so rather than looking empty.",
        "Node rows say how long ago a node was heard — \"14 min ago\" — instead of a date " +
            "that made you do the subtraction and read the same for everything heard today. " +
            "The detail sheet still shows the exact timestamp.",
    ),
    "0.8.1" to listOf(
        "A radio that goes out of range comes back on its own. A connect attempt that " +
            "began while the radio was walking away used to hang forever, with the " +
            "reconnect loop stuck behind it — so returning to range did nothing. " +
            "Attempts now have a deadline, and after the first try the app asks Bluetooth " +
            "to complete the link by itself whenever the radio next appears.",
        "Reconnect backoff no longer counts time spent failing as time connected, so an " +
            "absent radio is retried at a widening interval instead of every second.",
        "The Bluetooth link writes to the diagnostics log. Radio addresses are trimmed to " +
            "their last two octets, so the log stays shareable and two radios stay " +
            "distinguishable.",
    ),
    "0.8.0" to listOf(
        "Firmware updates over Bluetooth. An nRF52 radio can be updated from Settings → " +
            "Firmware: pick a build, confirm the board, watch it flash. Needs companion " +
            "firmware v1.15 or newer, which is when MeshCore started exposing the update " +
            "service; the screen says so plainly when a radio does not have it.",
        "Repeaters and room servers have a Firmware tile too, for admins. `start ota` " +
            "goes over the mesh; the firmware itself does not and cannot — you have to be " +
            "within Bluetooth range of the node to finish it. The node keeps repeating " +
            "with its Bluetooth on, and only reboots when the transfer starts.",
        "The radio reports what it is running. Board name, firmware version and build " +
            "date were always in the DEVICE_INFO frame and were being skipped.",
        "Firmware can be fetched in the app or opened from storage. Checking reads the " +
            "MeshCore release list from GitHub — the second outbound request this app " +
            "makes, after map tiles, and only when you ask. Downloads are verified " +
            "against the checksum the release published and refused on a mismatch.",
        "The board is confirmed by name before anything is written. A DFU package cannot " +
            "say which board it is for — every nRF52 board declares the same device type " +
            "— so the filename is the only evidence and a human reads it.",
        "ESP32 boards are told the truth: their over-the-air path is a WiFi hotspot and a " +
            "browser, which this app does not offer and does not pretend to.",
        "You choose the version, not just the latest. Every published version is listed " +
            "and the installed one is marked — MeshCore releases often, and the version " +
            "worth putting on a hard-to-reach node is usually one already running " +
            "somewhere you can reach. Going backwards is allowed.",
        "A node stuck in update mode can be recovered by long-pressing it in the node " +
            "list. The address it announced is remembered, so it can be restarted or " +
            "flashed again without the mesh — which is what it no longer has.",
        "A transfer is refused over a link too weak to finish it. The node erases its " +
            "firmware before writing the new copy, so a transfer that stops half way costs " +
            "a visit; below -95 dBm the app says so and offers to try anyway.",
        "A failed update says what state the node is left in. Interrupted means waiting in " +
            "update mode, not bricked — the difference between a retry and a trip up a mast.",
        "The update link asks for the fastest connection interval it can get. Android's " +
            "default packs several packets into each connection event, so the bootloader " +
            "gets a whole window in two or three bursts — faster than it writes to flash, " +
            "and it answers \"operation failed\" a few hundred bytes in. A node that still " +
            "cannot keep up is retried more slowly automatically.",
        "A finished update is no longer reported as a failure on its last write — the node " +
            "reboots while handling it, so it can never be acknowledged. Packets are also " +
            "sized from the link now rather than fixed at 20 bytes, which is twelve times " +
            "quicker on a bootloader that negotiates a larger one.",
        "\"Retry more slowly\" was a dead button. It was offered on the failure screen and " +
            "did nothing, at the one moment the node is sitting with its firmware erased.",
        "A node is asked for its version before it is asked to enter update mode. `start " +
            "ota` cannot be taken back without walking to the node, so sending it to one " +
            "that has stopped listening does not fail — it just leaves the app believing " +
            "something. `ver` goes first: free if unanswered, proof the firmware is " +
            "running if it is answered, and the version recorded at the last moment " +
            "anything can ask for it. Only the node's own reply puts it in update mode.",
        "Signing in to a node takes it out of update mode. A bootloader has no LoRa stack, " +
            "so any answer to a login — even a rejected password — proves otherwise.",
        "A node's `start ota` reply is read once rather than for ever. The admin console " +
            "is stored, so that reply was being re-read as a present-tense fact on every " +
            "visit, and no correction could survive it.",
        "A node's board and firmware version are no longer swapped. They were asked for at " +
            "the same moment and their replies could be filed under the other command, so " +
            "a version was stored as the board name — which is what the firmware picker " +
            "and the search for a node in update mode both work from.",
        "Packet flow control follows the MeshCore FAQ's per-board figures, and the board " +
            "name now reaches the flash step for a node that has left the mesh.",
        "A transfer can no longer hang inside a single packet. Every step was bounded " +
            "except the one that sends data: each write waits for the Bluetooth stack to " +
            "confirm it, and nothing bounded that wait, so a confirmation that never came " +
            "left the transfer frozen at its last byte count with no error and no way out " +
            "but killing the app.",
        "The short connection interval is re-requested as the transfer runs. It is " +
            "advisory, stacks let it lapse, and nothing can read it back — so asking once " +
            "at the start covered the part that was never in doubt.",
        "A node that announced its update address is found by it. The search applied the " +
            "bootloader's +1 to an address the node had not jumped to yet, leaving its " +
            "`_OTA` name — which every node in update mode wears — as the only thing to " +
            "match on. An announced address now means that node in either state.",
        "The radio in your pocket is released before another node is flashed, instead of " +
            "holding a second live Bluetooth link beside the transfer.",
        "The packets are paced, which is what made an over-the-air update finish. The " +
            "bootloader buffers each packet and flushes it to flash in the background, and " +
            "answers \"operation failed\" when that buffer fills — its receipt " +
            "notifications cannot prevent it, because one means a packet was received, not " +
            "that it reached flash. Sending as fast as the Bluetooth stack allowed died at " +
            "5-15 KB every time. A 20 ms pause between packets, used only on a link that " +
            "never raised its MTU, moves a 404 KB image in about eight minutes.",
        "A node's board and firmware version are no longer filled in by an unrelated " +
            "reply. `OK - mac: …` was refused as a board name and accepted as a version, " +
            "so a repeater read as \"ProMicro DIY · OK - mac: FF:5C:…\" — and that string " +
            "was stored and compared against release tags.",
        "A node whose firmware has already been erased is never restarted. Abandoning a " +
            "transfer used to reset the node so its bootloader would forget the " +
            "half-finished session — right until the start step is accepted, which is " +
            "when the node erases its application. A bootloader restarted with nothing " +
            "to boot comes back in USB mass-storage mode and stops advertising over " +
            "Bluetooth, so the reset meant to rescue the node was what put it out of " +
            "reach. It is now left advertising and retryable.",
        "Packet writes no longer wait for a confirmation that may never come. Android " +
            "reports a no-response write as done when it frees the buffer, and that is a " +
            "courtesy rather than a guarantee — waiting on it deadlocked the transfer on " +
            "real hardware while every control-point write completed instantly. Flow " +
            "control is the node's own receipt notification instead.",
        "A node's board, firmware version and update address survive reconnecting to the " +
            "radio. The radio owns the contact list and it is re-read on every " +
            "connection, which wiped everything the app had learned and the radio had " +
            "not — the very fields that exist because the node cannot be asked again.",
        "The update log no longer erases itself. Progress was written on every " +
            "acknowledgement — about 1,860 lines against a 500-line buffer — so a failed " +
            "flash left a log holding nothing but its own progress bar. Progress is now " +
            "sampled and carries the transfer rate.",
    ),
    "0.7.16" to listOf(
        "Channel codes carry their flood scope. region_scope is documented and the " +
            "mainstream app has emitted it since v1.47.0; this app dropped it, so joining " +
            "a scoped channel flooded the whole mesh while its owner believed it was " +
            "contained. Now shown before you join, applied after, and shared on your codes.",
        "A code whose region this app can't use now says so in the join dialog. It used " +
            "to be indistinguishable from a code with no region at all.",
        "Re-sharing a scoped code to someone who already has the channel applies the " +
            "scope — that is how a community rolls a region out, and it used to be a no-op.",
        "Deleting a channel forgets its region. The radio hands the freed slot to the " +
            "next join, which inherited the scope of a channel you had deleted.",
        "Favouriting a contact, or pinning its route, no longer stops its adverts. The " +
            "firmware reads that field as the contact's last advert and drops anything " +
            "older as a replay, so writing the phone's clock into it froze the contact's " +
            "name, location and route until the node's own clock caught up.",
        "A channel can no longer be overwritten by a failed channel read — and a " +
            "channel's key cannot be recovered from the radio once it is gone.",
        "Direct contacts stop reporting a pinned route they never had, on a mesh with " +
            "2-byte hop hashes.",
        "Inbound messages are no longer dropped in the first moment after connecting.",
        "A meshcore:// link pasted with a sentence after it works, and so does one with " +
            "anything after a #.",
        "On a device whose keystore refuses to store secrets, the app says so instead of " +
            "silently not saving a password — and a channel whose key can't be cached " +
            "stays in your chat list.",
        "Node names ending in an emoji are no longer cut in half when written to the radio.",
    ),
    "0.7.15" to listOf(
        "The QR scanner can read inverted codes — for the first time. Apps in dark mode " +
            "render white codes on near-black, and this app could not see them at all. " +
            "0.7.11 claimed to fix this and did not: the hints were set on a decoder the " +
            "scanning library replaced a moment later, so they never reached a frame.",
        "Channel QR codes from other MeshCore apps work. The channel key is called " +
            "\"secret\"; this app asked for \"channel_secret\", a name it invented and " +
            "also emitted, so its codes were readable only by itself. Older codes from " +
            "this app still scan.",
        "Spaces in a scanned name stay spaces — a channel shared as West+Michigan+GMRS " +
            "arrived with the plus signs in its name.",
        "Paste a code, under Nodes → ⋮. Other clients share contacts by copying a " +
            "meshcore:// link rather than showing a QR, and there was no way to give one " +
            "to this app.",
        "Joining a channel you are already in no longer adds a second copy. It matches on " +
            "the key, not the name, because the key is what a channel is.",
    ),
    "0.7.14" to listOf(
        "Contact QR codes from other MeshCore clients import. The meshcore://<hex> form " +
            "— a shared raw advert — was rejected every time with \"Import failed (bad " +
            "signature?)\". A radio exports a whole packet, and this app checked the " +
            "signature as though it were the advert alone, reading the packet header as " +
            "the first byte of the public key.",
        "That code had never imported: nothing this app emits takes that form, so the " +
            "only codes reaching the path came from elsewhere.",
        "Spaces in a scanned name are no longer turned into \"+\". A contact shared as " +
            "name=Example+Contact — the encoding in MeshCore's own QR documentation — " +
            "arrived called \"Example+Contact\".",
    ),
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
