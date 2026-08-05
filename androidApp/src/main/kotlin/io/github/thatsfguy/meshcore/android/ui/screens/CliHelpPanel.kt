package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.protocol.CliCatalog
import io.github.thatsfguy.meshcore.protocol.CliCommand
import io.github.thatsfguy.meshcore.protocol.CliKind
import io.github.thatsfguy.meshcore.protocol.NodeRole

/**
 * Reference for the commands this node actually accepts.
 *
 * Filtered by the node's role AND by the session's role: a guest is
 * never shown a command the node would refuse, which is the difference
 * between a reference and a list of things to be disappointed by.
 *
 * Tapping a command copies its usage into the console input rather than
 * running it — several of these are destructive, and a help screen that
 * executes on tap is a trap.
 */
@Composable
fun CliHelpPanel(
    role: NodeRole,
    session: AdminSession,
    onUse: (String) -> Unit,
) {
    val isAdmin = session.isAdmin
    var query by remember { mutableStateOf("") }
    val byCategory = remember(role, isAdmin) { CliCatalog.forRoleByCategory(role, isAdmin) }
    val filtered = remember(query, byCategory) {
        if (query.isBlank()) {
            byCategory
        } else {
            byCategory.mapValues { (_, commands) ->
                commands.filter {
                    it.id.contains(query, true) ||
                        it.label.contains(query, true) ||
                        it.description.contains(query, true)
                }
            }.filterValues { it.isNotEmpty() }
        }
    }
    val total = filtered.values.sumOf { it.size }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search commands") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        HintText(cliHelpSummary(total, role, session))
        LazyColumn(Modifier.fillMaxWidth()) {
            for ((category, commands) in filtered) {
                item(key = "h-$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(commands, key = { "${category}-${it.id}" }) { command ->
                    CommandRow(command) { onUse(usageOf(command)) }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: CliCommand, onUse: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onUse)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                usageOf(command),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (command.requiresConfirm) Badge("destructive", MaterialTheme.colorScheme.error)
            if (command.sensitive) Badge("secret", MaterialTheme.colorScheme.error)
            if (command.adminOnly) Badge("admin", MaterialTheme.colorScheme.primary)
        }
        Text(
            command.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        command.companionEquivalent?.let {
            // Worth stating: for these the app uses a binary frame, so the
            // CLI form is documentation rather than the path we take.
            Text(
                "app uses $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The line you'd actually type. */
internal fun usageOf(command: CliCommand): String = when (command.kind) {
    CliKind.GetOnly -> "get ${command.id}"
    CliKind.GetSet -> "set ${command.id} ${command.argHint ?: "<value>"}"
    CliKind.Action -> command.id
    CliKind.ActionWithArg -> "${command.id} ${command.argHint ?: "<value>"}"
}

private fun roleWord(role: NodeRole): String = when (role) {
    NodeRole.Repeater -> "repeater"
    NodeRole.Room -> "room server"
    NodeRole.Sensor -> "sensor"
    NodeRole.Companion -> "node"
}

/**
 * The one line above the catalogue, qualified by what the node granted.
 *
 * The old wording had two cases and read the second one off `isAdmin`,
 * so a session that had never logged in was described as a guest — a
 * claim about the node's answer made before the node had answered.
 * Command help is reachable without signing in, so that third case is
 * real and gets its own words.
 */
fun cliHelpSummary(total: Int, role: NodeRole, session: AdminSession): String {
    // "1 commands" — found by searching the catalogue on a real node.
    val head = "$total command${if (total == 1) "" else "s"} this ${roleWord(role)} accepts"
    return when (session) {
        AdminSession.Admin -> "$head."
        AdminSession.Guest -> "$head as a guest."
        AdminSession.None -> "$head without an admin sign-in."
    }
}
