package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mesh-settings QR (`meshcore://radio/set`).
 *
 * This is the most dangerous code the app accepts. A contact card at
 * worst adds a row; these four values decide whether a node is on a mesh
 * at all, and which frequency it transmits on — a legal question
 * wherever the person scanning happens to be standing. Nothing in it is
 * authenticated, and it is trivially mintable by anyone with a QR
 * generator.
 *
 * So the tests that matter are the ones where the parser says NO. Ranges
 * are the firmware's, not guesses, and a value outside them cannot be a
 * mesh anyone is on — refusing it means it can never reach a radio,
 * whatever the UI does afterwards.
 */
class ShareUriRadioTest {

    private fun uri(
        v: String = "1",
        name: String = "West%20Michigan",
        freq: String = "906.375",
        bw: String = "250",
        sf: String = "11",
        cr: String = "5",
        hash: String? = "1",
        region: String? = null,
    ) = buildString {
        append("meshcore://radio/set?v=").append(v)
        append("&name=").append(name)
        append("&freq=").append(freq)
        append("&bw=").append(bw)
        append("&sf=").append(sf)
        append("&cr=").append(cr)
        hash?.let { append("&hash=").append(it) }
        region?.let { append("&region=").append(it) }
    }

    private fun decodeOk(text: String): ShareUri.Decoded.RadioConfig {
        val d = ShareUri.decode(text)
        assertTrue(d is ShareUri.Decoded.RadioConfig, "expected RadioConfig, got $d")
        return d
    }

    // --- the happy path ---------------------------------------------------

    @Test
    fun aWellFormedCodeDecodesIntoWireUnits() {
        val c = decodeOk(uri())
        assertEquals("West Michigan", c.name)
        // MHz/kHz in the code, kHz/Hz on the wire — the asymmetry the
        // rest of this codebase keeps having to restate.
        assertEquals(906_375L, c.frequencyKhz)
        assertEquals(250_000L, c.bandwidthHz)
        assertEquals(11, c.spreadingFactor)
        assertEquals(5, c.codingRate)
        assertEquals(1, c.pathHashMode)
        assertEquals(null, c.region)
    }

    @Test
    fun encodeAndDecodeAreInverses() {
        val text = ShareUri.encodeRadio(
            name = "West Michigan",
            frequencyKhz = 906_375,
            bandwidthHz = 250_000,
            spreadingFactor = 11,
            codingRate = 5,
            pathHashMode = 1,
            region = "wmich",
        )
        val c = decodeOk(text)
        assertEquals("West Michigan", c.name)
        assertEquals(906_375L, c.frequencyKhz)
        assertEquals(250_000L, c.bandwidthHz)
        assertEquals(11, c.spreadingFactor)
        assertEquals(5, c.codingRate)
        assertEquals(1, c.pathHashMode)
        assertEquals("wmich", c.region)
    }

    @Test
    fun everyShippedPresetSurvivesTheRoundTrip() {
        // The presets are the realistic corpus: if a real mesh config
        // cannot make the trip through a QR, the format is wrong.
        for (p in RadioPresets.ALL) {
            val c = decodeOk(
                ShareUri.encodeRadio(
                    p.name, p.frequencyKhz, p.bandwidthHz,
                    p.spreadingFactor, p.codingRate, pathHashMode = 1,
                ),
            )
            assertEquals(p.frequencyKhz, c.frequencyKhz, "${p.name} frequency")
            assertEquals(p.bandwidthHz, c.bandwidthHz, "${p.name} bandwidth")
            assertEquals(p.spreadingFactor, c.spreadingFactor, "${p.name} SF")
            assertEquals(p.codingRate, c.codingRate, "${p.name} CR")
        }
    }

    @Test
    fun theCodeCarriesNoTxPowerAndNoKey() {
        // Both were deliberate exclusions. TX power is jurisdictional and
        // hardware-bound; a PSK would make this a secret rather than a
        // config. If either ever appears in the emitted string, that
        // decision was reversed by accident.
        val text = ShareUri.encodeRadio("x", 906_375, 250_000, 11, 5, 1, "r").lowercase()
        for (forbidden in listOf("tx", "power", "dbm", "secret", "psk", "key")) {
            assertTrue(forbidden !in text, "settings code leaked \"$forbidden\": $text")
        }
    }

    // --- refusals ---------------------------------------------------------

    @Test
    fun aFrequencyOutsideTheFirmwaresRangeIsRefused() {
        // 2.4GHz and DC are not meshes; neither is the classic 1000x
        // unit slip (910525 "MHz").
        for (freq in listOf("0", "-906.375", "2500.001", "910525", "299.999")) {
            assertEquals(
                ShareUri.Decoded.Malformed,
                ShareUri.decode(uri(freq = freq)),
                "accepted freq=$freq",
            )
        }
    }

    @Test
    fun aBandwidthThatIsNotALoRaStepIsRefused() {
        // 250 is a step; 240 is somebody's typo. Accepting it would make
        // a node deaf in a way that looks like a coverage problem.
        for (bw in listOf("0", "240", "251", "125.5", "-250", "500000")) {
            assertEquals(
                ShareUri.Decoded.Malformed,
                ShareUri.decode(uri(bw = bw)),
                "accepted bw=$bw",
            )
        }
        // The real steps all pass.
        for (bw in listOf("7.8", "10.4", "15.6", "20.8", "31.25", "62.5", "125", "250", "500")) {
            assertTrue(
                ShareUri.decode(uri(bw = bw)) is ShareUri.Decoded.RadioConfig,
                "refused a real LoRa bandwidth: $bw",
            )
        }
    }

    @Test
    fun spreadingFactorAndCodingRateAreProtocolBounded() {
        for (sf in listOf("4", "13", "0", "-7", "999")) {
            assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(uri(sf = sf)), "sf=$sf")
        }
        for (cr in listOf("4", "9", "0", "-5")) {
            assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(uri(cr = cr)), "cr=$cr")
        }
    }

    @Test
    fun theReservedHashModeIsRefused() {
        // Mode 3 is reserved and the firmware answers ERR_CODE_ILLEGAL_ARG
        // for it — the same value the "4 B" chip used to offer.
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(uri(hash = "3")))
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(uri(hash = "-1")))
        for (hash in listOf("0", "1", "2")) {
            assertTrue(ShareUri.decode(uri(hash = hash)) is ShareUri.Decoded.RadioConfig)
        }
    }

    @Test
    fun anAbsentHashDefaultsRatherThanFailing() {
        // Meshes predate the setting; a code without it is still usable.
        val c = decodeOk(uri(hash = null))
        assertEquals(PathHashMode.MIN_MODE, c.pathHashMode)
    }

    @Test
    fun aNewerVersionIsRefusedAsUnsupportedNotMalformed() {
        // The distinction matters: the code is fine and we are old.
        // Half-understanding it would half-tune a radio.
        assertEquals(ShareUri.Decoded.UnsupportedVersion(2), ShareUri.decode(uri(v = "2")))
        assertEquals(ShareUri.Decoded.UnsupportedVersion(99), ShareUri.decode(uri(v = "99")))
    }

    @Test
    fun aMissingOrNonNumericVersionIsMalformed() {
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(uri(v = "")))
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(uri(v = "one")))
        assertEquals(
            ShareUri.Decoded.Malformed,
            ShareUri.decode("meshcore://radio/set?freq=906.375&bw=250&sf=11&cr=5"),
        )
    }

    @Test
    fun everyMissingFieldIsRefused() {
        // Half a config is worse than none: it would tune three of four
        // parameters and leave the node silently off the mesh.
        val full = "meshcore://radio/set?v=1&name=x&freq=906.375&bw=250&sf=11&cr=5&hash=1"
        for (drop in listOf("freq=906.375", "bw=250", "sf=11", "cr=5")) {
            val without = full.replace("&$drop", "")
            assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(without), "accepted $without")
        }
    }

    @Test
    fun garbageAndTruncationAreRefusedRatherThanHalfParsed() {
        for (bad in listOf(
            "meshcore://radio/set",
            "meshcore://radio/set?",
            "meshcore://radio/set?v=1",
            "meshcore://radio/set?v=1&freq=&bw=&sf=&cr=",
            "meshcore://radio/set?v=1&freq=abc&bw=250&sf=11&cr=5",
            "meshcore://radio/set?v=1&freq=906.375&bw=250&sf=11&cr=5&hash=%ZZ",
        )) {
            val d = ShareUri.decode(bad)
            assertTrue(
                d is ShareUri.Decoded.Malformed,
                "accepted \"$bad\" as $d",
            )
        }
    }

    @Test
    fun aDuplicatedFieldCannotOverrideTheOneAHumanRead() {
        // First occurrence wins, matching the contact-card rule. A code
        // that displays 906.375 in a decoder and applies 433.0 would be
        // the whole attack.
        val c = decodeOk(uri() + "&freq=433.0&sf=7")
        assertEquals(906_375L, c.frequencyKhz)
        assertEquals(11, c.spreadingFactor)
    }

    @Test
    fun anOversizedCodeIsRefusedBeforeParsing() {
        val huge = uri() + "&region=" + "a".repeat(ShareUri.MAX_URI_LENGTH)
        assertEquals(ShareUri.Decoded.TooLarge, ShareUri.decode(huge))
    }

    @Test
    fun theNameAndRegionAreTreatedAsUntrustedDisplayText() {
        // Control characters and newlines out of a QR must not reach a
        // dialog intact.
        val c = decodeOk(uri(name = "a%00b%0Ac", region = "r%09s"))
        assertTrue(c.name.none { it < ' ' }, "control chars survived in ${c.name}")
        assertTrue(c.region!!.none { it < ' ' }, "control chars survived in ${c.region}")
    }

    // --- it must not collide with the codes already in circulation --------

    @Test
    fun aSettingsCodeIsNotMistakenForAContactOrChannel() {
        assertTrue(ShareUri.decode(uri()) is ShareUri.Decoded.RadioConfig)
        // And the existing forms still decode as themselves.
        val contact = ShareUri.encodeContact("Bob", "aa".repeat(32), Codes.ADV_TYPE_CHAT)
        assertTrue(ShareUri.decode(contact) is ShareUri.Decoded.Contact)
        val channel = ShareUri.encodeChannel("Public", "bb".repeat(16))
        assertTrue(ShareUri.decode(channel) is ShareUri.Decoded.ChannelShare)
    }

    @Test
    fun theScannerClassifiesItAsAMeshCoreUri() {
        // Otherwise it lands in the community-JSON decoder and reports
        // "invalid community code" for a perfectly good settings QR —
        // exactly the bug ScannedCode was created to stop.
        assertEquals(ScannedCode.MeshCoreUri, ScannedCode.classify(uri()))
    }

    @Test
    fun theSummaryNamesEveryFieldItApplies() {
        // The dialog is the only thing standing between a scanned code
        // and a retuned radio, so it has to show what will change.
        val c = decodeOk(uri(region = "wmich"))
        val s = c.summary()
        for (expected in listOf("906.375", "250", "SF11", "CR4/5", "wmich")) {
            assertTrue(expected in s, "summary omitted $expected: $s")
        }
    }
}
