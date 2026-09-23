package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two pushes the radio uses to say its contact table changed under
 * us. Frames are built the way the firmware writes them
 * (`companion_radio/MyMesh.cpp`, `onContactOverwrite` and
 * `onContactsFull`), not from our own parser's idea of them.
 */
class ContactPushesTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun contactDeletedCarriesTheWholeKey() {
        // out_frame[0] = PUSH_CODE_CONTACT_DELETED;
        // memcpy(&out_frame[1], pub_key, PUB_KEY_SIZE);
        // writeFrame(out_frame, 1 + PUB_KEY_SIZE);
        val frame = byteArrayOf(0x8F.toByte()) + key
        val ev = ResponseParser.parse(frame)
        assertTrue(ev is DeviceEvent.ContactDeleted, "parsed as $ev")
        assertContentEquals(key, ev.publicKey)
        assertTrue(ev.isPush)
    }

    @Test
    fun contactsFullIsTheBareCode() {
        // writeFrame(out_frame, 1) — nothing after the code.
        assertSame(DeviceEvent.ContactsFull, ResponseParser.parse(byteArrayOf(0x90.toByte())))
    }

    @Test
    fun theCodesAreTheFirmwaresNumbers() {
        // #define PUSH_CODE_CONTACT_DELETED 0x8F / PUSH_CODE_CONTACTS_FULL 0x90
        assertEquals(0x8F, Codes.PUSH_CODE_CONTACT_DELETED)
        assertEquals(0x90, Codes.PUSH_CODE_CONTACTS_FULL)
    }

    @Test
    fun aTruncatedDeletionDeletesNothing() {
        // A short key must not become a shorter key that matches — or
        // fails to match — something else. It is Unknown, and the
        // engine never sees a deletion.
        for (len in listOf(0, 1, 6, 31)) {
            val ev = ResponseParser.parse(byteArrayOf(0x8F.toByte()) + key.copyOf(len))
            assertTrue(ev is DeviceEvent.Unknown, "len=$len parsed as $ev")
        }
    }
}
