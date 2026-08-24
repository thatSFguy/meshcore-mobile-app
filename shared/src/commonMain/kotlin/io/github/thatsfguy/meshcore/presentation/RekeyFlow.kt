package io.github.thatsfguy.meshcore.presentation

/**
 * What to tell the user after replacing a repeater's identity key.
 *
 * The sequence has four steps that can each half-succeed — the node
 * takes the key, the app writes the new identity locally, the reboot
 * goes out, the new identity answers — and the failure that matters is
 * not any one of them failing. It is the report claiming more than
 * happened: "rebooted" when nothing acknowledged the reboot (nothing
 * ever does — `IdentityKey.REBOOT_HAS_NO_ANSWER`), or "done" when the
 * node is still running its old key.
 *
 * So the wording is a pure function of what is actually known, pinned by
 * tests, rather than assembled from string concatenation at the call
 * site. iOS inherits it.
 */
object RekeyFlow {

    /** How the attempt to sign in to the NEW identity ended. */
    enum class Probe {
        /** Not tried — the user did not ask for a reboot, or it failed. */
        NOT_ATTEMPTED,

        /** No saved password, so there was nothing to sign in with. */
        NO_PASSWORD,

        /** The new identity answered and granted a session. It rebooted. */
        CONFIRMED,

        /** Nothing answered. Expected while it is still booting. */
        SILENT,

        /** Something answered and refused the password. */
        REJECTED,
    }

    /**
     * Everything the app learned, and nothing it assumed.
     *
     * [mismatchedWith] is the public key this phone computed for the key
     * it sent, when the node reported a different one. That is not a
     * cosmetic disagreement — one of the two is not the node we think we
     * are talking to — so it outranks every other line.
     */
    data class Report(
        val newPublicKeyHex: String? = null,
        val refusal: String? = null,
        val mismatchedWith: String? = null,
        val adopted: Boolean = false,
        val rebootRequested: Boolean = false,
        val rebootSent: Boolean = false,
        val probe: Probe = Probe.NOT_ATTEMPTED,
    )

    /** True when the node took the key and nothing contradicted it. */
    fun succeeded(r: Report): Boolean =
        r.newPublicKeyHex != null && r.refusal == null && r.mismatchedWith == null

    /**
     * The report, as lines for the panel. First line first: a refusal or
     * a mismatch is the whole story and nothing else is worth saying
     * underneath it.
     */
    fun describe(r: Report): List<String> {
        r.refusal?.let { return listOf(it) }

        val key = r.newPublicKeyHex ?: return listOf(
            "No reply. The change may or may not have applied — a node keeps its old key " +
                "until it reboots, so there is nothing to read back that would settle it.",
        )

        r.mismatchedWith?.let { expected ->
            return listOf(
                "Stop. The node reported $key, but the key sent should have produced " +
                    "$expected. Something answered that is not the node this key was " +
                    "generated for, or the command was altered on the way. Nothing has " +
                    "been changed in this app.",
            )
        }

        val lines = mutableListOf("The node accepted the key. Its new identity is $key.")

        if (r.adopted) {
            lines += "Added here as a new contact carrying the old one's name, its " +
                "favourite mark, its Bluetooth address and its saved password — so it " +
                "does not have to be waited for."
        } else {
            lines += "The new identity could NOT be written to this radio's contact " +
                "list, so it will only appear when the node next advertises."
        }

        when {
            !r.rebootRequested ->
                lines += "Not rebooted, so the node is still running its old key. It " +
                    "takes the new one on its next restart."

            !r.rebootSent ->
                lines += "The reboot was NOT sent — this radio would not transmit it. The " +
                    "node is still running its old key."

            else -> lines += when (r.probe) {
                Probe.CONFIRMED ->
                    "Rebooted: the new identity answered a sign-in, which is proof it is " +
                        "running and holds the new key."

                Probe.REJECTED ->
                    "Something answered as the new identity and refused the password. " +
                        "The node has rebooted; the password did not carry over, so sign " +
                        "in again by hand."

                Probe.NO_PASSWORD ->
                    "Reboot sent. There is no saved password for this node, so it could " +
                        "not be checked — sign in to the new entry to confirm."

                Probe.SILENT, Probe.NOT_ATTEMPTED ->
                    "Reboot sent, and not yet confirmed — nothing answered as the new " +
                        "identity. A node takes a while to come back, and a reboot is " +
                        "never acknowledged, so this is not yet a failure. Sign in to " +
                        "the new entry in a minute or two."
            }
        }

        if (r.adopted) {
            lines += "The old entry is left alone: until the node actually restarts, it " +
                "is still the live one. Delete it once the new identity is answering."
        }
        return lines
    }
}
