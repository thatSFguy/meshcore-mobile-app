package io.github.thatsfguy.meshcore.presentation

/**
 * A room server is one thing: you sign in, then you read and post.
 *
 * The firmware makes the sign-in load-bearing, not a formality. A post
 * is decrypted only for a sender already in the room's ACL
 * (`simple_room_server/MyMesh.cpp:413`, `searchPeersByHash` walks the
 * ACL), and a client is put there only by a login (`:358`). New posts
 * are pushed only to clients in that table (`:1009-1021`). So a room
 * conversation opened without signing in looks like a working chat and
 * is not one: nothing sent is accepted, nothing new arrives.
 *
 * The app's session is held in memory and the firmware has no logout,
 * so [AdminSession.None] means "this app has not signed in since it
 * started", not "the room does not know you". That is why the notice
 * offers a sign-in rather than blocking the composer.
 */
fun roomAccessNotice(session: AdminSession): String? = when (session) {
    AdminSession.None ->
        "Not signed in. A room only accepts posts from, and sends new posts to, people who have signed in."
    AdminSession.Guest ->
        "Signed in read-only. You'll receive this room's posts, but it won't accept yours."
    AdminSession.Member, AdminSession.Admin -> null
}

/** The notice's button: signing in is the fix for both states it shows. */
fun roomAccessAction(session: AdminSession): String? = when (session) {
    AdminSession.None -> "Sign in"
    AdminSession.Guest -> "Sign in again"
    AdminSession.Member, AdminSession.Admin -> null
}

/**
 * The room password stock room-server builds ship with. Every room
 * environment in the firmware's variants sets it — e.g.
 * `variants/heltec_v3/platformio.ini:115`, `-D ROOM_PASSWORD='"hello"'`
 * — and most rooms on the air never change it, because it is the one
 * their members are meant to share.
 */
const val ROOM_DEFAULT_PASSWORD = "hello"

/**
 * One line for the room sign-in dialog. A default, not a fact about
 * this room: the owner may have changed it, and the node's answer is
 * what counts.
 */
fun roomPasswordHint(): String =
    "Rooms often keep the default room password, \"$ROOM_DEFAULT_PASSWORD\", unless the owner changed it."
