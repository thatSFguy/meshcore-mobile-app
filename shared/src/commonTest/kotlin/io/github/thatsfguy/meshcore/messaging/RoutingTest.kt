package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.PathDiscovery
import io.github.thatsfguy.meshcore.protocol.RoutingMode
import io.github.thatsfguy.meshcore.protocol.TraceResult
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingTest {

    private val al = "aa".repeat(32)

    private class FakeRadio(
        var accept: Boolean = true,
        var trace: TraceResult? = null,
        var discovery: PathDiscovery.Result? = null,
    ) : Routing.Radio {
        val set = mutableListOf<Pair<RoutingMode, String>>()
        var tracedWidth = 0
        override suspend fun setRouting(keyHex: String, mode: RoutingMode, path: ByteArray): Boolean {
            set += mode to path.toHex(); return accept
        }
        override suspend fun trace(path: ByteArray, hashWidth: Int): TraceResult? { tracedWidth = hashWidth; return trace }
        override suspend fun discover(keyHex: String) = discovery
    }

    private fun contact(pathLen: Int, path: String = "", type: Int = Codes.ADV_TYPE_CHAT) = Contact(
        publicKey = hexToBytesOrNull(al)!!, type = type, flags = 0, pathLen = pathLen,
        path = (hexToBytesOrNull(path) ?: ByteArray(0)).copyOf(64), name = "Al", timestamp = 0,
        latitude = null, longitude = null, lastModified = 0,
    )

    @Test
    fun manualNeedsHopsAndIsRememberedWhenTheRadioTakesIt() = runTest {
        val r = FakeRadio()
        val paths = PathHistoryBook({ 1 })
        assertEquals(Routing.Outcome(false, "Enter at least one hop"), Routing(r).apply(al, RoutingMode.Manual, "", 2, paths))
        assertTrue(r.set.isEmpty())
        val ok = Routing(r).apply(al, RoutingMode.Manual, "B389C985", 2, paths)
        assertEquals(Routing.Outcome(true, "Routing set to manual"), ok)
        assertEquals(RoutingMode.Manual to "b389c985", r.set.single())
        assertEquals("b389c985", paths.forContact(al).single().pathHex)
    }

    @Test
    fun aRefusedRouteIsNotRemembered() = runTest {
        val paths = PathHistoryBook({ 1 })
        val out = Routing(FakeRadio(accept = false)).apply(al, RoutingMode.Manual, "b389c985", 2, paths)
        assertEquals("Radio rejected the route", out.message)
        assertTrue(paths.forContact(al).isEmpty())
    }

    @Test
    fun autoAndFloodSendAnEmptyPath() = runTest {
        val r = FakeRadio()
        Routing(r).apply(al, RoutingMode.Flood, null, 2, null)
        assertEquals(RoutingMode.Flood to "", r.set.single())
    }

    @Test
    fun aTraceUsesTheMeshWidthAndReadsItsResult() = runTest {
        // 0x42: 2-byte hashes, 2 hops — the hop-hash width is a property of the mesh.
        val r = FakeRadio(trace = TraceResult(1, 0, 1, 2, listOf("b389", "c985"), listOf(6.5, -2.25, 4.0)))
        val lines = Routing(r).trace(contact(0x42, "b389c985"), meshWidth = 2)
        assertEquals(2, r.tracedWidth)
        assertEquals(listOf("2 hop(s): b389 → c985", "SNR: 6.5 dB, -2.3 dB, 4.0 dB"), lines)
    }

    @Test
    fun aFloodContactCannotBeTracedAndSaysWhy() = runTest {
        val c = contact(0xFF)
        assertTrue(!Routing.canTrace(c))
        val lines = Routing(FakeRadio()).trace(c, 2)
        assertTrue(lines.single().startsWith("No stored route to trace"))
        val silent = Routing(FakeRadio(trace = null)).trace(contact(0x42, "b389c985"), 2)
        assertTrue(silent.single().startsWith("No reply."))
    }

    @Test
    fun silenceFromAChatNodeIsNotTakenAsDeafness() = runTest {
        val chat = Routing(FakeRadio()).check(contact(0xFF), emptyMap())
        assertTrue("most radios only answer" in chat, chat)
        val rptr = Routing(FakeRadio()).check(contact(0xFF, type = Codes.ADV_TYPE_REPEATER), emptyMap())
        assertTrue("They may not hear you" in rptr, rptr)
    }

    @Test
    fun theReplyRouteIsTheArrivalReversedHopByHop() {
        val book = MessageBook(nowMs = { 1 })
        book.receive("dm|$al", false, null, "hi", 1, "k", hops = 2, arrivalPathHex = "b389c985", arrivalHashWidth = 2)
        // Reversed by HOP at width 2 — not by byte, which would give 85c989b3.
        assertEquals("c985b389", Routing.replyRoute(book, "dm|$al"))
        assertNull(Routing.replyRoute(book, "dm|${"bb".repeat(32)}"))
    }

    @Test
    fun theRelayListIsRepeatersAndRoomsByName() {
        val rp = contact(0xFF, type = Codes.ADV_TYPE_REPEATER).copy(name = "zeta")
        val rm = contact(0xFF, type = Codes.ADV_TYPE_ROOM).copy(name = "Alpha")
        val ch = contact(0xFF).copy(name = "chat")
        assertEquals(listOf("Alpha", "zeta"), Routing.relays(listOf(rp, ch, rm)).map { it.name })
        assertEquals("Route is full (${io.github.thatsfguy.meshcore.protocol.PathCodec.maxHopsFor(2)} hops max on this mesh).",
            Routing.relayHint(io.github.thatsfguy.meshcore.protocol.PathCodec.maxHopsFor(2), 2))
    }

    @Test
    fun aPingThatComesBackSaysBothSignalsAndTheTime() = runTest {
        // One hop at the mesh's 2-byte width: the node's first two key bytes, aa aa.
        val r = FakeRadio(trace = TraceResult(1, 0, 1, 2, listOf("aaaa"), listOf(6.5, -1.0)))
        var t = 1_000L
        val line = Routing(r).ping(al, 2, listOf(al)) { t.also { t += 120 } }
        assertTrue(line.startsWith("Reached directly in 120 ms."), line)
        assertEquals(2, r.tracedWidth)
        assertTrue("⚠" !in line)
    }

    @Test
    fun aPingWarnsWhenAnotherNodeSharesTheHash() = runTest {
        val r = FakeRadio(trace = TraceResult(1, 0, 1, 2, listOf("aaaa"), listOf(6.5, -1.0)))
        val line = Routing(r).ping(al, 2, listOf(al, "aaaa" + "bb".repeat(30))) { 0 }
        assertTrue("1 other known node shares this hop hash" in line, line)
    }

    @Test
    fun silenceOrAnUnexpressibleWidthIsSaid() = runTest {
        assertTrue(Routing(FakeRadio(trace = null)).ping(al, 2, emptyList()) { 0 }.startsWith("No reply."))
        assertTrue("3-byte hop hashes" in Routing(FakeRadio()).ping(al, 3, emptyList()) { 0 })
    }
}
