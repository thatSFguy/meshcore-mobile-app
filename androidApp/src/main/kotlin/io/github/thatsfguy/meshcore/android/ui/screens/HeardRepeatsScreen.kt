package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.protocol.HeardRepeats

/**
 * "Who repeats me" — the repeaters carrying this node's traffic
 * (PARITY.md §2, `HeardRepeatsScreen`).
 *
 * The mirror of the message sheet's "Arrived via". That one is passive
 * and backward-looking; this one you can *run*: send a flood advert and
 * watch which repeaters send a copy back. Every row is a signed copy of
 * our own advert, so no one else can put a repeater on this list.
 *
 * Two things the screen has to keep straight, because getting them
 * confused would make it confidently wrong:
 *
 *  - **Heard you ≠ you heard it.** A path's first hop picked our
 *    transmission out of the air; its last hop is the one whose
 *    transmission we demodulated. Only the second has a measured SNR.
 *  - **This is a floor, not coverage.** A repeater that carried our
 *    traffic onward without a copy returning cannot appear at all, and
 *    the caveat says so rather than letting the list imply a map.
 */
@Composable
fun HeardRepeatsScreen(vm: MeshCoreViewModel, nav: NavController) {
    val echoes by vm.heardRepeats.collectAsState()
    val contacts by vm.dbContacts.collectAsState()
    val engineState by vm.engineState.collectAsState()
    val connected = engineState == EngineState.Ready

    val names = contacts.associate { it.keyHex.lowercase() to it.name }
    val rows = repeatRows(echoes, names, vm.engineNowMillis())
    val relays = HeardRepeats.tally(echoes)

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Who repeats me",
                vm = vm,
                nav = nav,
                menuActions = listOf(
                    MenuAction("Send flood advert") { vm.sendSelfAdvert(flood = true) },
                    MenuAction("Clear", destructive = true) { vm.clearHeardRepeats() },
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                HeardRepeats.summary(echoes, relays),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            // The measurement is a deliberate act, so the button that
            // takes it is on the screen and not only in the menu.
            Button(
                onClick = { vm.sendSelfAdvert(flood = true) },
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send a flood advert") }
            Spacer(Modifier.height(4.dp))
            Text(
                if (connected) {
                    "Repeaters that relay it will appear below as their copies reach this " +
                        "radio. Give it a minute — an advert has to travel out and come back."
                } else {
                    // An enabled button that cannot work reads as a broken
                    // feature rather than a missing radio.
                    "Connect a radio first — this measures what the mesh does with your " +
                        "transmissions, so there has to be one."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.hashHex }) { row -> RepeatCard(row) }
                // The caveat qualifies the LIST, so it appears once
                // there is a list. Shown against an empty screen it was
                // a second paragraph of hedging before the reader had
                // seen a single row — LESSONS §14, caveat creep.
                if (rows.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            HeardRepeats.CAVEAT,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RepeatCard(row: RepeatRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = if (row.isAmbiguous) FontFamily.Monospace else null,
                )
                if (row.isTwoWay) {
                    // A label, not a chip. An AssistChip here looked
                    // tappable and did nothing — the affordance defect
                    // the first hardware session found six of.
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            "Two-way",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                row.direction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("Carried ${row.relayed} of your packet(s)")
                    row.snrText?.let { append(" · $it") }
                    append(" · ${row.ageText}")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
