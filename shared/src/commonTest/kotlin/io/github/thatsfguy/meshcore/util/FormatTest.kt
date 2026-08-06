package io.github.thatsfguy.meshcore.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The multiplatform replacement for `"%.1f".format(x)`.
 *
 * Pinned against the values the app actually renders, because the point
 * of this helper is that moving a screen into `shared` must not change
 * a single character of what the user sees.
 */
class FormatTest {

    @Test
    fun `the readings the app actually shows are unchanged`() {
        // 910.525 MHz is the author's radio; 9.0 dB came off a live
        // repeat measurement. Both were rendered by String.format before.
        assertEquals("910.525", fixed(910.525, 3))
        assertEquals("9.0", fixed(9.0, 1))
        assertEquals("7.5", fixed(7.5, 1))
        assertEquals("11.5", fixed(11.5, 1))
    }

    @Test
    fun `it rounds half-up, the way String_format did`() {
        // 1.25 is the case that separates the two: HALF_UP gives 1.3,
        // ties-to-even (kotlin.math.round) gives 1.2. The JVM's
        // String.format used HALF_UP, so this must too or the move
        // changes what the user reads.
        assertEquals("1.3", fixed(1.25, 1))
        assertEquals("1.2", fixed(1.24, 1))
        assertEquals("2.0", fixed(1.96, 1))
        assertEquals("1.000", fixed(0.9999, 3))
    }

    @Test
    fun `zero places gives no point`() {
        assertEquals("43", fixed(42.6, 0))
        assertEquals("0", fixed(0.4, 0))
    }

    @Test
    fun `fractions are zero-padded to the requested width`() {
        assertEquals("5.10", fixed(5.1, 2))
        assertEquals("5.001", fixed(5.001, 3))
        assertEquals("0.0", fixed(0.0, 1))
    }

    @Test
    fun `negatives keep their sign`() {
        assertEquals("-9.5", fixed(-9.5, 1))
        assertEquals("-0.5", fixed(-0.5, 1))
    }

    @Test
    fun `a value that rounds away to zero loses its sign`() {
        // "-0.0 dB" reads as a negative measurement; it isn't one.
        assertEquals("0.0", fixed(-0.04, 1))
        assertEquals("0", fixed(-0.4, 0))
    }

    @Test
    fun `non-finite input degrades instead of throwing`() {
        assertEquals("NaN", fixed(Double.NaN, 1))
        assertEquals("∞", fixed(Double.POSITIVE_INFINITY, 1))
        assertEquals("-∞", fixed(Double.NEGATIVE_INFINITY, 1))
    }

    @Test
    fun `hex padding matches the percent-zero-N-x it replaces`() {
        assertEquals("0a", hexPadded(0x0a, 2))
        assertEquals("b3", hexPadded(0xb3, 2))
        assertEquals("f0b3", hexPadded(0xf0b3, 4))
        assertEquals("0000", hexPadded(0, 4))
    }
}
