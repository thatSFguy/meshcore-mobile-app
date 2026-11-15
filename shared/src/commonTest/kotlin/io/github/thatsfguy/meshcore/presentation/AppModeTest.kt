package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.ChannelKind
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppModeTest {

    private val allTiles = listOf(
        "connection", "transports", "pin", "identity", "radio", "clock", "policies",
        "autoadd", "customvars", "firmware", "channels", "blocking", "app", "backup",
        "data", "about",
    )

    @Test
    fun `the full app hides nothing`() {
        // Positive control: every Simple-mode rule below is a removal.
        assertEquals(listOf("chats", "nodes", "map", "settings"), AppModes.tabs(AppMode.Full))
        assertTrue(allTiles.all { AppModes.showsSettingsTile(AppMode.Full, it) })
        assertTrue(AppModes.showsThread(AppMode.Full, true, ChannelKind.Public, null))
        assertTrue(AppModes.showsThread(AppMode.Full, false, null, Codes.ADV_TYPE_REPEATER))
        assertTrue(AppModes.showsSignalDetail(AppMode.Full))
        assertTrue(AppModes.showsTechnicalActions(AppMode.Full))
    }

    @Test
    fun `simple mode keeps chats and settings only`() {
        assertEquals(listOf("chats", "settings"), AppModes.tabs(AppMode.Simple))
    }

    @Test
    fun `simple settings keep getting connected and your own things`() {
        val kept = allTiles.filter { AppModes.showsSettingsTile(AppMode.Simple, it) }
        assertEquals(
            listOf("connection", "identity", "channels", "blocking", "app", "backup", "about"),
            kept,
        )
    }

    @Test
    fun `simple mode hides the Public channel but not the group's`() {
        assertFalse(AppModes.showsThread(AppMode.Simple, true, ChannelKind.Public, null))
        assertTrue(AppModes.showsThread(AppMode.Simple, true, ChannelKind.Private, null))
        assertTrue(AppModes.showsThread(AppMode.Simple, true, ChannelKind.Hashtag, null))
        // A channel whose kind is not known yet is not Public.
        assertTrue(AppModes.showsThread(AppMode.Simple, true, null, null))
    }

    @Test
    fun `simple mode shows people and hides consoles and rooms`() {
        assertTrue(AppModes.showsThread(AppMode.Simple, false, null, Codes.ADV_TYPE_CHAT))
        assertFalse(AppModes.showsThread(AppMode.Simple, false, null, Codes.ADV_TYPE_REPEATER))
        assertFalse(AppModes.showsThread(AppMode.Simple, false, null, Codes.ADV_TYPE_ROOM))
        assertFalse(AppModes.showsThread(AppMode.Simple, false, null, Codes.ADV_TYPE_SENSOR))
        // Someone not yet classified is shown rather than silently hidden.
        assertTrue(AppModes.showsThread(AppMode.Simple, false, null, null))
    }

    @Test
    fun `simple mode drops signal detail and technical actions`() {
        assertFalse(AppModes.showsSignalDetail(AppMode.Simple))
        assertFalse(AppModes.showsTechnicalActions(AppMode.Simple))
    }

    @Test
    fun `an unknown stored mode is the full app`() {
        assertEquals(AppMode.Full, AppMode.of(null))
        assertEquals(AppMode.Full, AppMode.of("simple"))
        assertEquals(AppMode.Simple, AppMode.of("Simple"))
    }
}
