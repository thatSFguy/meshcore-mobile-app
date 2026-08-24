package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.presentation.StaleNodes
import io.github.thatsfguy.meshcore.util.RelativeTime
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Clear out nodes nothing has been heard from — one slider, from
 * [StaleNodes.MIN_DAYS] to [StaleNodes.MAX_DAYS] (Meshtastic's shape;
 * the mainstream MeshCore app has no equivalent).
 *
 * A dialog rather than a settings row, because it is a one-off action
 * with a destructive result, and because the count has to move while
 * the slider does. Every caveat is spelled out here in full: this is a
 * modal opened to make an irreversible decision, which is exactly where
 * the one-line copy budget does NOT apply (REBUILD-PLAYBOOK §6.3, and
 * `CopyBudgetTest` exempts dialogs for this reason).
 *
 * The list is shown before the button is pressed. A count alone asks
 * the user to trust a rule they cannot see, and the whole question they
 * are answering is "is anything in there I want to keep?".
 */
@Composable
fun StaleNodesDialog(vm: MeshCoreViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val contacts by vm.dbContacts.collectAsState()
    var days by remember { mutableIntStateOf(StaleNodes.DEFAULT_DAYS) }
    var busy by remember { mutableStateOf(false) }

    // Recomputed on every slider move, against the same list the sweep
    // itself will read — the preview and the action cannot disagree
    // because they are the same function.
    val sweep = StaleNodes.sweep(contacts, days, System.currentTimeMillis())

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Remove stale nodes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Removes nodes from the radio's contact list when nothing has been heard " +
                        "from them — no advert and no message — for longer than this.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Favourites are never removed, whatever the slider says. Nor is a node " +
                        "that has never been heard from at all, which is what a contact just " +
                        "added from a QR code looks like.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    "Older than $days days",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = days.toFloat(),
                    onValueChange = { days = it.roundToInt() },
                    valueRange = StaleNodes.MIN_DAYS.toFloat()..StaleNodes.MAX_DAYS.toFloat(),
                    // No tick marks. Material draws one dot per step, and
                    // 26 of them read as a dotted line rather than as a
                    // scale — seen on the phone. The value is still whole
                    // days: the thumb slides, `roundToInt` lands it.
                    steps = 0,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "${sweep.count} of ${sweep.total} nodes",
                    style = MaterialTheme.typography.titleSmall,
                )
                StaleNodes.keptNote(sweep)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (sweep.remove.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    // Oldest first, so what is shown is what is least
                    // likely to be wanted.
                    for (node in sweep.remove.take(PREVIEW_ROWS)) {
                        val evidence = StaleNodes.lastEvidenceMillis(node)
                        Text(
                            node.name.ifBlank { node.keyHex.take(12) } + " · " +
                                RelativeTime.agoMillis(System.currentTimeMillis() - evidence),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (node.name.isBlank()) FontFamily.Monospace else null,
                        )
                    }
                    if (sweep.count > PREVIEW_ROWS) {
                        Text(
                            "…and ${sweep.count - PREVIEW_ROWS} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Conversations are kept. A thread with a removed node shows its key " +
                        "instead of its name, and the node comes back on its own if it " +
                        "advertises again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                enabled = sweep.count > 0 && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        // Each removal is a command to the radio, so this
                        // is seconds, not instant, on a long list.
                        vm.transientMessage.value = vm.removeStaleNodes(days)
                        busy = false
                        onDismiss()
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Removing…")
                } else {
                    Text(StaleNodes.actionLabel(sweep))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

/** Enough of the list to judge it by, without a dialog that scrolls forever. */
private const val PREVIEW_ROWS = 6
