package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.protocol.BufferWriter
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.transport.IncomingFrame
import io.github.thatsfguy.meshcore.transport.Transport
import io.github.thatsfguy.meshcore.transport.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guards in [MessageRepository] that decide whether a message is
 * stored at all.
 *
 * Every one of these failed silently: no crash, no log, just a row that
 * never appeared. That is the shape worth pinning — a suite that only
 * exercised the happy path passed against all three bugs.
 */
@RunWith(RobolectricTestRunner::class)
class MessageRepositoryGuardsTest {

    private val self = ByteArray(32) { 0x11 }
    private val selfHex = self.joinToString("") { "%02x".format(it) }
    private val peer = ByteArray(32) { (it + 40).toByte() }

    private lateinit var db: MeshCoreDatabase
    private lateinit var prefs: Preferences

    /**
     * The smallest radio that gets the engine to SELF_INFO. Everything
     * after the handshake's first step goes unanswered on purpose — the
     * engine's own timeouts are virtual under runTest, and the only
     * state these tests need is [MeshCoreEngine.selfInfo].
     */
    private class MinimalRadio(private val selfKey: ByteArray) : Transport {
        override val state = MutableStateFlow(TransportState.Disconnected)
        private val _incoming = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 64)
        override val incoming: Flow<IncomingFrame> = _incoming.asSharedFlow()

        override suspend fun connect() { state.value = TransportState.Connected }
        override suspend fun disconnect() { state.value = TransportState.Disconnected }

        override suspend fun send(frame: ByteArray) {
            if ((frame[0].toInt() and 0xFF) == Codes.CMD_APP_START) push(selfInfoFrame(selfKey))
        }

        fun push(frame: ByteArray) {
            check(_incoming.tryEmit(IncomingFrame(frame)))
        }

        private fun selfInfoFrame(pubKey: ByteArray): ByteArray {
            val w = BufferWriter()
            w.writeByte(Codes.RESP_CODE_SELF_INFO)
            w.writeByte(Codes.ADV_TYPE_CHAT)
            w.writeByte(22); w.writeByte(30)
            w.writeBytes(pubKey)
            w.writeInt32LE(0); w.writeInt32LE(0)
            w.writeByte(1); w.writeByte(1); w.writeByte(0); w.writeByte(1)
            w.writeUInt32LE(910_525L); w.writeUInt32LE(250_000L)
            w.writeByte(9); w.writeByte(5)
            w.writeString("MyNode"); w.writeByte(0)
            return w.toBytes()
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        context.getSharedPreferences("meshcore_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = Preferences(context)
    }

    @After
    fun tearDown() = db.close()

    private fun repo(scope: CoroutineScope, vault: SecretVault = WorkingVault()) =
        MessageRepository(db, SecretsRepository(prefs, vault), scope)

    /** Seals by prefixing a marker; enough to prove a round trip. */
    private class WorkingVault : SecretVault {
        override suspend fun seal(plaintext: ByteArray) = byteArrayOf(1) + plaintext
        override suspend fun unseal(sealed: ByteArray) = sealed.copyOfRange(1, sealed.size)
    }

    /** A device whose Keystore rejects every key spec. */
    private class DeadVault : SecretVault {
        override suspend fun seal(plaintext: ByteArray): ByteArray = error("no keystore")
        override suspend fun unseal(sealed: ByteArray): ByteArray = error("no keystore")
    }

    // ------------------------------------------------------------------
    // The self-key race
    // ------------------------------------------------------------------

    @Test
    fun anInboundMessageIsStoredBeforeTheServiceAssignsTheSelfKey() = runTest {
        // MessageRepository.selfKey is written by the SERVICE, from a
        // different coroutine collecting the same flow. handle() used to
        // read that field directly and return when it was still empty,
        // so a message arriving before the service caught up was
        // dropped — not deferred, dropped. resolveSelfKey exists for
        // exactly this and was used by the other three collectors but
        // not by this one.
        //
        // handle() is called DIRECTLY rather than through the engine's
        // event flow: driving it through two collectors and a Room
        // insert on Room's own executor made the assertion race the
        // write, and the test flaked. A test for a race must not have
        // one of its own.
        val radio = MinimalRadio(self)
        val repository = repo(backgroundScope)
        val engine = MeshCoreEngine(backgroundScope, AndroidCryptoProvider(), { 1_700_000_000L })
        engine.attach(radio)
        radio.connect()
        while (engine.selfInfo.value == null) yield()

        // The race, reproduced exactly: the engine knows who we are,
        // the repository field does not.
        assertEquals("", repository.selfKey)
        repository.handle(
            engine,
            MeshEvent.DirectMessageReceived(
                senderPrefixHex = peer.copyOfRange(0, 6).joinToString("") { "%02x".format(it) },
                senderKeyHex = null,
                text = "hello there",
                timestamp = 1_700_000_100L,
                txtType = Codes.TXT_TYPE_PLAIN,
                snr = null,
            ),
        )

        assertEquals(1, db.messages().countAll(selfHex), "the message was dropped, not stored")
        assertEquals(0, db.messages().countAll(""), "and not filed under an empty key")
    }

    @Test
    fun aMessageIsStillStoredOnceTheServiceHasAssignedTheSelfKey() = runTest {
        // Positive control: the fallback must not be the only path that
        // works, or an engine that never reports SELF_INFO would look
        // fine here and store nothing in the field.
        val repository = repo(backgroundScope)
        val engine = MeshCoreEngine(backgroundScope, AndroidCryptoProvider(), { 1_700_000_000L })
        repository.selfKey = selfHex

        repository.handle(
            engine,
            MeshEvent.DirectMessageReceived(
                senderPrefixHex = peer.copyOfRange(0, 6).joinToString("") { "%02x".format(it) },
                senderKeyHex = null,
                text = "second",
                timestamp = 1_700_000_200L,
                txtType = Codes.TXT_TYPE_PLAIN,
                snr = null,
            ),
        )

        assertEquals(1, db.messages().countAll(selfHex))
    }

    // ------------------------------------------------------------------
    // A Keystore that cannot seal
    // ------------------------------------------------------------------

    @Test
    fun aChannelSurvivesAVaultThatCannotSealItsKey() = runTest {
        // `sealPsk(...) ?: return` dropped the whole ROW, not just the
        // cached key — so on a device whose Keystore rejects every spec
        // the channel vanished from Chats entirely. Losing the cached
        // PSK is acceptable (the radio still holds it); losing the
        // channel is not.
        val repository = repo(backgroundScope, DeadVault())
        repository.selfKey = selfHex

        repository.persistChannel(
            selfHex,
            io.github.thatsfguy.meshcore.model.Channel(0, "Public", ByteArray(16) { 7 }),
        )

        val row = db.channels().byIdx(selfHex, 0)
        assertNotNull(row, "the channel row was dropped with the key")
        assertEquals("Public", row.name)
        assertEquals(0, row.pskSealed.size, "nothing may be stored in the clear")
    }

    @Test
    fun aWorkingVaultStillSealsTheKey() {
        // Positive control: the fallback above must not be what happens
        // on a healthy device.
        runTest {
            val repository = repo(backgroundScope)
            repository.persistChannel(
                selfHex,
                io.github.thatsfguy.meshcore.model.Channel(1, "somechannel", ByteArray(16) { 9 }),
            )
            val row = db.channels().byIdx(selfHex, 1)
            assertNotNull(row)
            assertTrue(row.pskSealed.isNotEmpty(), "a working vault must seal")
        }
    }

    @Test
    fun aSecretThatCannotBeSealedIsReportedNotSilentlyDropped() {
        // SecretsRepository returns false rather than throwing, and the
        // callers used to ignore it — which is how "Save password" came
        // to do nothing at all on affected devices.
        runTest {
            val secrets = SecretsRepository(prefs, DeadVault())
            assertEquals(false, secrets.storeLoginPassword("aa".repeat(32), "hunter2"))
            assertEquals(false, secrets.storeCommunitySecret("bb".repeat(32), ByteArray(32)))
            assertNull(secrets.loginPassword("aa".repeat(32)))
            // And nothing reached storage in the clear.
            assertTrue(prefs.sealedKeys("login_").isEmpty())
        }
    }

    // ------------------------------------------------------------------
    // The guard that never fired
    // ------------------------------------------------------------------

    @Test
    fun aRepeatSightingWithNoSelfKeyIsIgnored() {
        // `val self = selfKey ?: return` was elvis on a non-null String,
        // so the guard never fired and the query ran against "".
        runTest {
            val repository = repo(backgroundScope)
            repository.selfKey = ""
            repository.noteDirectRepeat(destHash = 0x11, pathHex = "b389", width = 2)
            assertEquals(0, db.messages().countAll(""))
        }
    }
}
