package io.github.thatsfguy.meshcore.android.ui.screens

/**
 * Routes that more than one caller has to spell the same way.
 *
 * The conversation route is written in four places — the chat list, the
 * node list, the NavHost that declares it, and the notification tap
 * that deep-links into it. Four hand-typed copies of
 * `"conversation/$kind/$peer"` is a drift waiting to happen, and the
 * failure is silent: a route with no destination is a tap that does
 * nothing at all.
 */

/** The pattern the NavHost declares. */
const val CONVERSATION_ROUTE: String = "conversation/{kind}/{peer}"

/**
 * A conversation route for [kind] (`dm` / `ch`) and [peerKey] (a contact
 * public key, or a channel index).
 */
fun conversationRoute(kind: String, peerKey: String): String = "conversation/$kind/$peerKey"

/** "Who repeats me" — declared by the NavHost, navigated to from Nodes. */
const val HEARD_REPEATS_ROUTE: String = "repeats"
