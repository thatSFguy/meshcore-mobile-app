package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ContactEntity
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.presentation.AdminSession
import io.github.thatsfguy.meshcore.presentation.NeighbourLink
import io.github.thatsfguy.meshcore.presentation.collectedLabel
import io.github.thatsfguy.meshcore.presentation.neighbourOffer
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlinx.coroutines.launch

/**
 * The map's node popup: what this pin is, and — for a repeater — the
 * links it hears, drawn on the map with their signal quality.
 *
 * Tapping a pin used to do nothing at all. Every marker already carries
 * its name, so an info bubble repeating it would have been a control
 * that exists to restate what is on screen; what the map was missing is
 * the one thing a map is for, which is showing how the nodes connect.
 *
 * The neighbour table behind those lines costs a login and a round trip
 * over the air, so it is READ ONCE and kept (`NeighbourRecord`). What is
 * on screen may therefore be hours old, and says so rather than
 * implying it is live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapNodeSheet(
    vm: MeshCoreViewModel,
    contact: ContactEntity,
    links: List<NeighbourLink>,
    linksShown: Boolean,
    onToggleLinks: () -> Unit,
    onOpenNode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val records = vm.neighbourRecords.collectAsState().value
        .filter { it.repeaterKeyHex == contact.keyHex }
    val connected = vm.engineState.collectAsState().value == EngineState.Ready
    val session = vm.adminSessions.collectAsState().value[contact.keyHex] ?: AdminSession.None

    var hasSaved by remember(contact.keyHex) { mutableStateOf(false) }
    LaunchedEffect(contact.keyHex) { hasSaved = vm.hasSavedLoginPassword(contact.keyHex) }

    var busy by remember(contact.keyHex) { mutableStateOf(false) }
    var note by remember(contact.keyHex) { mutableStateOf<String?>(null) }

    val offer = neighbourOffer(
        isRepeater = contact.type == Codes.ADV_TYPE_REPEATER,
        connected = connected,
        session = session,
        hasSavedPassword = hasSaved,
        storedCount = records.size,
        collected = collectedLabel(records, System.currentTimeMillis()),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                contact.name.ifBlank { "Unnamed node" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                typeLabel(contact.type).dropLast(1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (contact.lastSeen > 0) {
                Text(
                    "Last advert ${relativeAge(contact.lastSeen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (offer == null) {
                // Only repeater firmware keeps a neighbour table — there
                // is no 0x06 handler on a room server or a sensor at all
                // — so offering the control here could only ever produce
                // a timeout that blames the link.
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onOpenNode) { Text("Open this node") }
                return@Column
            }

            Spacer(Modifier.height(16.dp))
            Text("Neighbours", style = MaterialTheme.typography.titleSmall)
            offer.collected?.let {
                Text(
                    "${offer.storedCount} heard · $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ButtonFlowRow {
                if (offer.hasStored) {
                    TextButton(onClick = onToggleLinks) {
                        Text(if (linksShown) "Hide links" else "Show links on map")
                    }
                }
                TextButton(
                    enabled = offer.canFetch && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            note = vm.collectNeighbours(contact.keyHex).message
                            busy = false
                        }
                    },
                ) { Text(offer.fetchLabel) }
            }
            if (busy) SectionSpinner("Asking the node…")
            HintText(offer.fetchHint)
            note?.let { HintText(it) }

            if (links.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                for (link in links) NeighbourRow(link)
                val undrawable = links.count { !it.isDrawable }
                if (undrawable > 0) {
                    HintText("$undrawable of these cannot be placed on the map.")
                }
                Spacer(Modifier.height(4.dp))
                // The caveats belong behind the tap, once, at the point
                // they matter (REBUILD-PLAYBOOK §6.3).
                ExpandableHint("A line means this repeater heard that node's advert directly.") {
                    HintText(
                        "It says nothing about the other direction, and nothing about now — " +
                            "the node records a neighbour when a repeater's advert arrives at " +
                            "zero hops, and nothing expires it.",
                    )
                    HintText(
                        "Companions, rooms and sensors are never recorded, so a short list " +
                            "is the firmware being narrow, not a fault.",
                    )
                    HintText(
                        "The list is what this repeater says it hears, relayed by that " +
                            "repeater, and the signal figure is its reading at the time.",
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenNode) { Text("Administer this node") }
            if (offer.hasStored) {
                TextButton(onClick = { vm.forgetNeighbours(contact.keyHex) }) {
                    Text("Forget this reading")
                }
            }
        }
    }
}

/** One neighbour: the quality swatch that is drawn on the map, then the row. */
@Composable
private fun NeighbourRow(link: NeighbourLink) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = Color(link.quality.argb),
            shape = CircleShape,
            modifier = Modifier.size(10.dp),
        ) {}
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                link.label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                // Why a line is missing belongs on the row it is missing
                // from, not in a footnote: a prefix that matches two
                // nodes is a different problem from one with no fix.
                link.undrawable?.let { "${link.summary} · ${it.reason}" } ?: link.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
