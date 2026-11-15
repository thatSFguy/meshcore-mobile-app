package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.ChannelKind
import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * Simple mode: install, connect a node, talk to your group.
 *
 * Asked for so the app can be handed to people who are not radio
 * enthusiasts — including first responders working in a closed group.
 * They talk on the group's channel, and now and then two of them set up a
 * direct conversation (DmRequest). Everything about repeaters, routes,
 * radio parameters and firmware is somebody else's job.
 *
 * Simple mode HIDES; it never changes anything. Contacts, channels,
 * messages and every radio setting stay exactly as they are, and switching
 * back shows all of it again. That is also why switching back needs no
 * PIN: someone stuck in a mode they cannot leave files an issue, and there
 * is nothing behind the switch that Simple mode protects.
 *
 * Every rule is here rather than in the screens, so it is tested, and so
 * the screens stay one version each — Simple is a subset of the full app,
 * not a copy of it.
 */
enum class AppMode {
    Full,
    Simple,
    ;

    companion object {
        /** Stored as the name; anything unrecognised is the full app. */
        fun of(stored: String?): AppMode = entries.firstOrNull { it.name == stored } ?: Full
    }
}

object AppModes {

    /** Bottom-bar tabs, by route. */
    fun tabs(mode: AppMode): List<String> = when (mode) {
        AppMode.Full -> listOf("chats", "nodes", "map", "settings")
        AppMode.Simple -> listOf("chats", "settings")
    }

    /**
     * Settings hub tiles (SettingsHubModel routes) Simple mode keeps:
     * getting connected, your name, your channels, keeping noise out,
     * notifications, backup, and what the app is.
     */
    private val SIMPLE_SETTINGS = setOf(
        "connection", "identity", "channels", "blocking", "app", "backup", "about",
    )

    fun showsSettingsTile(mode: AppMode, route: String): Boolean =
        mode == AppMode.Full || route in SIMPLE_SETTINGS

    /**
     * Whether a conversation appears in Chats.
     *
     * Simple mode leaves out the Public channel — every radio has it, and
     * one wrong tap posts a responder's message to the whole mesh — and
     * any direct thread that is not with a person: a repeater's console or
     * a room, which belong to the full app. A thread whose contact is not
     * known yet ([contactType] null) is shown: hiding a message from
     * someone because we cannot classify them is the wrong way round.
     */
    fun showsThread(mode: AppMode, isChannel: Boolean, channelKind: ChannelKind?, contactType: Int?): Boolean {
        if (mode == AppMode.Full) return true
        return if (isChannel) {
            channelKind != ChannelKind.Public
        } else {
            contactType == null || contactType == Codes.ADV_TYPE_CHAT
        }
    }

    /** Hop count, SNR and repeat badges under each bubble. */
    fun showsSignalDetail(mode: AppMode): Boolean = mode == AppMode.Full

    /**
     * The conversation ⋮ menu and message long-press entries that lead into
     * routing, paths and node detail. Mute, mark unread, clear, reply,
     * react, copy and delete stay in both modes.
     */
    fun showsTechnicalActions(mode: AppMode): Boolean = mode == AppMode.Full
}
