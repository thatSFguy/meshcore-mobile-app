package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.protocol.CliExchange.Shape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliExchangeTest {

    // Reply strings are the firmware's own, from CommonCLI.cpp and
    // RegionMap's handleRegionCmd on meshcore-dev/MeshCore main.

    @Test
    fun repliesAreRecognisedByTheShapesTheFirmwarePrints() {
        assertEquals(Shape.Value, CliExchange.shapeOf("> 3"))
        assertEquals(Shape.Value, CliExchange.shapeOf("> 910.5250244,250,10,5"))
        assertEquals(Shape.Version, CliExchange.shapeOf("v1.16.0-07a3ca9 (Build: 06-Jun-2026)"))
        assertEquals(Shape.DefaultScope, CliExchange.shapeOf(" default scope is <null>"))
        assertEquals(Shape.DefaultScope, CliExchange.shapeOf(" default scope is now mi-west"))
        assertEquals(Shape.Home, CliExchange.shapeOf(" home is *"))
        assertEquals(Shape.Home, CliExchange.shapeOf(" home is now kent"))
        assertEquals(Shape.Ok, CliExchange.shapeOf("OK"))
        assertEquals(Shape.Ok, CliExchange.shapeOf("OK - reboot to apply"))
        assertEquals(Shape.Ok, CliExchange.shapeOf("OK - (flood allowed)"))
        assertEquals(Shape.Ok, CliExchange.shapeOf("ok"))
    }

    @Test
    fun everyWayTheFirmwareSaysNoIsARefusal() {
        for (no in listOf(
            "Err - unknown region", "Err - save failed", "Error, max 64", "Error: unsupported",
            "ERR: bad pubkey", "(ERR: clock cannot go backwards)", "??: region",
            "unknown config: flood.max.unscoped", "Unknown command",
        )) {
            assertEquals(Shape.Refusal, CliExchange.shapeOf(no), no)
        }
    }

    @Test
    fun aRegionTreeIsNotMistakenForAnyOtherShape() {
        assertEquals(Shape.Other, CliExchange.shapeOf("* F\n mi F\n  kent ^ F"))
    }

    @Test
    fun aVersionIsNotTheAnswerToAGet() {
        // The Regions-screen failure: a late `ver` taken as the reply to
        // `get flood.max.unscoped`, read as "no limit", shown as "Relay all".
        assertFalse(
            CliExchange.accepts(
                "get flood.max.unscoped",
                "v1.16.0-07a3ca9 (Build: 06-Jun-2026)",
            ),
        )
        assertTrue(CliExchange.accepts("get flood.max.unscoped", "> 3"))
    }

    @Test
    fun eachCommandRefusesTheOtherCommandsShapes() {
        val tree = "* F\n mi F"
        val cases = mapOf(
            "get flood.max.unscoped" to "> 3",
            "ver" to "v1.17.1 (Build: 01-Sep-2026)",
            "region default" to " default scope is mi",
            "region home" to " home is *",
            "set flood.max.unscoped 3" to "OK",
            "region allowf *" to "OK",
            "region save" to "OK",
            "region" to tree,
        )
        for ((command, own) in cases) {
            assertTrue(CliExchange.accepts(command, own), "$command should take `$own`")
            for ((other, reply) in cases) {
                if (CliExchange.shapeOf(reply) == CliExchange.shapeOf(own)) continue
                // A tree is unrecognisable text, which anything may take.
                if (reply == tree) continue
                assertFalse(CliExchange.accepts(command, reply), "$command must skip $other's `$reply`")
            }
        }
    }

    @Test
    fun aRefusalCanAnswerAnything() {
        for (command in listOf("get flood.max.unscoped", "ver", "region", "region default", "set x 1")) {
            assertTrue(CliExchange.accepts(command, "??: $command"))
        }
    }

    @Test
    fun anUnknownCommandIsMatchedByPositionAlone() {
        assertTrue(CliExchange.accepts("clock", "> 3"))
        assertTrue(CliExchange.accepts("neighbors", "v1.16.0 (Build: x)"))
    }

    @Test
    fun onlyReadsAreResent() {
        for (read in listOf(
            "get flood.max.unscoped", "ver", "board", "region", "region default", "region home",
            "region list allowed", "region get mi",
        )) assertTrue(CliExchange.isRead(read), read)
        for (write in listOf(
            "set flood.max.unscoped 3", "region default mi", "region home mi", "region save",
            "region allowf *", "region put mi", "region remove mi", "start ota", "reboot",
            "advert", "get", "", "ver x",
        )) assertFalse(CliExchange.isRead(write), write)
    }

    // ------------------------------------------------------------------
    // Stragglers
    // ------------------------------------------------------------------

    @Test
    fun theExtraAnswerToAResentCommandIsSkippedOnce() {
        val s = CliStragglers()
        s.owe("> 5", sends = 2, now = 0)
        assertTrue(s.consume("> 5", now = 1))
        // Only one was owed; the next identical text is a real answer.
        assertFalse(s.consume("> 5", now = 2))
    }

    @Test
    fun aCommandSentOnceOwesNothing() {
        val s = CliStragglers()
        s.owe("> 5", sends = 1, now = 0)
        assertFalse(s.consume("> 5", now = 1))
    }

    @Test
    fun onlyTheSameTextIsSkipped() {
        val s = CliStragglers()
        s.owe("> 5", sends = 3, now = 0)
        assertFalse(s.consume("> 7", now = 1))
        assertTrue(s.consume(" > 5 ", now = 1))
        assertTrue(s.consume("> 5", now = 1))
        assertFalse(s.consume("> 5", now = 1))
    }

    @Test
    fun anOwedAnswerExpires() {
        val s = CliStragglers(ttlMs = 1_000)
        s.owe("> 5", sends = 2, now = 0)
        assertFalse(s.consume("> 5", now = 1_000))
    }
}
