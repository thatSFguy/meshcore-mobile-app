package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link-budget estimate in the settings-QR generator.
 *
 * The generator is a single self-contained HTML file with no test
 * runner of its own, so this reaches into it from the JVM suite. Two
 * different things are pinned here, and only one of them is arithmetic.
 *
 * **The constants are the facts.** Sensitivity is a textbook model —
 * kTB, plus the receiver's noise figure, plus the spreading factor's
 * demodulator SNR limit — and everything interesting lives in those
 * numbers. Recomputed here from the values the page actually carries,
 * the model has to land on the SX1262 datasheet's typical figures; if
 * someone edits the table, the datasheet check is what notices.
 *
 * **The separation is the safety property.** Transmit power and antenna
 * gain describe the person holding the radio, not the mesh. The page
 * already says so about the QR code, and the moment those fields exist
 * as inputs there are three new ways for them to leak: into the URI,
 * into a saved or exported setting, and into the caption drawn onto a
 * printed code. Any of those publishes one operator's antenna as though
 * it were everyone's configuration. That cannot be caught by reading the
 * page, because it looks right — the number is correct, it is simply not
 * the reader's number.
 */
class SettingsQrLinkBudgetTest {

    private val html: String by lazy {
        val f = File("../docs/settings-qr/index.html")
        assertTrue("settings-qr/index.html not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** The `{ 5: -2.5, … }` table as the page carries it. */
    private fun snrTable(): Map<Int, Double> {
        val body = Regex("""const SNR_MIN_DB = \{([^}]*)\}""").find(html)?.groupValues?.get(1)
        assertTrue("SNR_MIN_DB table not found", body != null)
        return Regex("""(\d+):\s*(-?[\d.]+)""").findAll(body!!)
            .associate { it.groupValues[1].toInt() to it.groupValues[2].toDouble() }
    }

    private fun constant(name: String): Double {
        val m = Regex("""const $name = (-?[\d.]+)""").find(html)
        assertTrue("$name not found", m != null)
        return m!!.groupValues[1].toDouble()
    }

    private fun sensitivity(sf: Int, bwHz: Double): Double =
        constant("THERMAL_NOISE_DBM_HZ") + 10 * log10(bwHz) +
            constant("NOISE_FIGURE_DB") + snrTable().getValue(sf)

    @Test
    fun theSnrTableCoversEverySpreadingFactorTheFormOffers() {
        // The form populates SF5..SF12. A missing key is not a visible
        // failure — it is `undefined`, which makes every derived number
        // NaN and the whole table read "NaN dBm".
        val table = snrTable()
        for (sf in 5..12) {
            assertTrue("SNR_MIN_DB has no entry for SF$sf", table.containsKey(sf))
        }
        assertTrue(
            "the form must still offer SF5..SF12",
            html.contains("for (let sf = 5; sf <= 12; sf++)"),
        )
    }

    @Test
    fun theModelReproducesTheDatasheet() {
        // The pinned values: SX1262 typical LoRa sensitivity at 125 kHz.
        // These are the ground truth the model is claiming to match, and
        // the page says so in its own comment. Tolerance is 0.1 dB —
        // tight enough that changing the noise figure by 1 dB, or any
        // SNR entry by a step, fails here.
        val expected = mapOf(
            7 to -124.5, 8 to -127.0, 9 to -129.5,
            10 to -132.0, 11 to -134.5, 12 to -137.0,
        )
        for ((sf, dbm) in expected) {
            assertEquals("SF$sf at 125 kHz", dbm, sensitivity(sf, 125_000.0), 0.1)
        }
    }

    @Test
    fun sensitivityFollowsBandwidthAndSpreadingFactor() {
        // The two rules of thumb the page tells the reader to use.
        // They are stated as advice in prose, so they had better be true
        // of the numbers in the table beside them.
        assertEquals(
            "halving the bandwidth must be worth 3 dB",
            3.0,
            sensitivity(9, 250_000.0) - sensitivity(9, 125_000.0),
            0.05,
        )
        for (sf in 6..12) {
            assertEquals(
                "each SF step must be worth 2.5 dB (SF$sf)",
                2.5,
                sensitivity(sf - 1, 125_000.0) - sensitivity(sf, 125_000.0),
                0.001,
            )
        }
    }

    @Test
    fun aWorkedBudgetComesOutWhereItShould() {
        // 22 dBm, 2.15 dBi each end, no cable loss — the page's own
        // defaults — on the Rural preset's SF11 at 250 kHz.
        val sens = sensitivity(11, 250_000.0)
        assertEquals("sensitivity", -131.5, sens, 0.1)
        assertEquals("max path loss", 157.8, 22.0 + 2.15 + 2.15 - 0.0 - sens, 0.1)
    }

    @Test
    fun yourAntennaNeverReachesTheCode() {
        // currentConfig() is what gets encoded, saved and exported. If a
        // link-budget field appears in it, one operator's hardware ships
        // inside a code meant to be printed and handed to strangers.
        val config = html.substringAfter("function currentConfig()").substringBefore("}")
        val uri = html.substringAfter("function currentUri()").substringBefore("\n}")
        val caption = html.substringAfter("function captionLines()").substringBefore("\n}")
        for (field in listOf("txp", "gtx", "grx", "loss")) {
            assertFalse("$field leaked into currentConfig()", config.contains("\"$field\""))
            assertFalse("$field leaked into the URI", uri.contains("$field="))
            assertFalse("$field leaked into the printed caption", caption.contains("\"$field\""))
        }
        // The same for the node: "Apply over USB" writes `set radio`,
        // and transmit power is a separate `set tx.power` that this page
        // has no business sending — it is the legal limit where the node
        // stands, not a property of the mesh.
        val usb = html.substringAfter("""$("usb-apply").onclick""").substringBefore("\n};")
        assertFalse("USB apply must not write transmit power", usb.contains("tx.power"))
        for (field in listOf("txp", "gtx", "grx", "loss")) {
            assertFalse("$field leaked into the USB write", usb.contains("$field"))
        }

        // And the URI keeps carrying exactly the mesh-defining fields.
        for (param in listOf("freq=", "bw=", "sf=", "cr=", "hash=")) {
            assertTrue("the URI stopped carrying $param", uri.contains(param))
        }
    }

    @Test
    fun theFormulaExistsOnlyOnce() {
        // The QR tab's summary line quotes sensitivity too, and the
        // tempting way to write that is to inline the arithmetic where
        // it is displayed. That is the shape of defect this project
        // keeps producing — two halves computing the same thing, both
        // plausible, the suite green — and here it would be invisible,
        // because a wrong number in dBm looks exactly like a right one.
        // Block comments stripped: the formula is written out in prose
        // beside the constant on purpose, and documenting it twice is
        // not the same sin as computing it twice.
        val code = html.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        assertEquals(
            "the thermal-noise term must appear in exactly one function",
            1,
            Regex("""10 \* Math\.log10""").findAll(code).count(),
        )
        assertEquals(
            "and the noise floor constant must be declared once",
            1,
            Regex("""-174""").findAll(code).count(),
        )
        val rate = html.substringAfter("""$("rate").innerHTML""").substringBefore(";")
        assertTrue(
            "the summary line must call the shared function, not recompute",
            rate.contains("sensitivityDbm(c.sf, bwHz)"),
        )
    }

    @Test
    fun theBudgetFieldsHaveTheirOwnStorage() {
        // Separate key, so importing someone else's settings file cannot
        // silently adopt their antenna — and so "Forget all", which
        // clears the saved list, does not wipe your own radio.
        val saved = Regex("""const STORE_KEY = "([^"]+)"""").find(html)!!.groupValues[1]
        val radio = Regex("""const RADIO_KEY = "([^"]+)"""").find(html)!!.groupValues[1]
        assertFalse("the two storage keys collide", saved == radio)
        val clear = html.substringAfter("""$("clear-saved").onclick""").substringBefore("};")
        assertFalse("Forget all must not touch the radio key", clear.contains("RADIO_KEY"))
    }

    @Test
    fun editingYourAntennaDoesNotInvalidateThePreset() {
        // Every input and select is wired to a handler that knocks the
        // preset selector to "Custom…", on the reasoning that a changed
        // field means changed mesh settings. That reasoning stops being
        // true the moment fields exist that are not mesh settings:
        // typing your antenna gain would report that the preset had been
        // edited when nothing shared had moved.
        //
        // The opt-out is keyed on the section, not on a marker class, so
        // a control added to the calculator later cannot forget it.
        val loop = html
            .substringAfter("""for (const el of document.querySelectorAll("input, select"))""")
            .substringBefore("const onEdit")
        assertTrue(
            "calculator controls must be skipped by the preset-invalidating loop",
            loop.contains("""el.closest("#tab-loss")"""),
        )
    }

    @Test
    fun theCalculatorLivesOnItsOwnTab() {
        // It is not part of generating a code, and sitting in the middle
        // of that flow implied it was. Every hardware input, and the
        // view switch, must be inside the section the loop above skips —
        // one of them left outside is invisible in the layout and wrong
        // in behaviour.
        val loss = html.substringAfter("""<section id="tab-loss"""").substringBefore("</section>")
        for (field in listOf("txp", "gtx", "grx", "loss")) {
            assertTrue(
                "#$field must live on the calculator tab",
                loss.contains("""id="$field""""),
            )
        }
        assertTrue("the view switch belongs there too", loss.contains("""name="lbview""""))
        assertTrue("and the table", loss.contains("""id="lb-table""""))

        // The QR tab must not have kept a copy of any of it.
        val qr = html.substringAfter("""<section id="tab-qr"""").substringBefore("</section>")
        assertFalse("the calculator must not also be on the QR tab", qr.contains("""id="txp""""))
        assertTrue("the QR tab still owns the code", qr.contains("""id="qr""""))
    }

    @Test
    fun sensitivityHasNoFrequencyTerm() {
        // The misconception this tab exists to correct. Sensitivity is
        // thermal noise in the channel plus noise figure plus the
        // demodulator's SNR limit — bandwidth and spreading factor only.
        // Frequency changes what survives the path, not what the far end
        // can hear, so a frequency argument here would be a bug wearing
        // the shape of a feature.
        val fn = html.substringAfter("function sensitivityDbm(").substringBefore("\n}")
        assertTrue("must take only sf and bandwidth", fn.startsWith("sf, bwHz)"))
        assertFalse("frequency must not enter sensitivity", fn.contains("freq"))
        assertTrue(
            "and the page must say so, since the question keeps coming up",
            html.contains("Sensitivity does not depend on frequency"),
        )
    }

    @Test
    fun outOfRangeInputIsClampedAndSaidOutLoud() {
        // A blank or absurd field must not render "NaN dB" and must not
        // silently pretend to a number the user did not give.
        val fn = html.substringAfter("function currentRadio()").substringBefore("\n}")
        assertTrue("must clamp", fn.contains("Math.min") && fn.contains("Math.max"))
        assertTrue("must collect what was out of range", fn.contains("bad.push"))
        assertTrue(
            "the page must show that it clamped",
            html.contains("""$("lb-err").textContent"""),
        )
    }

    @Test
    fun thePageRefusesToTurnDecibelsIntoDistance() {
        // Coverage modelling is out of scope by decision (CLAUDE.md): a
        // free-space range at these budgets is thousands of kilometres,
        // and a confident wrong number is worse than none. This test
        // exists so a later "helpful" addition has to argue with it.
        assertTrue(
            "the deliberate omission of range must stay stated",
            html.contains("There is deliberately no distance here"),
        )
        assertFalse(
            "no free-space distance formula",
            html.contains("32.44") || Regex("""\bkm\b""").containsMatchIn(
                html.substringAfter("---------- link budget ----------")
                    .substringBefore("---------- rendering ----------"),
            ),
        )
    }
}
