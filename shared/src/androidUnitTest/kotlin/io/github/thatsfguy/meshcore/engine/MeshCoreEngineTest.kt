package io.github.thatsfguy.meshcore.engine

import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.protocol.BufferWriter
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.transport.IncomingFrame
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Engine-level tests against a scripted fake radio: every frame the
 * engine sends is answered the way MeshCore firmware would, so the
 * handshake → sync → messaging pipeline is exercised end-to-end without
 * hardware.
 */
class MeshCoreEngineTest {

    private val crypto = AndroidCryptoProvider()

    private class FakeRadio : Transport {
        override val state = MutableStateFlow(TransportState.Disconnected)
        private val _incoming = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 256)
        override val incoming: Flow<IncomingFrame> = _incoming.asSharedFlow()

        val sentFrames = ArrayList<ByteArray>()
        var responder: (ByteArray) -> List<ByteArray> = { emptyList() }

        override suspend fun connect() { state.value = TransportState.Connected }
        override suspend fun disconnect() { state.value = TransportState.Disconnected }

        override suspend fun send(frame: ByteArray) {
            sentFrames.add(frame)
            for (reply in responder(frame)) push(reply)
        }

        fun push(frame: ByteArray) {
            check(_incoming.tryEmit(IncomingFrame(frame))) { "test flow overflow" }
        }
    }

    private fun selfInfoFrame(pubKey: ByteArray, name: String = "MyNode"): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_SELF_INFO)
        w.writeByte(Codes.ADV_TYPE_CHAT)
        w.writeByte(22); w.writeByte(30)
        w.writeBytes(pubKey)
        w.writeInt32LE(0); w.writeInt32LE(0)
        w.writeByte(1); w.writeByte(1); w.writeByte(0); w.writeByte(1)
        w.writeUInt32LE(910_525_000L); w.writeUInt32LE(250_000L)
        w.writeByte(10); w.writeByte(5)
        w.writeString(name); w.writeByte(0)
        return w.toBytes()
    }

    private fun deviceInfoFrame(maxChannels: Int = 4): ByteArray {
        val f = ByteArray(4)
        f[0] = Codes.RESP_CODE_DEVICE_INFO.toByte()
        f[1] = 10          // fw ver
        f[2] = 100         // max contacts / 2
        f[3] = maxChannels.toByte()
        return f
    }

    private fun channelInfoFrame(index: Int, name: String, psk: ByteArray): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CHANNEL_INFO)
        w.writeByte(index)
        w.writeFixedCString(name, 32)
        w.writeBytesPadded(psk, 16)
        return w.toBytes()
    }

    private fun currTimeFrame(ts: Long): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CURR_TIME)
        w.writeUInt32LE(ts)
        return w.toBytes()
    }

    private fun contactFrame(pubKey: ByteArray, name: String): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CONTACT)
        w.writeBytes(pubKey)
        w.writeByte(Codes.ADV_TYPE_CHAT)
        w.writeByte(0)
        w.writeByte(0)
        w.writeBytesPadded(ByteArray(0), 64)
        w.writeFixedCString(name, 32)
        w.writeUInt32LE(1L)
        w.writeInt32LE(0); w.writeInt32LE(0)
        w.writeUInt32LE(1L)
        return w.toBytes()
    }

    private val now = 1_700_000_000L
    private val selfKey = ByteArray(32) { 1 }
    private val peerKey = ByteArray(32) { (it + 40).toByte() }
    private val psk = ChannelCrypto.PUBLIC_CHANNEL_PSK

    /** Standard firmware behavior for the handshake + initial syncs. */
    private fun standardResponder(radio: FakeRadio): (ByteArray) -> List<ByteArray> = { frame ->
        when (frame[0].toInt() and 0xFF) {
            Codes.CMD_APP_START -> listOf(selfInfoFrame(selfKey))
            Codes.CMD_DEVICE_QUERY -> listOf(deviceInfoFrame())
            Codes.CMD_GET_DEVICE_TIME -> listOf(currTimeFrame(now))
            Codes.CMD_GET_CHANNEL -> {
                val idx = frame[1].toInt()
                if (idx == 0) listOf(channelInfoFrame(0, "Public", psk))
                else listOf(channelInfoFrame(idx, "", ByteArray(16)))
            }
            Codes.CMD_GET_CONTACTS -> listOf(
                byteArrayOf(Codes.RESP_CODE_CONTACTS_START.toByte(), 1, 0, 0, 0),
                contactFrame(peerKey, "peer"),
                byteArrayOf(Codes.RESP_CODE_END_OF_CONTACTS.toByte()),
            )
            Codes.CMD_GET_BATT_AND_STORAGE -> listOf(
                byteArrayOf(Codes.RESP_CODE_BATT_AND_STORAGE.toByte(), 0x9A.toByte(), 0x0F, 0, 0, 0, 0, 0, 0, 0, 0),
            )
            Codes.CMD_GET_CUSTOM_VAR -> listOf(
                byteArrayOf(Codes.RESP_CODE_CUSTOM_VARS.toByte(), 0),
            )
            Codes.CMD_SYNC_NEXT_MESSAGE -> listOf(
                byteArrayOf(Codes.RESP_CODE_NO_MORE_MESSAGES.toByte()),
            )
            Codes.CMD_SEND_TXT_MSG, Codes.CMD_SEND_CHANNEL_TXT_MSG -> {
                val w = BufferWriter()
                w.writeByte(Codes.RESP_CODE_SENT)
                w.writeByte(0)
                w.writeUInt32LE(0xABCD1234L)
                w.writeUInt32LE(3000L)
                listOf(w.toBytes())
            }
            Codes.CMD_SEND_LOGIN -> {
                val w = BufferWriter()
                w.writeByte(Codes.PUSH_CODE_LOGIN_SUCCESS)
                w.writeByte(1)
                w.writeBytes(peerKey.copyOfRange(0, 6))
                w.writeUInt32LE(now)
                listOf(w.toBytes())
            }
            else -> listOf(byteArrayOf(Codes.RESP_CODE_OK.toByte()))
        }
    }

    private suspend fun MeshCoreEngine.awaitReady() {
        withTimeout(10_000) { state.first { it == EngineState.Ready } }
        // Let the post-handshake sync sequence finish.
        withTimeout(10_000) {
            meshEvents.first { it is MeshEvent.ContactsSynced }
        }
    }

    @Test
    fun handshakeReachesReadyAndSyncs() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()

        engine.awaitReady()
        assertEquals("MyNode", engine.selfInfo.value?.name)
        assertEquals(4, engine.deviceInfo.value?.maxChannels)
        assertEquals(1, engine.channels.value.size)
        assertEquals("Public", engine.channels.value[0].name)
        assertEquals(1, engine.contacts.value.size)
        assertEquals("peer", engine.contacts.value.values.first().name)
        // Battery refresh runs after the contact sync — await the flow.
        val battery = withTimeout(10_000) { engine.battery.first { it != null } }
        assertEquals(3994, battery?.batteryMillivolts)
    }

    @Test
    fun sendDirectMessageReturnsAckHash() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val sent = engine.sendDirectMessage(peerKey, "hello")
        assertNotNull(sent)
        assertEquals(0xABCD1234L, sent.ackHash)

        // Delivery confirmation push resolves as MessageDelivered.
        val w = BufferWriter()
        w.writeByte(Codes.PUSH_CODE_SEND_CONFIRMED)
        w.writeUInt32LE(0xABCD1234L)
        w.writeUInt32LE(210L)
        val delivered = async {
            withTimeout(5_000) { engine.meshEvents.first { it is MeshEvent.MessageDelivered } }
        }
        yield()
        radio.push(w.toBytes())
        val ev = delivered.await() as MeshEvent.MessageDelivered
        assertEquals(210L, ev.tripMs)
    }

    @Test
    fun channelSendAcceptsGenericOk() = runTest {
        // Real firmware answers a group send with RESP_CODE_OK, not
        // RESP_CODE_SENT (first-hardware-session finding, 2026-07-31).
        val radio = FakeRadio()
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_CHANNEL_TXT_MSG ->
                    listOf(byteArrayOf(Codes.RESP_CODE_OK.toByte()))
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        assertTrue(engine.sendChannelMessage(0, "hello", now))
        // Frame carries the caller's timestamp so echo dedup keys match.
        val frame = radio.sentFrames.last { it[0].toInt() == Codes.CMD_SEND_CHANNEL_TXT_MSG }
        val r = io.github.thatsfguy.meshcore.protocol.BufferReader(frame)
        r.skipBytes(3)
        assertEquals(now, r.readUInt32LE())

        // Same inputs → same content key (what the echo will hash to).
        assertEquals(
            engine.channelContentKey(0, now, "MyNode", "hello"),
            engine.channelContentKey(0, now, "MyNode", "hello"),
        )
    }

    @Test
    fun cliQueryAwaitsTheRepeatersTextReply() = runTest {
        // The form-based admin UI depends on: send "get freq" → radio
        // ACKs (RESP_CODE_SENT) → repeater's reply arrives later as a
        // contact message from that node → returned as the value.
        val radio = FakeRadio()
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_TXT_MSG -> {
                    val sent = BufferWriter().apply {
                        writeByte(Codes.RESP_CODE_SENT)
                        writeByte(0)
                        writeUInt32LE(1L)
                        writeUInt32LE(1000L)
                    }.toBytes()
                    val reply = BufferWriter().apply {
                        writeByte(Codes.RESP_CODE_CONTACT_MSG_RECV)
                        writeBytes(peerKey.copyOfRange(0, 6))
                        writeByte(0)
                        writeByte(Codes.TXT_TYPE_CLI_DATA)
                        writeUInt32LE(now)
                        writeString("> 910.525")
                        writeByte(0)
                    }.toBytes()
                    listOf(sent, reply)
                }
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val reply = engine.sendCliAndAwaitReply(peerKey, "get freq")
        assertEquals("> 910.525", reply)
        assertEquals(
            "910.525",
            io.github.thatsfguy.meshcore.protocol.CliReplies.extractGetValue(reply!!),
        )
    }

    @Test
    fun loginSucceedsAgainstFakeRepeater() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        assertTrue(engine.sendLogin(peerKey, "secret"))
    }

    @Test
    fun rxLogChannelMessageDecrypts() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        // Build a GRP_TXT RX-log push encrypted with the Public PSK.
        val pw = BufferWriter()
        pw.writeUInt32LE(1234L)
        pw.writeByte(0)
        pw.writeString("carol: off grid")
        pw.writeByte(0)
        val encrypted = ChannelCrypto.encryptForTest(crypto, psk, pw.toBytes())
        val header = (Codes.PAYLOAD_TYPE_GRP_TXT shl 2) or 0x01
        val packet = byteArrayOf(header.toByte(), 0x00) +
            byteArrayOf(ChannelCrypto.channelHash(crypto, psk).toByte()) + encrypted
        val push = byteArrayOf(Codes.PUSH_CODE_LOG_RX_DATA.toByte(), 40, 0xB0.toByte()) + packet

        val waiter = async {
            withTimeout(5_000) {
                engine.meshEvents.first { it is MeshEvent.ChannelMessageReceived }
            }
        }
        yield()
        radio.push(push)
        val msg = waiter.await() as MeshEvent.ChannelMessageReceived
        assertEquals("carol", msg.senderName)
        assertEquals("off grid", msg.text)
        assertEquals(0, msg.channelIndex)
    }

    @Test
    fun rxLogForgedAdvertIsDropped() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        var advertEvents = 0
        val collector = launch {
            engine.meshEvents.collect { if (it is MeshEvent.VerifiedAdvertHeard) advertEvents++ }
        }
        yield()

        // A structurally-valid advert with a garbage signature.
        val forged = ByteArray(101)
        forged[100] = 0x81.toByte() // flags: chat + has_name (no name bytes → fine)
        val header = (Codes.PAYLOAD_TYPE_ADVERT shl 2) or 0x01
        val packet = byteArrayOf(header.toByte(), 0x00) + forged
        radio.push(byteArrayOf(Codes.PUSH_CODE_LOG_RX_DATA.toByte(), 0, 0) + packet)
        yield(); yield()

        assertEquals(0, advertEvents)
        collector.cancel()
    }

    // ------------------------------------------------------------------
    // Regions / flood scope (PARITY §8)
    // ------------------------------------------------------------------

    /** SET_FLOOD_SCOPE payloads in send order; null = "cleared". */
    private fun FakeRadio.scopeFrames(): List<ByteArray?> =
        sentFrames.filter { it[0].toInt() and 0xFF == Codes.CMD_SET_FLOOD_SCOPE }
            .map { if (it.size > 2) it.copyOfRange(2, it.size) else null }

    /**
     * Scope + channel-send frames in order. Background syncs (battery,
     * custom vars) keep running after the handshake and would otherwise
     * show up between them; they are unrelated to the scope window.
     */
    private fun FakeRadio.scopeAndSendKinds(): List<Int> =
        sentFrames.map { it[0].toInt() and 0xFF }
            .filter { it == Codes.CMD_SET_FLOOD_SCOPE || it == Codes.CMD_SEND_CHANNEL_TXT_MSG }

    @Test
    fun regionScopedChannelSendWrapsTheSendInAScopeWindow() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()
        radio.sentFrames.clear()

        assertTrue(engine.sendChannelMessage(0, "hello", now, region = "bayarea"))

        // Scope set, message sent, scope released — in that order. The
        // radio must never be left holding a region after the send.
        assertEquals(
            listOf(
                Codes.CMD_SET_FLOOD_SCOPE,
                Codes.CMD_SEND_CHANNEL_TXT_MSG,
                Codes.CMD_SET_FLOOD_SCOPE,
            ),
            radio.scopeAndSendKinds(),
        )
        val scopes = radio.scopeFrames()
        assertEquals(2, scopes.size)
        assertContentEquals(ChannelCrypto.floodScopeHash(crypto, "bayarea"), scopes[0])
        assertEquals(null, scopes[1])
    }

    @Test
    fun anUnscopedChannelSendNeverTouchesTheFloodScope() = runTest {
        // Plain channels must keep working against firmware that predates
        // CMD_SET_FLOOD_SCOPE.
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()
        radio.sentFrames.clear()

        assertTrue(engine.sendChannelMessage(0, "hello", now))
        assertTrue(engine.sendChannelMessage(0, "hello", now, region = "  "))
        assertEquals(listOf(Codes.CMD_SEND_CHANNEL_TXT_MSG, Codes.CMD_SEND_CHANNEL_TXT_MSG), radio.scopeAndSendKinds())
    }

    @Test
    fun aScopedSendRestoresTheUsersGlobalScopeRatherThanClearingIt() = runTest {
        // The Settings screen owns the global scope; a per-channel send
        // must put it back, not quietly blank it.
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        assertTrue(engine.setFloodScope("socal"))
        radio.sentFrames.clear()

        assertTrue(engine.sendChannelMessage(0, "hello", now, region = "bayarea"))
        val scopes = radio.scopeFrames()
        assertContentEquals(ChannelCrypto.floodScopeHash(crypto, "bayarea"), scopes[0])
        assertContentEquals(ChannelCrypto.floodScopeHash(crypto, "socal"), scopes[1])
        assertEquals("socal", engine.floodScopeRegion.value)
    }

    @Test
    fun aRefusedScopeAbortsTheSendInsteadOfUsingTheWrongRegion() = runTest {
        val radio = FakeRadio()
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SET_FLOOD_SCOPE -> listOf(byteArrayOf(Codes.RESP_CODE_ERR.toByte(), 1))
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()
        radio.sentFrames.clear()

        // Sending anyway would put the message on whatever scope the
        // radio happens to hold — a different region, silently.
        assertTrue(!engine.sendChannelMessage(0, "hello", now, region = "bayarea"))
        assertTrue(radio.scopeAndSendKinds().none { it == Codes.CMD_SEND_CHANNEL_TXT_MSG })
    }

    @Test
    fun setFloodScopeRefusesANameThatIsNotARegion() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()
        radio.sentFrames.clear()

        // Hashing junk would scope traffic to a region nobody uses.
        assertTrue(!engine.setFloodScope("bay area"))
        assertTrue(!engine.setFloodScope("b".repeat(31)))
        assertTrue(radio.scopeFrames().isEmpty())

        // "#BayArea" is the same region as "bayarea" once canonicalised.
        assertTrue(engine.setFloodScope("#BayArea"))
        assertEquals("bayarea", engine.floodScopeRegion.value)
        assertContentEquals(
            ChannelCrypto.floodScopeHash(crypto, "bayarea"),
            radio.scopeFrames().last(),
        )
    }

    @Test
    fun requestRegionsCorrelatesTheReplyByTag() = runTest {
        val radio = FakeRadio()
        val ackHash = 0x0BADF00DL
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_ANON_REQ -> {
                    val sent = BufferWriter().apply {
                        writeByte(Codes.RESP_CODE_SENT)
                        writeByte(1)
                        writeUInt32LE(ackHash)
                        writeUInt32LE(4000L)
                    }.toBytes()
                    // A reply for someone else's request first — it must
                    // not be mistaken for ours.
                    val decoy = BufferWriter().apply {
                        writeByte(Codes.PUSH_CODE_BINARY_RESPONSE)
                        writeByte(0)
                        writeUInt32LE(0xDEADBEEFL)
                        writeUInt32LE(0)
                        writeString("elsewhere")
                    }.toBytes()
                    val reply = BufferWriter().apply {
                        writeByte(Codes.PUSH_CODE_BINARY_RESPONSE)
                        writeByte(0)
                        writeUInt32LE(ackHash)
                        writeUInt32LE(0) // 4-byte body header
                        writeString("bayarea,socal,BAD NAME")
                    }.toBytes()
                    listOf(sent, decoy, reply)
                }
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val regions = engine.requestRegions(peerKey)
        assertEquals(listOf("bayarea", "socal"), regions)

        // The request must carry the anon req type and a 0-hop reply path.
        val frame = radio.sentFrames.last { it[0].toInt() and 0xFF == Codes.CMD_SEND_ANON_REQ }
        assertEquals(Codes.ANON_REQ_TYPE_REGIONS, frame[33].toInt() and 0xFF)
        assertEquals(0, frame[34].toInt() and 0xFF)
    }

    @Test
    fun requestRegionsReturnsNullWhenTheNodeNeverAnswers() = runTest {
        // "no answer" and "answered with nothing" are different facts and
        // the UI has to be able to tell them apart.
        val radio = FakeRadio()
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_ANON_REQ -> listOf(
                    BufferWriter().apply {
                        writeByte(Codes.RESP_CODE_SENT)
                        writeByte(1)
                        writeUInt32LE(1L)
                        writeUInt32LE(1000L)
                    }.toBytes(),
                )
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        assertEquals(null, engine.requestRegions(peerKey))
    }

    @Test
    fun discoveryCollectsOnlyRepliesCarryingOurOwnTag() = runTest {
        val radio = FakeRadio()
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_CONTROL_DATA -> {
                    // Echo the tag the engine chose back in two responses,
                    // plus one carrying a stranger's tag.
                    val r = io.github.thatsfguy.meshcore.protocol.BufferReader(frame)
                    r.skipBytes(3)
                    val tag = r.readUInt32LE()
                    fun resp(t: Long, prefix: ByteArray) = BufferWriter().apply {
                        writeByte(Codes.PUSH_CODE_CONTROL_DATA)
                        writeByte(10); writeByte(0xB0); writeByte(0)
                        writeByte((Codes.CONTROL_SUBTYPE_DISCOVER_RESP shl 4) or Codes.ADV_TYPE_REPEATER)
                        writeByte(12)
                        writeUInt32LE(t)
                        writeBytes(prefix)
                    }.toBytes()
                    listOf(
                        resp(tag, byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
                        resp(tag + 1, byteArrayOf(0x11, 0x22)),
                        resp(tag, byteArrayOf(0xCC.toByte(), 0xDD.toByte())),
                    )
                }
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val prefixes = engine.discoverNodePrefixes()
        assertEquals(setOf("aabb", "ccdd"), prefixes)
    }

    @Test
    fun setChannelWritesAndResyncs() = runTest {
        val radio = FakeRadio()
        val channels = HashMap<Int, Channel>()
        channels[0] = Channel(0, "Public", psk)
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SET_CHANNEL -> {
                    val idx = frame[1].toInt()
                    val name = frame.copyOfRange(2, 34).takeWhile { it.toInt() != 0 }
                        .toByteArray().decodeToString()
                    channels[idx] = Channel(idx, name, frame.copyOfRange(34, 50))
                    listOf(byteArrayOf(Codes.RESP_CODE_OK.toByte()))
                }
                Codes.CMD_GET_CHANNEL -> {
                    val idx = frame[1].toInt()
                    val ch = channels[idx]
                    listOf(
                        if (ch != null) channelInfoFrame(ch.index, ch.name, ch.psk)
                        else channelInfoFrame(idx, "", ByteArray(16)),
                    )
                }
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val newPsk = ChannelCrypto.hashtagPsk(crypto, "#rescue")
        assertTrue(engine.setChannel(1, "#rescue", newPsk))
        assertEquals(2, engine.channels.value.size)
        assertEquals("#rescue", engine.channels.value[1].name)
        assertEquals(2, engine.nextFreeChannelIndex())
    }
}
