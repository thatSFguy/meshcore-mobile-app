package io.github.thatsfguy.meshcore.protocol

/**
 * How many bytes of a repeater's key go into each hop of a packet path
 * — the on-air path hash width, set as a *mode* where bytes = mode + 1.
 *
 * ## The range is 0–2, not 0–3
 *
 * Every layer of the firmware refuses mode 3, and this app offered it
 * anyway on a "4 B" chip that could only ever produce an error:
 *
 *  - companion binary handler — `if (cmd_frame[2] >= 3)
 *    writeErrFrame(ERR_CODE_ILLEGAL_ARG)`
 *    (`examples/companion_radio/MyMesh.cpp:1446`)
 *  - text CLI — `"Error, must be 0,1, or 2"` (`CommonCLI.cpp:664`)
 *  - on load — `constrain(path_hash_mode, 0, 2)  // NOTE: mode 3
 *    reserved for future` (`CommonCLI.cpp:108`)
 *
 * The reserved mode is the whole reason this is a named object rather
 * than a `0..3` written inline in three files: when the firmware does
 * take mode 3, exactly one constant moves and the chips, the hint, the
 * catalogue's arg hint and the validation all follow.
 *
 * ## Why it matters more than a setting usually does
 *
 * The width is a property of the MESH, not of a node — every node has
 * to agree or hop hashes stop matching. It is the recurring defect in
 * this codebase (CLAUDE.md), so treat a mismatch as the first suspect
 * when a route "looks right but does nothing".
 */
object PathHashMode {

    /** Lowest mode the firmware accepts. */
    const val MIN_MODE: Int = 0

    /** Highest mode the firmware accepts; 3 is reserved. */
    const val MAX_MODE: Int = 2

    /** First mode the firmware REFUSES, named so tests can say why. */
    const val RESERVED_MODE: Int = 3

    /** Modes in order; the index is the mode. */
    val MODES: List<Int> = (MIN_MODE..MAX_MODE).toList()

    /** Chip labels, in mode order: "1 B", "2 B", "3 B". */
    val LABELS: List<String> = MODES.map { "${bytesFor(it)} B" }

    /** Bytes per hop for a mode. */
    fun bytesFor(mode: Int): Int = mode + 1

    /**
     * The mode that produces [bytes] per hop, clamped into range.
     *
     * Clamping rather than failing is deliberate: this converts a width
     * the RADIO reported (DEVICE_INFO), and a radio running firmware
     * newer than this app could name a width we have no chip for. A
     * clamped selection is wrong by one; a crash or an out-of-range
     * index is worse.
     */
    fun modeFor(bytes: Int): Int = (bytes - 1).coerceIn(MIN_MODE, MAX_MODE)

    /** True when the firmware would accept this mode. */
    fun isValid(mode: Int): Boolean = mode in MIN_MODE..MAX_MODE

    /** "2 bytes per hop" / "1 byte per hop". */
    fun describeBytes(bytes: Int): String =
        "$bytes byte${if (bytes == 1) "" else "s"} per hop"
}
