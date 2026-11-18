package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.CliCatalog
import io.github.thatsfguy.meshcore.protocol.CliCommand
import io.github.thatsfguy.meshcore.protocol.CliKind
import io.github.thatsfguy.meshcore.protocol.NodeRole

/**
 * The command-help screen's rules, shared since iOS Phase 5 (2026-11-18)
 * — they lived in Android's CliHelpPanel. Categories come back as an
 * ordered list, not a map: a Kotlin map reaches Swift as an unordered
 * dictionary.
 */
object CliHelp {

    data class Category(val title: String, val commands: List<CliCommand>)

    /** The line you'd actually type. */
    fun usage(command: CliCommand): String = when (command.kind) {
        CliKind.GetOnly -> "get ${command.id}"
        CliKind.GetSet -> "set ${command.id} ${command.argHint ?: "<value>"}"
        CliKind.Action -> command.id
        CliKind.ActionWithArg -> "${command.id} ${command.argHint ?: "<value>"}"
    }

    fun roleWord(role: NodeRole): String = when (role) {
        NodeRole.Repeater -> "repeater"
        NodeRole.Room -> "room server"
        NodeRole.Sensor -> "sensor"
        NodeRole.Companion -> "node"
    }

    /**
     * The line above the catalogue, qualified by what the node granted:
     * a node takes CLI text from admins only, so a guest's share is none.
     */
    fun summary(total: Int, role: NodeRole, session: AdminSession): String {
        val head = "$total command${if (total == 1) "" else "s"} this ${roleWord(role)} accepts"
        return when (session) {
            AdminSession.Admin -> "$head."
            AdminSession.Guest -> "$head from an admin — none from a guest session."
            AdminSession.Member -> "$head from an admin — none from a member session."
            AdminSession.None -> "$head from an admin."
        }
    }

    /** Badges: what a command does that you'd want to know before typing it. */
    fun badges(command: CliCommand): List<String> = listOfNotNull(
        "destructive".takeIf { command.requiresConfirm },
        "secret".takeIf { command.sensitive },
        "changes node".takeIf { command.changesState },
    )

    /** The catalogue for [role], filtered by [query] (id, label or description); empty categories dropped. */
    fun categories(role: NodeRole, query: String): List<Category> {
        val q = query.trim().lowercase()
        return CliCatalog.forRoleByCategory(role).mapNotNull { (title, commands) ->
            val hits = if (q.isEmpty()) commands else commands.filter {
                q in it.id.lowercase() || q in it.label.lowercase() || q in it.description.lowercase()
            }
            hits.takeIf { it.isNotEmpty() }?.let { Category(title, it) }
        }
    }
}
