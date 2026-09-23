package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine.AddOutcome

/**
 * What the New tab says after Add.
 *
 * Each failure gets its own wording. The old single message, "Import
 * failed (bad signature?)", was shown for every one of them, and it was
 * wrong every time: the signature was never the reason. A full radio
 * especially needs saying — the user can do something about it.
 */
object DiscoveredAdd {
    fun message(outcome: AddOutcome, name: String): String = when (outcome) {
        AddOutcome.Added -> "Added $name"
        AddOutcome.TableFull ->
            "The radio's contact list is full. Remove a node to make room for $name."
        AddOutcome.Unverified ->
            "Couldn't add $name: its stored advert doesn't verify. Wait to hear it again."
        AddOutcome.Failed -> "Couldn't add $name: the radio didn't answer."
    }
}
