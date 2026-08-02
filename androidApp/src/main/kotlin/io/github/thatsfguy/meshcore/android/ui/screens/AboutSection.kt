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
