package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.protocol.Regions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web generator (docs/settings-qr) and the app must agree on what a
 * region is. The app refuses a code whose region isn't already canonical
 * rather than rewriting it, so a page more lenient than the app would
 * hand out codes whose region silently never applies. There is no JS
 * runtime in the build, so this holds the page's rule to the app's by
 * source.
 */
class SettingsQrRegionParityTest {

    private val page = File("../docs/settings-qr/index.html").readText()

    @Test
    fun `the page validates regions with the app's rule`() {
        val js = Regex("""const REGION_OK = /\^\[a-z0-9-\]\{1,(\d+)\}\$/;""").find(page)
        assertTrue("REGION_OK not found in the generator", js != null)
        assertEquals(Regions.MAX_NAME_LENGTH, js!!.groupValues[1].toInt())
        // The same pattern, checked against the same names.
        val jsRule = Regex("^[a-z0-9-]{1,${js.groupValues[1]}}$")
        for (name in listOf("mi", "mi-west", "grr", "MI", "bay area", "a".repeat(29), "a".repeat(30), "#mi", "")) {
            assertEquals(name, Regions.canonical(name) == name, jsRule.matches(name))
        }
    }

    @Test
    fun `the page normalises as the app canonicalises`() {
        // Strip one '#', trim, lowercase — Regions.canonical's steps.
        assertTrue(page.contains(""".trim().replace(/^#/, "").toLowerCase()"""))
        // And writes the normalised value, never the raw field.
        assertTrue(page.contains("""region: normRegion(${'$'}("region").value)"""))
        assertTrue(page.contains("""encodeURIComponent(c.region)"""))
    }
}
