package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.ShareUri
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
        // And writes normalised values, never the raw fields.
        assertTrue(page.contains("""region: rgDefault >= 0 ? normRegion(rgRows[rgDefault]) : "","""))
        assertTrue(page.contains("""function rgNames() { return rgRows.map(normRegion); }"""))
        assertTrue(page.contains("""encodeURIComponent(c.region)"""))
    }

    @Test
    fun `the page limits a region tree as the app does`() {
        val js = Regex("""const MAX_REGION_TREE = (\d+);""").find(page)
        assertTrue("MAX_REGION_TREE not found in the generator", js != null)
        assertEquals(ShareUri.MAX_REGION_TREE, js!!.groupValues[1].toInt())
        // The parameter the app reads, and `region` kept as the default.
        assertTrue(page.contains("\"&${ShareUri.REGION_TREE_PARAM}=\""))
        assertTrue(page.contains("\"&region=\" + encodeURIComponent(c.region)"))
        // The default must be in the tree, or the app refuses the tree.
        assertTrue(page.contains("c.region && !c.regions.includes(c.region)"))
    }

    @Test
    fun `the page never splits a region name on a space`() {
        // "mid west" must be an error to fix, not two regions nobody meant.
        val split = Regex("""const parseTree = s => String\(s \?\? ""\)\.split\(/\[([^\]]*)\]/\)""").find(page)
        assertTrue("parseTree not found in the generator", split != null)
        assertTrue(" " !in split!!.groupValues[1] && "\\s" !in split.groupValues[1])
    }
}
