package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct-message retry routing.
 *
 * The values here are pinned to MeshCore's own FAQ
 * (`meshcore-dev/MeshCore/docs/faq.md`), not to any client:
 *
 * > "…the message will fail after 3 retries, and the app will reset the
 * > path and send the message as flood on the last retry by default.
 * > This can be turned off in settings."
 *
 * That matters because the previous read of this behaviour came from a
 * single third-party client which uses five attempts and ships the
 * fallback disabled — neither of which is the documented default
 * (LESSONS §5, REBUILD-PLAYBOOK §5.1: read the authority, not one
 * implementation).
 */
class SendRetryTest {

    // --- the documented shape --------------------------------------------

    @Test
    fun `the default attempt count is the documented three`() {
        assertEquals(3, SendRetry.DEFAULT_MAX_ATTEMPTS)
    }

    @Test
    fun `the last attempt floods and every earlier one does not`() {
        // The positive control: this whole feature is worthless if the
        // flood never happens, and every "asserts StoredPath" case below
        // would pass with routeFor() hardcoded to StoredPath.
        assertEquals(
            SendRetry.Route.ResetAndFlood,
            SendRetry.routeFor(attempt = 2, maxAttempts = 3, hasStoredPath = true),
        )
        assertEquals(
            SendRetry.Route.StoredPath,
            SendRetry.routeFor(attempt = 0, maxAttempts = 3, hasStoredPath = true),
        )
        assertEquals(
            SendRetry.Route.StoredPath,
            SendRetry.routeFor(attempt = 1, maxAttempts = 3, hasStoredPath = true),
        )
    }

    @Test
    fun `exactly one flood is ever scheduled at any attempt count`() {
        // A client flood is the most expensive packet on the mesh and
        // MeshCore clients never repeat, so "one" is the whole budget.
        for (max in 1..10) {
            val floods = (0 until max).count {
                SendRetry.routeFor(it, max, hasStoredPath = true) == SendRetry.Route.ResetAndFlood
            }
            assertTrue(floods <= 1, "$max attempts scheduled $floods floods")
        }
    }

    // --- when there is nothing to fall back FROM --------------------------

    @Test
    fun `no stored path means no fallback`() {
        // The radio is already flooding. Resetting a path that does not
        // exist and calling it a fallback spends a round trip to reach
        // the state we are in.
        for (attempt in 0 until 3) {
            assertEquals(
                SendRetry.Route.StoredPath,
                SendRetry.routeFor(attempt, maxAttempts = 3, hasStoredPath = false),
            )
        }
    }

    @Test
    fun `a single-attempt send never floods`() {
        // Otherwise the one and only attempt would throw away a working
        // path before it had been shown to be broken.
        assertEquals(
            SendRetry.Route.StoredPath,
            SendRetry.routeFor(attempt = 0, maxAttempts = 1, hasStoredPath = true),
        )
    }

    @Test
    fun `degenerate attempt counts do not flood`() {
        for (max in -1..0) {
            assertEquals(
                SendRetry.Route.StoredPath,
                SendRetry.routeFor(attempt = 0, maxAttempts = max, hasStoredPath = true),
            )
        }
    }

    // --- the setting the FAQ says exists ----------------------------------

    @Test
    fun `disabling the fallback keeps every attempt on the stored path`() {
        for (attempt in 0 until 3) {
            assertEquals(
                SendRetry.Route.StoredPath,
                SendRetry.routeFor(
                    attempt = attempt,
                    maxAttempts = 3,
                    hasStoredPath = true,
                    floodFallbackEnabled = false,
                ),
            )
        }
    }

    // --- path scoring ------------------------------------------------------

    @Test
    fun `a flood attempt is not evidence about the path it replaced`() {
        // The path is cleared before the flood goes out, so crediting or
        // blaming it would record an outcome for a route that did not
        // carry the message.
        assertTrue(SendRetry.scoresStoredPath(SendRetry.Route.StoredPath))
        assertFalse(SendRetry.scoresStoredPath(SendRetry.Route.ResetAndFlood))
    }

    @Test
    fun `a three-attempt send scores the path at most twice`() {
        val scored = (0 until 3).count {
            SendRetry.scoresStoredPath(SendRetry.routeFor(it, 3, hasStoredPath = true))
        }
        assertEquals(2, scored)
    }
}
