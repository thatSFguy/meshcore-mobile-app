package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.RadioDefaultScope

/**
 * One line for the radio's saved default scope, or null while it has not
 * been read. See [RadioDefaultScope] for why the app has to show it: with
 * no scope set in the app, this is what the radio tags floods with.
 */
fun radioDefaultScopeLine(default: RadioDefaultScope): String? = when (default) {
    RadioDefaultScope.NotRead -> null
    RadioDefaultScope.Unsupported ->
        "This radio's firmware doesn't report a saved default scope."
    RadioDefaultScope.None -> "The radio has no saved default scope."
    is RadioDefaultScope.Set -> if (default.isPublicRegion) {
        "The radio's saved default scope is region ${default.name}."
    } else {
        "The radio's saved default scope is \"${default.name}\", with a key that isn't that " +
            "name's public one — repeaters that only know the name won't match it."
    }
}
