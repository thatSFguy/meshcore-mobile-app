package io.github.thatsfguy.meshcore.engine

import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.protocol.BufferWriter
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.AccessList
import io.github.thatsfguy.meshcore.protocol.PathRecovery
import io.github.thatsfguy.meshcore.protocol.Advert
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.HeardRepeats
import io.github.thatsfguy.meshcore.protocol.ShareUri
import io.github.thatsfguy.meshcore.protocol.MeshIdentity
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        w.writeUInt32LE(910_525L); w.writeUInt32LE(250_000L)
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

    private fun contactFrame(
        pubKey: ByteArray,
        name: String,
        outPath: ByteArray = ByteArray(0),
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CONTACT)
        w.writeBytes(pubKey)
        w.writeByte(Codes.ADV_TYPE_CHAT)
        w.writeByte(0)
        w.writeByte(outPath.size)
        w.writeBytesPadded(outPath, 64)
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
            Codes.CMD_SEND_LOGIN -> listOf(loginSentFrame(), loginSuccessPush())
            else -> listOf(byteArrayOf(Codes.RESP_CODE_OK.toByte()))
        }
    }


    /**
     * The 10-byte frame the firmware actually answers CMD_SEND_LOGIN
     * with: `RESP_CODE_SENT`, is_flood, the first 4 bytes of the peer
     * key as a correlation tag, then its own airtime estimate
     * (`companion_radio/MyMesh.cpp`, CMD_SEND_LOGIN branch).
     *
     * The fake used to reply with the success push alone, which is not
     * a thing the firmware ever does. Nothing noticed until the client
     * started reading the estimate — a fake that disagrees with the
     * hardware is the failure LESSONS §8 is about, and this one was
     * written by the same hand as the code it was testing.
     */
    private fun loginSentFrame(estTimeoutMs: Long = 3_000L, flood: Boolean = false): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_SENT)
        w.writeByte(if (flood) 1 else 0)
        w.writeBytes(peerKey.copyOfRange(0, 4))
        w.writeUInt32LE(estTimeoutMs)
        return w.toBytes()
    }

    private fun loginSuccessPush(isAdmin: Boolean = true): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.PUSH_CODE_LOGIN_SUCCESS)
        w.writeByte(if (isAdmin) 1 else 0)
        w.writeBytes(peerKey.copyOfRange(0, 6))
        w.writeUInt32LE(now)
        return w.toBytes()
    }

    private fun loginFailPush(): ByteArray =
        byteArrayOf(Codes.PUSH_CODE_LOGIN_FAIL.toByte())

    /** Standard firmware, except login answers per attempt index. */
    private fun loginResponder(
        radio: FakeRadio,
        outPath: ByteArray = ByteArray(0),
        replies: (Int) -> List<ByteArray>,
    ): (ByteArray) -> List<ByteArray> {
        val base = standardResponder(radio)
        var attempts = 0
        return { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_LOGIN -> replies(attempts++)
                Codes.CMD_GET_CONTACTS -> listOf(
                    byteArrayOf(Codes.RESP_CODE_CONTACTS_START.toByte(), 1, 0, 0, 0),
                    contactFrame(peerKey, "peer", outPath),
                    byteArrayOf(Codes.RESP_CODE_END_OF_CONTACTS.toByte()),
                )
                else -> base(frame)
            }
        }
    }

    private fun FakeRadio.countOf(cmd: Int): Int =
        sentFrames.count { (it[0].toInt() and 0xFF) == cmd }

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

        val outcome = engine.sendLogin(peerKey, "secret")
        assertTrue(outcome.accepted)
        assertTrue(outcome.answered)
    }

    /**
     * A rejected password must be sent exactly once.
     *
     * Retrying cannot change the answer, and CMD_SEND_LOGIN carries the
     * password in CLEARTEXT (§12) — two more transmissions to be told
     * "no" again is airtime spent putting a secret on the air.
     */
    @Test
    fun aRejectedLoginIsNotRetried() = runTest {
        val radio = FakeRadio()
        radio.responder = loginResponder(radio) { listOf(loginSentFrame(), loginFailPush()) }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val outcome = engine.sendLoginWithRetry(peerKey, "wrong")
        assertFalse(outcome.accepted)
        // The node spoke. That is the difference between "wrong
        // password" and "out of range", and the UI says different
        // things for each.
        assertTrue(outcome.answered)
        assertEquals(1, radio.countOf(Codes.CMD_SEND_LOGIN))
    }

    /** Silence is the failure retrying exists for. */
    @Test
    fun aLoginThatIsNeverAnsweredIsRetried() = runTest {
        val radio = FakeRadio()
        radio.responder = loginResponder(radio) { listOf(loginSentFrame()) }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val outcome = engine.sendLoginWithRetry(peerKey, "secret")
        assertFalse(outcome.accepted)
        assertFalse(outcome.answered)
        assertEquals(3, radio.countOf(Codes.CMD_SEND_LOGIN))
    }

    @Test
    fun aLoginThatSucceedsOnRetryStopsThere() = runTest {
        val radio = FakeRadio()
        radio.responder = loginResponder(radio) { attempt ->
            if (attempt == 0) listOf(loginSentFrame())
            else listOf(loginSentFrame(), loginSuccessPush())
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        assertTrue(engine.sendLoginWithRetry(peerKey, "secret").accepted)
        assertEquals(2, radio.countOf(Codes.CMD_SEND_LOGIN))
    }

    /**
     * The last attempt clears the stored path, which is what makes a
     * login survive a repeater going away — the firmware routes a login
     * over `out_path` exactly like a text message
     * (`BaseChatMesh::sendLogin`), so it inherits the same stale-path
     * failure and the same remedy.
     */
    @Test
    fun theLastLoginAttemptResetsAStalePath() = runTest {
        val radio = FakeRadio()
        radio.responder = loginResponder(radio, outPath = byteArrayOf(0x11, 0x22)) {
            listOf(loginSentFrame())
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        engine.sendLoginWithRetry(peerKey, "secret")
        assertEquals(1, radio.countOf(Codes.CMD_RESET_PATH))

        // …and only before the FINAL attempt, not the first two.
        val order = radio.sentFrames.map { it[0].toInt() and 0xFF }
            .filter { it == Codes.CMD_SEND_LOGIN || it == Codes.CMD_RESET_PATH }
        assertEquals(
            listOf(
                Codes.CMD_SEND_LOGIN,
                Codes.CMD_SEND_LOGIN,
                Codes.CMD_RESET_PATH,
                Codes.CMD_SEND_LOGIN,
            ),
            order,
        )
    }

    @Test
    fun aContactWithNoPathIsNeverReset() = runTest {
        // Already flooding — there is nothing to clear, and spending a
        // round trip to reach the state we are in is not a fallback.
        val radio = FakeRadio()
        radio.responder = loginResponder(radio) { listOf(loginSentFrame()) }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        engine.sendLoginWithRetry(peerKey, "secret")
        assertEquals(0, radio.countOf(Codes.CMD_RESET_PATH))
    }

    @Test
    fun theFloodFallbackCanBeTurnedOff() = runTest {
        val radio = FakeRadio()
        radio.responder = loginResponder(radio, outPath = byteArrayOf(0x11, 0x22)) {
            listOf(loginSentFrame())
        }
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        engine.sendLoginWithRetry(peerKey, "secret", floodFallbackEnabled = false)
        assertEquals(3, radio.countOf(Codes.CMD_SEND_LOGIN))
        assertEquals(0, radio.countOf(Codes.CMD_RESET_PATH))
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

    // ------------------------------------------------------------------
    // Heard-via: the route a message arrived on (PARITY §2, §13)
    // ------------------------------------------------------------------

    /** `path_len_enc` for [hops] hops at [width] bytes each. */
    private fun pathLenEnc(hops: Int, width: Int): Int = ((width - 1) shl 6) or (hops and 0x3F)

    /** A two-hop route through b389 then c985, as this mesh encodes it. */
    private val twoHopPath = byteArrayOf(0xb3.toByte(), 0x89.toByte(), 0xc9.toByte(), 0x85.toByte())

    private fun rxLogPush(packet: ByteArray): ByteArray =
        byteArrayOf(Codes.PUSH_CODE_LOG_RX_DATA.toByte(), 40, 0xB0.toByte()) + packet

    /** A TXT_MSG packet we CANNOT decrypt — only its route and src_hash. */
    private fun encryptedDmPacket(
        srcHash: Int,
        path: ByteArray = twoHopPath,
        width: Int = 2,
    ): ByteArray {
        val header = (Codes.PAYLOAD_TYPE_TXT_MSG shl 2) or 0x01
        val hops = if (width > 0) path.size / width else 0
        // dest_hash, src_hash, 2-byte MAC, then ciphertext we can't read.
        val payload = byteArrayOf(0x01, srcHash.toByte(), 0x11, 0x22) + ByteArray(16) { 0x5A }
        return byteArrayOf(header.toByte(), pathLenEnc(hops, width).toByte()) + path + payload
    }

    private fun contactMsgFrame(hops: Int, width: Int, text: String): ByteArray =
        BufferWriter().apply {
            writeByte(Codes.RESP_CODE_CONTACT_MSG_RECV)
            writeBytes(peerKey.copyOfRange(0, 6))
            writeByte(((width - 1) shl 6) or (hops and 0x3F))
            writeByte(0)
            writeUInt32LE(now)
            writeString(text)
            writeByte(0)
        }.toBytes()

    @Test
    fun aChannelMessageCarriesTheFullRouteItArrivedOn() = runTest {
        // The exact case: the engine decrypts this very packet, so its
        // path IS the message's path — no correlation involved.
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val pw = BufferWriter()
        pw.writeUInt32LE(1234L)
        pw.writeByte(0)
        pw.writeString("carol: off grid")
        pw.writeByte(0)
        val encrypted = ChannelCrypto.encryptForTest(crypto, psk, pw.toBytes())
        val header = (Codes.PAYLOAD_TYPE_GRP_TXT shl 2) or 0x01
        val packet = byteArrayOf(header.toByte(), pathLenEnc(2, 2).toByte()) + twoHopPath +
            byteArrayOf(ChannelCrypto.channelHash(crypto, psk).toByte()) + encrypted

        val waiter = async {
            withTimeout(5_000) { engine.meshEvents.first { it is MeshEvent.ChannelMessageReceived } }
        }
        yield()
        radio.push(rxLogPush(packet))
        val msg = waiter.await() as MeshEvent.ChannelMessageReceived
        assertEquals("off grid", msg.text)
        assertEquals("b389c985", msg.arrivalPathHex)
        assertEquals(2, msg.arrivalHashWidth)
        // And the count still agrees with the route it just reported.
        assertEquals(2, msg.hops)
    }

    @Test
    fun aDirectMessageIsCreditedToTheOnePacketThatCouldHaveCarriedIt() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val waiter = async {
            withTimeout(5_000) { engine.meshEvents.first { it is MeshEvent.DirectMessageReceived } }
        }
        yield()
        // The raw packet is heard first (a push), then the radio hands
        // over the decrypted message from its queue.
        radio.push(rxLogPush(encryptedDmPacket(srcHash = peerKey[0].toInt() and 0xFF)))
        yield(); yield()
        radio.push(contactMsgFrame(hops = 2, width = 2, text = "on my way"))

        val msg = waiter.await() as MeshEvent.DirectMessageReceived
        assertEquals("on my way", msg.text)
        assertEquals("b389c985", msg.arrivalPathHex)
        assertEquals(2, msg.arrivalHashWidth)
    }

    @Test
    fun twoPlausiblePacketsLeaveTheRouteUnknownRatherThanGuessed() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val waiter = async {
            withTimeout(5_000) { engine.meshEvents.first { it is MeshEvent.DirectMessageReceived } }
        }
        yield()
        val src = peerKey[0].toInt() and 0xFF
        // Same sender byte, same hop count, different routes. We cannot
        // say which one carried the message — so we say nothing rather
        // than draw a route that looks exactly as confident as a right one.
        radio.push(rxLogPush(encryptedDmPacket(src, twoHopPath)))
        radio.push(
            rxLogPush(
                encryptedDmPacket(
                    src,
                    byteArrayOf(0xd0.toByte(), 0xce.toByte(), 0x90.toByte(), 0xa8.toByte()),
                ),
            ),
        )
        yield(); yield()
        radio.push(contactMsgFrame(hops = 2, width = 2, text = "ambiguous"))

        val msg = waiter.await() as MeshEvent.DirectMessageReceived
        assertEquals("ambiguous", msg.text)
        assertNull(msg.arrivalPathHex)
        // The hop COUNT is still known — the frame states it directly.
        assertEquals(2, msg.hops)
    }

    @Test
    fun aPacketFromSomeoneElseIsNotCreditedToThisSender() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val waiter = async {
            withTimeout(5_000) { engine.meshEvents.first { it is MeshEvent.DirectMessageReceived } }
        }
        yield()
        radio.push(rxLogPush(encryptedDmPacket(srcHash = 0x99)))
        yield(); yield()
        radio.push(contactMsgFrame(hops = 2, width = 2, text = "not theirs"))

        val msg = waiter.await() as MeshEvent.DirectMessageReceived
        assertNull(msg.arrivalPathHex)
    }

    @Test
    fun aPacketIsConsumedSoASecondMessageCannotClaimItToo() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val seen = ArrayList<MeshEvent.DirectMessageReceived>()
        val collector = launch {
            engine.meshEvents.collect { if (it is MeshEvent.DirectMessageReceived) seen.add(it) }
        }
        yield()
        radio.push(rxLogPush(encryptedDmPacket(srcHash = peerKey[0].toInt() and 0xFF)))
        yield(); yield()
        radio.push(contactMsgFrame(hops = 2, width = 2, text = "first"))
        yield(); yield()
        radio.push(contactMsgFrame(hops = 2, width = 2, text = "second"))
        yield(); yield()

        assertEquals(2, seen.size)
        assertEquals("b389c985", seen[0].arrivalPathHex)
        // One packet carried ONE message. Handing the same route to the
        // next message would be inventing evidence.
        assertNull(seen[1].arrivalPathHex)
        collector.cancel()
    }

    @Test
    fun aDisagreeingHopCountIsNotCredited() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val waiter = async {
            withTimeout(5_000) { engine.meshEvents.first { it is MeshEvent.DirectMessageReceived } }
        }
        yield()
        // A 2-hop packet, but the message frame says it travelled 3.
        radio.push(rxLogPush(encryptedDmPacket(srcHash = peerKey[0].toInt() and 0xFF)))
        yield(); yield()
        radio.push(contactMsgFrame(hops = 3, width = 2, text = "mismatch"))

        val msg = waiter.await() as MeshEvent.DirectMessageReceived
        assertNull(msg.arrivalPathHex)
    }

    // ------------------------------------------------------------------
    // Heard repeats — our own advert coming back off the mesh
    //
    // The firmware hands the client EVERY demodulated packet
    // (`Dispatcher::checkRecv` logs before the seen-table check), so a
    // repeater's rebroadcast of our own advert reaches us even though
    // the mesh layer discards it as a duplicate. That returning copy is
    // the only packet that names, under a signature only we can produce,
    // which repeaters carry our traffic.
    // ------------------------------------------------------------------

    /** An ADVERT packet carrying [payload], relayed over [path]. */
    private fun advertPacket(
        payload: ByteArray,
        path: ByteArray = twoHopPath,
        width: Int = 2,
    ): ByteArray {
        val header = (Codes.PAYLOAD_TYPE_ADVERT shl 2) or 0x01
        val hops = if (width > 0) path.size / width else 0
        return byteArrayOf(header.toByte(), pathLenEnc(hops, width).toByte()) + path + payload
    }

    private fun signedAdvert(identity: MeshIdentity, name: String): ByteArray =
        Advert.build(
            crypto,
            identity.seed,
            timestamp = now,
            appData = Advert.buildAppData(Codes.ADV_TYPE_CHAT, name, null, null),
        )

    /** A responder whose SELF_INFO reports [identity]'s real public key. */
    private fun responderFor(radio: FakeRadio, identity: MeshIdentity): (ByteArray) -> List<ByteArray> {
        val base = standardResponder(radio)
        return { frame ->
            if ((frame[0].toInt() and 0xFF) == Codes.CMD_APP_START) {
                listOf(selfInfoFrame(identity.publicKey))
            } else {
                base(frame)
            }
        }
    }

    @Test
    fun ourOwnAdvertRelayedBackNamesTheRepeatersThatCarriedIt() = runTest {
        // The positive control. Every other test in this group asserts
        // that something is NOT counted, and all of them would pass if
        // the feature recorded nothing at all.
        val me = MeshIdentity.fromSeed(crypto, ByteArray(32) { 7 })
        val radio = FakeRadio()
        radio.responder = responderFor(radio, me)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        radio.push(rxLogPush(advertPacket(signedAdvert(me, "MyNode"))))
        val echoes = withTimeout(5_000) { engine.heardRepeats.first { it.isNotEmpty() } }

        assertEquals(1, echoes.size)
        assertEquals("b389c985", echoes.single().pathHex)
        assertEquals(2, echoes.single().hashWidth)

        val relays = HeardRepeats.tally(echoes)
        assertEquals(listOf("b389", "c985"), relays.map { it.hashHex }.sorted())
        // b389 is hop 0: it pulled our transmission out of the air.
        val first = relays.single { it.hashHex == "b389" }
        assertTrue(first.heardUs)
        assertFalse(first.reachedUs)
        // c985 transmitted the copy we demodulated, so the SNR is its
        // link to us and nobody else's.
        val last = relays.single { it.hashHex == "c985" }
        assertTrue(last.reachedUs)
        assertEquals(10.0, last.bestSnr)   // rxLogPush encodes snr*4 = 40
        assertNull(first.bestSnr)
    }

    @Test
    fun someoneElsesAdvertIsNotOurTraffic() = runTest {
        val me = MeshIdentity.fromSeed(crypto, ByteArray(32) { 7 })
        val them = MeshIdentity.fromSeed(crypto, ByteArray(32) { 9 })
        val radio = FakeRadio()
        radio.responder = responderFor(radio, me)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        radio.push(rxLogPush(advertPacket(signedAdvert(them, "Someone"))))
        // It is a perfectly good advert — it just isn't ours, so it goes
        // to the discovery inbox and not to the coverage list.
        withTimeout(5_000) { engine.meshEvents.first { it is MeshEvent.VerifiedAdvertHeard } }
        assertTrue(engine.heardRepeats.value.isEmpty())
    }

    @Test
    fun aForgedAdvertClaimingOurKeyIsNotCounted() = runTest {
        // The attack this feature would otherwise invite: anyone in
        // range could inflate — or invent — the list of repeaters
        // "carrying your traffic" by transmitting an advert with your
        // public key pasted in. The signature is over the key, so it
        // does not verify, and a packet that does not verify never
        // reaches the tally.
        val me = MeshIdentity.fromSeed(crypto, ByteArray(32) { 7 })
        val them = MeshIdentity.fromSeed(crypto, ByteArray(32) { 9 })
        val radio = FakeRadio()
        radio.responder = responderFor(radio, me)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val forged = signedAdvert(them, "Someone").copyOf()
        me.publicKey.copyInto(forged, 0)   // claim to be us; signature now wrong
        radio.push(rxLogPush(advertPacket(forged)))
        yield(); yield(); yield()

        assertTrue(engine.heardRepeats.value.isEmpty(), "a forged advert reached the tally")
    }

    @Test
    fun aRelayedAdvertWithNoPathProvesNothing() = runTest {
        // We cannot hear our own transmission, so a path-less copy of
        // our advert is not one repeater — it is zero, and recording it
        // would put a row on the screen for a relay that does not exist.
        val me = MeshIdentity.fromSeed(crypto, ByteArray(32) { 7 })
        val radio = FakeRadio()
        radio.responder = responderFor(radio, me)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        radio.push(rxLogPush(advertPacket(signedAdvert(me, "MyNode"), path = ByteArray(0))))
        yield(); yield(); yield()

        assertTrue(engine.heardRepeats.value.isEmpty())
    }

    // ------------------------------------------------------------------
    // Direct-message repeats, pinned to a live capture (2026-08-06)
    //
    // A DM sent to Kaylee was re-broadcast by two nodes and both copies
    // came back. Verbatim from the phone's log:
    //
    //   RX TXT_MSG route=1 hops=1 path=f0b3 dest=10 src=227 self=227
    //   RX TXT_MSG route=1 hops=1 path=b389 dest=10 src=227 self=227
    //
    // route=1 is ROUTE_TYPE_FLOOD, dest=10 is 0x0a (the recipient's
    // first key byte) and src=self=227 is ours. Pinning the real bytes
    // rather than a property: the DM half shipped twice looking correct
    // and doing nothing, and both times a property-shaped test passed.
    // ------------------------------------------------------------------

    /** A TXT_MSG packet carrying OUR src_hash — our own message echoed. */
    private fun ownDmEcho(
        destHash: Int,
        path: ByteArray,
        width: Int = 2,
        routeType: Int = 0x01,
    ): ByteArray {
        val header = (Codes.PAYLOAD_TYPE_TXT_MSG shl 2) or routeType
        val hops = path.size / width
        // dest_hash, src_hash (ours), 2-byte MAC, opaque ciphertext.
        val payload = byteArrayOf(destHash.toByte(), selfKey[0], 0x11, 0x22) +
            ByteArray(16) { 0x5A }
        return byteArrayOf(header.toByte(), pathLenEnc(hops, width).toByte()) + path + payload
    }

    @Test
    fun ourOwnDirectMessageComingBackIsReportedAsARepeat() = runTest {
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val waiter = async {
            withTimeout(5_000) {
                engine.meshEvents.first { it is MeshEvent.OwnDirectRepeatHeard }
            }
        }
        yield()
        radio.push(rxLogPush(ownDmEcho(0x0a, byteArrayOf(0xf0.toByte(), 0xb3.toByte()))))

        val event = waiter.await() as MeshEvent.OwnDirectRepeatHeard
        assertEquals(0x0a, event.destHash)
        assertEquals("f0b3", event.pathHex)
        assertEquals(2, event.hashWidth)
    }

    @Test
    fun someoneElsesDirectMessageIsNotOurTraffic() = runTest {
        // src_hash is theirs, so this is an ordinary inbound packet. It
        // must stay a HeardVia arrival and never become a repeat of ours
        // — the failure would credit our coverage with their traffic.
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val seen = ArrayList<MeshEvent>()
        val job = launch { engine.meshEvents.collect { seen.add(it) } }
        yield()
        radio.push(rxLogPush(encryptedDmPacket(srcHash = peerKey[0].toInt() and 0xFF)))
        yield(); yield(); yield()
        job.cancel()

        assertTrue(
            seen.none { it is MeshEvent.OwnDirectRepeatHeard },
            "someone else's message was credited as our own traffic",
        )
    }

    @Test
    fun anEchoWithNoPathIsNotARepeat() = runTest {
        // Zero hops means nobody relayed it — we cannot hear our own
        // transmission, so this is not evidence of a repeat.
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()

        val seen = ArrayList<MeshEvent>()
        val job = launch { engine.meshEvents.collect { seen.add(it) } }
        yield()
        radio.push(rxLogPush(ownDmEcho(0x0a, ByteArray(0))))
        yield(); yield(); yield()
        job.cancel()

        assertTrue(seen.none { it is MeshEvent.OwnDirectRepeatHeard })
    }

    // ------------------------------------------------------------------
    // Path recovery — what "sign out and sign back in" was really doing
    // ------------------------------------------------------------------

    private fun binarySentFrame(tag: Long, estTimeoutMs: Long = 3_000L): ByteArray =
        BufferWriter().apply {
            writeByte(Codes.RESP_CODE_SENT)
            writeByte(0)
            writeUInt32LE(tag)
            writeUInt32LE(estTimeoutMs)
        }.toBytes()

    private fun binaryResponseFrame(tag: Long, body: ByteArray): ByteArray =
        BufferWriter().apply {
            writeByte(Codes.PUSH_CODE_BINARY_RESPONSE)
            writeByte(0)   // reserved
            writeUInt32LE(tag)
            writeBytes(body)
        }.toBytes()

    /** One ACL row: 6-byte key prefix then the permissions byte. */
    private fun aclEntry(permissions: Int): ByteArray =
        ByteArray(AccessList.KEY_PREFIX_BYTES) { 0x11 } + byteArrayOf(permissions.toByte())

    /** The password carried by a CMD_SEND_LOGIN frame the fake received. */
    private fun loginPasswordOf(frame: ByteArray): String {
        val start = 1 + 32
        val end = frame.indexOfFirst(start) { it == 0.toByte() }
        return frame.copyOfRange(start, end).decodeToString()
    }

    private inline fun ByteArray.indexOfFirst(from: Int, pred: (Byte) -> Boolean): Int {
        for (i in from until size) if (pred(this[i])) return i
        return size
    }

    /**
     * Answers binary requests only after [failFirst] of them have gone
     * unanswered, so a test can stage "the route was dead, then it was
     * not" without any notion of time.
     */
    private fun flakyBinaryResponder(
        radio: FakeRadio,
        failFirst: Int,
        body: ByteArray,
        answerLogin: Boolean = true,
    ): (ByteArray) -> List<ByteArray> {
        val base = standardResponder(radio)
        var seen = 0
        val tag = 0x5150A11EL
        return { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_BINARY_REQ ->
                    if (seen++ < failFirst) {
                        listOf(binarySentFrame(tag))   // accepted, never answered
                    } else {
                        listOf(binarySentFrame(tag), binaryResponseFrame(tag, body))
                    }
                Codes.CMD_SEND_LOGIN ->
                    if (answerLogin) listOf(loginSentFrame(), loginSuccessPush())
                    else listOf(loginSentFrame())      // accepted, never answered
                else -> base(frame)
            }
        }
    }

    private suspend fun TestScope.readyEngine(radio: FakeRadio): MeshCoreEngine {
        val engine = MeshCoreEngine(backgroundScope, crypto, { now })
        engine.attach(radio)
        radio.connect()
        engine.awaitReady()
        return engine
    }

    @Test
    fun anAdminRequestThatAnswersNeverTouchesTheRoute() = runTest {
        // The control. Recovery that fires on a healthy request would
        // put a flood login on the air every time you opened a screen.
        val radio = FakeRadio()
        radio.responder = flakyBinaryResponder(radio, failFirst = 0, body = aclEntry(3))
        val engine = readyEngine(radio)

        val acl = engine.withPathRecovery(peerKey, "hunter2") {
            engine.requestAccessList(peerKey)
        }

        assertEquals(1, acl?.size)
        assertEquals(0, radio.countOf(Codes.CMD_RESET_PATH))
        assertEquals(0, radio.countOf(Codes.CMD_SEND_LOGIN))
    }

    @Test
    fun anUnansweredRequestResetsTheRouteAndProbesWithABlankPassword() = runTest {
        // The whole feature. The repeater caches the way back to us and
        // answers down it; when that path dies the request still
        // arrives and the reply goes nowhere. Clearing OUR path forces
        // the login to flood, and a flooded login is what makes the
        // repeater drop its own stale out_path.
        val radio = FakeRadio()
        radio.responder = flakyBinaryResponder(radio, failFirst = 1, body = aclEntry(3))
        val engine = readyEngine(radio)

        val acl = engine.withPathRecovery(peerKey, "hunter2") {
            engine.requestAccessList(peerKey)
        }

        assertEquals(1, acl?.size, "the retry after the repair must succeed")
        assertEquals(1, radio.countOf(Codes.CMD_RESET_PATH))

        val logins = radio.sentFrames.filter { (it[0].toInt() and 0xFF) == Codes.CMD_SEND_LOGIN }
        assertEquals(1, logins.size)
        assertEquals(
            "",
            loginPasswordOf(logins[0]),
            "the free probe must not put the password on the air",
        )
    }

    @Test
    fun theStoredPasswordIsOnlySpentAfterTheFreeProbeGoesUnanswered() = runTest {
        // Ordering is the security property: CMD_SEND_LOGIN carries the
        // password in cleartext, so the repair that costs nothing has to
        // be tried first. Here nothing ever answers, so recovery walks
        // the whole ladder and we can read the order off the wire.
        val radio = FakeRadio()
        radio.responder =
            flakyBinaryResponder(radio, failFirst = 99, body = aclEntry(3), answerLogin = false)
        val engine = readyEngine(radio)

        val acl = engine.withPathRecovery(peerKey, "hunter2") {
            engine.requestAccessList(peerKey)
        }
        assertNull(acl)

        val passwords = radio.sentFrames
            .filter { (it[0].toInt() and 0xFF) == Codes.CMD_SEND_LOGIN }
            .map { loginPasswordOf(it) }
        assertTrue(passwords.isNotEmpty(), "expected at least the probe")
        assertEquals("", passwords.first(), "the blank probe must come first")
        assertTrue(
            passwords.drop(1).all { it == "hunter2" },
            "after the probe, only the real credential: $passwords",
        )
    }

    @Test
    fun recoveryWithNoStoredPasswordStopsAfterTheProbe() = runTest {
        // Without a credential there is nothing to escalate to, and
        // attempting it anyway would cost the user another full timeout
        // to arrive at the same answer.
        val radio = FakeRadio()
        radio.responder =
            flakyBinaryResponder(radio, failFirst = 99, body = aclEntry(3), answerLogin = false)
        val engine = readyEngine(radio)

        assertNull(
            engine.withPathRecovery(peerKey, password = null) {
                engine.requestAccessList(peerKey)
            },
        )
        assertEquals(1, radio.countOf(Codes.CMD_SEND_LOGIN), "probe only")
        assertEquals(1, radio.countOf(Codes.CMD_RESET_PATH))
    }

    @Test
    fun recoveryGivesUpInsteadOfLoopingForever() = runTest {
        // A repeater that is simply gone must not turn into an app that
        // floods logins at it until the battery dies. PathRecovery only
        // moves forwards, so the request is sent a bounded number of
        // times: the first attempt plus one after each repair.
        val radio = FakeRadio()
        radio.responder =
            flakyBinaryResponder(radio, failFirst = 99, body = aclEntry(3), answerLogin = true)
        val engine = readyEngine(radio)

        assertNull(
            engine.withPathRecovery(peerKey, "hunter2") { engine.requestAccessList(peerKey) },
        )
        assertEquals(
            PathRecovery.requestAttempts(hasPassword = true),
            radio.countOf(Codes.CMD_SEND_BINARY_REQ),
        )
    }

    @Test
    fun aFailedRepairEscalatesWithoutResendingTheRequest() = runTest {
        // If the probe itself goes unanswered the route was NOT fixed,
        // so re-sending the request would spend a second 30-second
        // timeout proving what we already know.
        val radio = FakeRadio()
        radio.responder =
            flakyBinaryResponder(radio, failFirst = 99, body = aclEntry(3), answerLogin = false)
        val engine = readyEngine(radio)

        engine.withPathRecovery(peerKey, "hunter2") { engine.requestAccessList(peerKey) }

        // Probe unanswered, re-auth unanswered: both repairs failed, so
        // only the original request was ever sent.
        assertEquals(1, radio.countOf(Codes.CMD_SEND_BINARY_REQ))
    }

    @Test
    fun theAccessListIgnoresAReplyTaggedForSomeoneElse() = runTest {
        // requestRegions has correlated by tag since it was written;
        // the ACL and neighbour requests took the first BinaryResponse
        // to arrive. Three of four agreeing and one not is this
        // project's recurring shape, and a mis-taken reply here parses
        // as a DIFFERENT node's access list — a security surface that
        // would look entirely plausible on screen.
        val radio = FakeRadio()
        val tag = 0x0BADCAFEL
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_BINARY_REQ -> listOf(
                    binarySentFrame(tag),
                    binaryResponseFrame(0xDEADBEEFL, aclEntry(3) + aclEntry(3)),
                    binaryResponseFrame(tag, aclEntry(2)),
                )
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = readyEngine(radio)

        val acl = engine.requestAccessList(peerKey)
        assertEquals(1, acl?.size, "took the decoy: ${acl?.size} entries")
        assertEquals(AccessList.ROLE_READ_WRITE, acl?.first()?.role)
    }

    @Test
    fun neighboursAlsoCorrelateByTag() = runTest {
        val radio = FakeRadio()
        val tag = 0x11223344L
        radio.responder = { frame ->
            when (frame[0].toInt() and 0xFF) {
                Codes.CMD_SEND_BINARY_REQ -> listOf(
                    binarySentFrame(tag),
                    binaryResponseFrame(0x99999999L, ByteArray(64) { 0x7F }),
                    // total=0, count=0 — answered, knows nobody.
                    binaryResponseFrame(tag, byteArrayOf(0, 0, 0, 0)),
                )
                else -> standardResponder(radio)(frame)
            }
        }
        val engine = readyEngine(radio)

        // The tagged reply is an empty table: answered, knows nobody.
        // The decoy would have parsed as a table full of nonsense.
        val table = engine.requestNeighbours(peerKey)
        assertNotNull(table)
        assertTrue(table.entries.isEmpty())
    }

    // ------------------------------------------------------------------
    // Advert-QR import — the `meshcore://<hex>` form other clients emit
    // ------------------------------------------------------------------

    @Test
    fun anExportedContactBlobIsAPacketAndItsPayloadIsWhatGetsVerified() {
        // The defect. CMD_EXPORT_CONTACT returns a whole packet —
        // self-export is pkt->writeTo(), contact-export is
        // getBlobByKey(), commented "retrieve last raw advert packet" —
        // so the payload starts after the header, path_len and path.
        // Verifying the blob itself reads the HEADER byte as the first
        // byte of the public key, which can never verify.
        val them = MeshIdentity.generate(crypto)
        val payload = signedAdvert(them, "Someone")
        val blob = advertPacket(payload)

        val extracted = MeshCoreEngine.extractAdvertPayload(blob)
        assertNotNull(extracted)
        assertContentEquals(payload, extracted, "must return the payload, not the packet")
        assertTrue(Advert.verifySignature(crypto, extracted))

        // And the guard: the old behaviour, pinned as broken so it
        // cannot come back looking reasonable.
        assertFalse(
            Advert.verifySignature(crypto, blob),
            "the packet must NOT verify as a payload — if it does, this test proves nothing",
        )
    }

    @Test
    fun aZeroHopAdvertPacketWorksToo() {
        // The self-export case: no path at all, so the payload starts at
        // offset 2. An off-by-one here would pass the two-hop test above
        // and fail on the commonest code in circulation.
        val them = MeshIdentity.generate(crypto)
        val payload = signedAdvert(them, "Direct")
        val extracted = MeshCoreEngine.extractAdvertPayload(
            advertPacket(payload, path = ByteArray(0), width = 2),
        )
        assertContentEquals(payload, extracted)
    }

    @Test
    fun aNonAdvertPacketIsRefused() {
        // The firmware's own importContact() requires PAYLOAD_TYPE_ADVERT
        // and we should not hand it anything else — a text-message packet
        // whose bytes happen to be long enough is not a contact.
        val them = MeshIdentity.generate(crypto)
        val payload = signedAdvert(them, "Someone")
        val header = (Codes.PAYLOAD_TYPE_TXT_MSG shl 2) or 0x01
        val notAnAdvert = byteArrayOf(header.toByte(), 0) + payload
        assertNull(MeshCoreEngine.extractAdvertPayload(notAnAdvert))
    }

    @Test
    fun truncatedAndUndersizedBlobsAreRefusedNotThrown() {
        // Scanned QR data is attacker-controlled; every one of these
        // must produce null rather than an exception or a short read.
        assertNull(MeshCoreEngine.extractAdvertPayload(ByteArray(0)))
        assertNull(MeshCoreEngine.extractAdvertPayload(byteArrayOf(0x11)))
        // Well-formed header, payload one byte short of a signature.
        val header = (Codes.PAYLOAD_TYPE_ADVERT shl 2) or 0x01
        assertNull(
            MeshCoreEngine.extractAdvertPayload(
                byteArrayOf(header.toByte(), 0) + ByteArray(99),
            ),
        )
        // Claims more hops than it carries.
        assertNull(
            MeshCoreEngine.extractAdvertPayload(byteArrayOf(header.toByte(), 0x3F) + ByteArray(8)),
        )
    }

    @Test
    fun scanningAnAdvertUriImportsTheContact() = runTest {
        // End to end, the way another client's QR arrives: hex of a raw
        // advert packet behind the bare `meshcore://` scheme. This is
        // the path that had no encoder pointed at it and so was never
        // exercised — ShareUri.encodeAdvert has no callers.
        val radio = FakeRadio()
        radio.responder = standardResponder(radio)
        val engine = readyEngine(radio)

        val them = MeshIdentity.generate(crypto)
        val blob = advertPacket(signedAdvert(them, "Someone"))
        val decoded = ShareUri.decode(ShareUri.encodeAdvert(blob))
        assertTrue(decoded is ShareUri.Decoded.Advert, "decoded as $decoded")

        assertTrue(engine.importContact((decoded as ShareUri.Decoded.Advert).blob))

        // The RADIO must receive the packet, not the payload: the
        // firmware's importContact() parses it with readFrom().
        val sent = radio.sentFrames.last { (it[0].toInt() and 0xFF) == Codes.CMD_IMPORT_CONTACT }
        assertContentEquals(blob, sent.copyOfRange(1, sent.size))
    }

    @Test
    fun aForgedAdvertUriIsStillRejected() {
        // The verification this fix moved must still bite. Flip a byte
        // in the signed region: extraction succeeds, verification does
        // not, and importContact refuses before anything reaches the
        // radio.
        val them = MeshIdentity.generate(crypto)
        val payload = signedAdvert(them, "Someone").copyOf()
        payload[35] = (payload[35] + 1).toByte()   // last byte of the timestamp
        val extracted = MeshCoreEngine.extractAdvertPayload(advertPacket(payload))
        assertNotNull(extracted)
        assertFalse(Advert.verifySignature(crypto, extracted))
    }
}
