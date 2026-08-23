package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboard behaviour for a field whose text is sent to a node exactly as
 * typed.
 *
 * Android's default is autocorrect on and the first letter capitalised,
 * which is right for a message and wrong for everything on the admin
 * side. A CLI line is not prose: `set prv.key <128 hex>` is a string the
 * keyboard is entitled to "fix", `advert` can be capitalised into a word
 * the firmware does not match, and a region name is a token compared
 * byte for byte. The failure is the bad kind — the field looks correct
 * at a glance, the node replies with an error that names nothing, and
 * the obvious suspect is the app.
 *
 * Spelled once, for the same reason [io.github.thatsfguy.meshcore.protocol.CliIds]
 * is: two fields that must behave identically, set by hand in two
 * places, drift.
 */
val VERBATIM_KEYBOARD = KeyboardOptions(
    // Ascii asks for the plain keyboard rather than a locale one; the
    // ids, values and region names are all ASCII on the wire.
    keyboardType = KeyboardType.Ascii,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)
