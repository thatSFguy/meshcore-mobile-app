package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring around the stale-node sweep.
 *
 * `StaleNodesTest` pins WHICH nodes go. This pins the parts a pure
 * function cannot see, and they are the dangerous ones: that the delete
 * loop asks the radio rather than only the local cache, that it takes
 * its list from the same rule the dialog previewed, and that the dialog
 * shows the list before the button can be pressed.
 */
class StaleNodesWiringTest {

    private fun read(path: String) = File(path).readText()

    private val viewModel =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/MeshCoreViewModel.kt")
    private val dialog =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/StaleNodesDialog.kt")
    private val nodes =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/NodesScreen.kt")

    private val sweepFun = viewModel.substringAfter("suspend fun removeStaleNodes(")
        .substringBefore("\n    fun ")

    @Test
    fun `the sweep removes from the radio, not just the local cache`() {
        // The radio owns the contact list. Deleting our rows alone would
        // look like it worked and be undone by the next contact sync —
        // the kind of failure that teaches a user not to trust a button.
        assertTrue(
            "the sweep never asks the radio to remove anything",
            sweepFun.contains("svc.engine.removeContact(key)"),
        )
        assertTrue(
            "the local row is dropped without the radio having agreed",
            sweepFun.indexOf("svc.engine.removeContact(key)") <
                sweepFun.indexOf("db.contacts().delete(self, node.keyHex)"),
        )
    }

    @Test
    fun `it sweeps the list the shared rule chose`() {
        // Not a filter written a second time here: favourites are safe
        // by a rule with tests, and the dialog previews that same call.
        assertTrue(
            "the ViewModel picks its own victims",
            sweepFun.contains("StaleNodes.sweep("),
        )
        assertTrue(
            "the dialog previews something other than what will run",
            dialog.contains("StaleNodes.sweep(contacts, days, System.currentTimeMillis())"),
        )
        assertTrue(
            "the favourite check is duplicated in the ViewModel",
            !sweepFun.contains("CONTACT_FLAG_FAVORITE"),
        )
    }

    @Test
    fun `a refusal is counted rather than swallowed`() {
        assertTrue(
            "a failed removal is reported as a success",
            sweepFun.contains("failed++") && sweepFun.contains("StaleNodes.outcome(removed, failed)"),
        )
    }

    @Test
    fun `a removed node takes its neighbour readings with it`() {
        assertTrue(
            "stale removal leaves lines drawn from a node with no pin",
            sweepFun.contains("db.neighbours().clear(self, node.keyHex)"),
        )
    }

    @Test
    fun `the dialog shows the list before it can be run`() {
        // A count alone asks the user to trust a rule they cannot see,
        // and the question they are answering is "is there anything in
        // there I want to keep?".
        assertTrue(
            "the dialog never lists what it is about to remove",
            dialog.contains("for (node in sweep.remove.take(PREVIEW_ROWS))"),
        )
        assertTrue(
            "a long list is shown without saying how much is hidden",
            dialog.contains("…and \${sweep.count - PREVIEW_ROWS} more"),
        )
    }

    @Test
    fun `the button cannot run an empty or in-flight sweep`() {
        assertTrue(
            "the confirm button is live with nothing to remove",
            dialog.contains("enabled = sweep.count > 0 && !busy"),
        )
    }

    @Test
    fun `the slider offers only the documented range`() {
        assertTrue(
            "the slider range is hardcoded instead of taken from the model",
            dialog.contains("StaleNodes.MIN_DAYS.toFloat()..StaleNodes.MAX_DAYS.toFloat()"),
        )
    }

    @Test
    fun `it is reachable from the node list`() {
        assertTrue(
            "no way in from the Nodes tab",
            nodes.contains("MenuAction(\"Remove stale nodes…\") { staleOpen = true }") &&
                nodes.contains("StaleNodesDialog(vm, onDismiss = { staleOpen = false })"),
        )
    }
}
