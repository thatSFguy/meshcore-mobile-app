package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The changelog exists twice on purpose and must not disagree with
 * itself.
 *
 * `CHANGELOG.md` is what the release workflow turns into GitHub release
 * notes. `AboutSection.CHANGELOG` is what the app shows in the field,
 * where there may be no network — a changelog behind a link is one you
 * cannot read off-grid, which is the whole premise of this app.
 *
 * Two copies drift. This one already did: 0.5.2 shipped a fix that was
 * never written into the in-app list, so the app skipped straight from
 * 0.5.1 to 0.5.3 and a released version documented nothing. Earlier,
 * 0.3.0 was credited with four features that landed *after* its tag —
 * LESSONS §18, docs that mislead the author three months later.
 */
class ChangelogTest {

    private val repoRoot = File("..")

    private fun markdownVersions(): List<String> {
        val md = File(repoRoot, "CHANGELOG.md")
        assertTrue("CHANGELOG.md not found at ${md.absolutePath}", md.exists())
        return Regex("""^## (\S+)$""", RegexOption.MULTILINE)
            .findAll(md.readText()).map { it.groupValues[1] }.toList()
    }

    private fun inAppVersions(): List<String> {
        val src = File(
            "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/AboutSection.kt",
        )
        assertTrue("AboutSection.kt not found at ${src.absolutePath}", src.exists())
        val body = src.readText().substringAfter("private val CHANGELOG")
        return Regex(""""(\d+\.\d+\.[\dx]+)" to listOf""")
            .findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `both changelogs list the same versions in the same order`() {
        val md = markdownVersions()
        val app = inAppVersions()
        assertTrue("CHANGELOG.md parsed no versions", md.isNotEmpty())
        assertTrue("in-app changelog parsed no versions", app.isNotEmpty())
        assertEquals(
            "CHANGELOG.md and AboutSection.CHANGELOG disagree — a released version is " +
                "documented in one and not the other",
            md,
            app,
        )
    }

    @Test
    fun `no version is listed twice`() {
        for (versions in listOf(markdownVersions(), inAppVersions())) {
            assertEquals(versions.size, versions.distinct().size)
        }
    }

    @Test
    fun `versions are newest first`() {
        // The release workflow takes the first matching section; an
        // out-of-order file would still build, so nothing else catches
        // this.
        fun key(v: String): List<Int> =
            v.split(".").map { it.toIntOrNull() ?: Int.MAX_VALUE }
        val versions = markdownVersions()
        for (i in 0 until versions.size - 1) {
            val a = key(versions[i])
            val b = key(versions[i + 1])
            assertTrue(
                "${versions[i]} is listed above ${versions[i + 1]}",
                compareLists(a, b) > 0,
            )
        }
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    @Test
    fun `every section has content the release page can show`() {
        // An empty section fails the workflow at tag time, which is a
        // bad moment to find out.
        val text = File(repoRoot, "CHANGELOG.md").readText()
        val sections = text.split(Regex("""^## """, RegexOption.MULTILINE)).drop(1)
        for (section in sections) {
            val version = section.lineSequence().first().trim()
            val body = section.lineSequence().drop(1).joinToString("\n").trim()
            assertTrue("$version has an empty section", body.isNotEmpty())
            assertTrue("$version has no bullet points", body.contains("- "))
        }
    }
}
