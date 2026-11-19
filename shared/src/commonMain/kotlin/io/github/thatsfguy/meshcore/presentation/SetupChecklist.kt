package io.github.thatsfguy.meshcore.presentation

/**
 * First-run setup (PARITY.md §1, `SetupScreen`), as a pure model so
 * Android and iOS show the same steps in the same words (2026-11-18).
 *
 * A checklist, not a wizard: each step says whether it is done and the
 * screen offers the one action that does it. Plenty of users arrive
 * with a radio someone else configured and should be able to skip
 * straight past.
 */
object SetupChecklist {

    /** [key] picks the step's action on each platform. */
    data class Step(val key: String, val number: Int, val title: String, val done: Boolean, val detail: String)

    const val CONNECT = "connect"
    const val NAME = "name"
    const val RADIO = "radio"
    const val GROUP = "group"
    const val PROTECTS = "protects"

    const val TITLE = "Set up MeshCore Hardened"
    const val INTRO = "This app talks to a MeshCore LoRa radio over Bluetooth or USB. Without one " +
        "there is nothing to send messages with — there is no internet fallback, by design."
    const val MODE_QUESTION = "How will you use it?"
    const val MODE_SIMPLE = "Simple: chats and channels with my group"
    const val MODE_FULL = "Full: nodes, map, repeaters and radio settings"
    const val MODE_NOTE = "You can switch at any time in Settings. Nothing is lost either way."
    const val NEEDS_RADIO = "Connect a radio first."

    /** A group's own channel — what "Join your group's channel" waits for. */
    fun isGroupChannel(kind: io.github.thatsfguy.meshcore.protocol.ChannelKind): Boolean =
        kind == io.github.thatsfguy.meshcore.protocol.ChannelKind.Private

    fun finishLabel(connected: Boolean): String = if (connected) "Done" else "Skip for now"

    fun steps(
        connected: Boolean,
        connectionLabel: String?,
        nodeName: String?,
        /** Names of the regional presets the radio's four values match. */
        matchingPresets: List<String>,
        simple: Boolean,
        /** Whether the radio holds a private (group) channel. */
        inAGroup: Boolean,
    ): List<Step> = buildList {
        add(Step(CONNECT, 1, "Connect a radio", connected,
            if (connected) "Connected to ${connectionLabel?.takeIf { it.isNotBlank() } ?: "a radio"}."
            else "Pair over Bluetooth, or plug one in over USB."))
        add(Step(NAME, 2, "Name this node", connected && !nodeName.isNullOrBlank(),
            "Your name is broadcast in every advert — the whole mesh sees it. " +
                "Pick something you're happy being known by out there."))
        add(Step(RADIO, 3, "Match your local radio settings", connected && matchingPresets.isNotEmpty(),
            if (connected && matchingPresets.isNotEmpty()) "Currently on ${matchingPresets.joinToString(", ")}."
            else "Every node on a mesh must share the same frequency, bandwidth, spreading factor " +
                "and coding rate. Mismatched, you'll see an empty app rather than an error."))
        if (simple) {
            add(Step(GROUP, 4, "Join your group's channel", inAGroup,
                "Your group talks on its own channel. Scan the channel code someone in the group " +
                    "shows you, or tap the link they sent."))
        }
        add(Step(PROTECTS, if (simple) 5 else 4, "Know what this app protects", false,
            "Direct messages are end-to-end encrypted. Channels are only obfuscated — AES-ECB " +
                "with a 2-byte MAC and a key that never changes — so treat a channel as a room " +
                "with an unlocked door, not a sealed one."))
    }
}
