package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.ChannelSender
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Names seen on a channel (PARITY.md §4, `ChannelParticipantsScreen`).
 *
 * PARITY's own caveat for this row: channel "participants" can only
 * ever be *names seen*, and this ships as that or not at all. A channel
 * message carries no sender key (MESHCORE_PROTOCOL §9/§10) — anyone
 * holding the PSK can type any name, including yours. So:
 *
 *  - the title says "Names seen", never "Members" or "Participants";
 *  - the counts are appearances, not people;
 *  - nothing here can be tapped to message someone, because there is no
 *    identity behind the name to message.
 *
 * It is genuinely useful for spotting who is active and for finding a
 * name to hide — and useless, dangerously so, as a membership list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSendersSheet(vm: MeshCoreViewModel, channelIndex: Int, onDismiss: () -> Unit) {
    var senders by remember(channelIndex) { mutableStateOf<List<ChannelSender>?>(null) }
    LaunchedEffect(channelIndex) { senders = vm.channelSenders(channelIndex) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Names seen", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            // The impersonation point is the whole reason this sheet is
            // called "Names seen" and not "Members", so it stays in the
            // one line rather than behind the tap.
            Text(
                "Anyone with the channel key can post under any name, including one you " +
                    "recognise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            ExpandableHint("These are names, not identities.") {
                Text(
                    "A channel message carries a name, not a key, so nothing here can be " +
                        "verified. The list is built from the messages this phone kept — " +
                        "it is not a membership list, and it cannot tell you who anyone is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))

            val list = senders
            when {
                list == null -> SectionSpinner("Reading history…")
                list.isEmpty() -> HintText(
                    "No inbound messages kept for this channel yet. Retention settings " +
                        "affect what's here.",
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(list, key = { it.name }) { sender ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(sender.name, style = MaterialTheme.typography.bodyLarge)
                                HintText(
                                    "${sender.messageCount} message(s) · last " +
                                        DateFormat.getDateTimeInstance(
                                            DateFormat.SHORT, DateFormat.SHORT,
                                        ).format(Date(sender.lastSeenAt)),
                                )
                            }
                            // The only action that makes sense on a name:
                            // hide it. Messaging "them" is not possible —
                            // there is no identity behind it.
                            TextButton(onClick = {
                                vm.setChannelNameFiltered(sender.name, true)
                            }) { Text("Hide") }
                        }
                    }
                }
            }
        }
    }
}
