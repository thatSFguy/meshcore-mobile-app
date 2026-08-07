package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The on-air path hash width, and the range the firmware actually takes.
 *
 * This exists because the app shipped a "4 B" chip for mode 3, which is
 * reserved: the companion handler answers ERR_CODE_ILLEGAL_ARG for
 * `>= 3` (`examples/companion_radio/MyMesh.cpp:1446`), the CLI answers
 * "Error, must be 0,1, or 2" (`CommonCLI.cpp:664`), and loadPrefs
 * clamps to 0..2 (`CommonCLI.cpp:108`). Tapping it did nothing and said
 * nothing. The catalogue's help text and arg hint claimed 0-3 too.
 *
 * So the tests that matter are the ones that fail if a fourth option
 * ever creeps back in — in the chips, in the validation, or in the
 * catalogue's own description of itself.
 */
class PathHashModeTest {

    @Test
    fun modeThreeIsReservedAndRejected() {
        // The whole defect, in one assertion.
        assertFalse(
            PathHashMode.isValid(PathHashMode.RESERVED_MODE),
            "mode 3 is reserved; the firmware refuses it at every layer",
        )
        assertEquals(3, PathHashMode.RESERVED_MODE)
        assertEquals(2, PathHashMode.MAX_MODE)
    }

    @Test
    fun thereAreExactlyThreeOptionsAndTheyAreOneToThreeBytes() {
        assertEquals(listOf(0, 1, 2), PathHashMode.MODES)
        assertEquals(listOf("1 B", "2 B", "3 B"), PathHashMode.LABELS)
        // The chip index IS the mode, which is what the UI relies on.
        PathHashMode.MODES.forEachIndexed { i, mode -> assertEquals(i, mode) }
    }

    @Test
    fun everyOfferedOptionIsOneTheFirmwareAccepts() {
        // The rule the "4 B" chip broke: never offer what would error.
        for (mode in PathHashMode.MODES) {
            assertTrue(PathHashMode.isValid(mode), "offered mode $mode is not valid")
        }
        assertEquals(PathHashMode.MODES.size, PathHashMode.LABELS.size)
    }

    @Test
    fun bytesAreModePlusOne() {
        assertEquals(1, PathHashMode.bytesFor(0))
        assertEquals(2, PathHashMode.bytesFor(1))
        assertEquals(3, PathHashMode.bytesFor(2))
    }

    @Test
    fun widthRoundTripsThroughMode() {
        for (mode in PathHashMode.MODES) {
            assertEquals(mode, PathHashMode.modeFor(PathHashMode.bytesFor(mode)))
        }
    }

    @Test
    fun anOutOfRangeWidthClampsRatherThanIndexingOffTheEnd() {
        // DEVICE_INFO comes off the radio, so a node on newer firmware
        // could report a width we have no chip for. Clamping keeps the
        // selection in range; an unclamped (bytes - 1) would index past
        // LABELS and crash the settings screen.
        assertEquals(0, PathHashMode.modeFor(0))
        assertEquals(0, PathHashMode.modeFor(-5))
        assertEquals(2, PathHashMode.modeFor(4))
        assertEquals(2, PathHashMode.modeFor(99))
        for (bytes in -5..99) {
            assertTrue(
                PathHashMode.modeFor(bytes) in PathHashMode.LABELS.indices,
                "width $bytes produced an index outside the chip list",
            )
        }
    }

    @Test
    fun describeBytesGetsThePluralRight() {
        assertEquals("1 byte per hop", PathHashMode.describeBytes(1))
        assertEquals("2 bytes per hop", PathHashMode.describeBytes(2))
        assertEquals("3 bytes per hop", PathHashMode.describeBytes(3))
    }

    @Test
    fun theCatalogueDoesNotAdvertiseTheReservedMode() {
        // The help panel and the arg hint are read by a person deciding
        // what to type into the console; they claimed 0-3 as well.
        val cmd = CliCatalog.all.first { it.id == CliIds.PATH_HASH_MODE }
        assertEquals("<0-2>", cmd.argHint)
        assertFalse(
            "0-3" in cmd.description || "0–3" in cmd.description,
            "the catalogue still offers the reserved mode: ${cmd.description}",
        )
    }

    @Test
    fun theCommandIsGettableAndSettableOnEveryRole() {
        // It is mesh-wide, so it has to be reachable on a repeater and a
        // room, not only on the radio in your hand.
        val cmd = CliCatalog.all.first { it.id == CliIds.PATH_HASH_MODE }
        assertEquals(CliKind.GetSet, cmd.kind)
        assertTrue(NodeRole.Repeater in cmd.roles)
        assertTrue(NodeRole.Room in cmd.roles)
        assertTrue(NodeRole.Companion in cmd.roles)
    }
}
