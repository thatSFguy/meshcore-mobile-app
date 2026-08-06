package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.protocol.CliFormFields
import io.github.thatsfguy.meshcore.protocol.CliIds
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [CliIds.ALL] honest, and keeps bare CLI-id literals out of the
 * settings form.
 *
 * `CliIds.ALL` is hand-maintained — Kotlin cannot enumerate an object's
 * constants without reflection, which commonTest does not have. So the
 * list can fall behind the declarations, and a constant missing from it
 * is a constant nothing checks against the catalogue. This counts the
 * declarations in the source and compares.
 *
 * The second test is the one that matters. Constants only help if they
 * are used: the form previously spelled 26 ids by hand, and a 27th
 * typed tomorrow would reintroduce exactly the drift this removed.
 */
class CliIdsCoverageTest {

    private val idsFile = File(
        "../shared/src/commonMain/kotlin/io/github/thatsfguy/meshcore/protocol/CliIds.kt",
    )
    private val form = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/RemoteSettingsForm.kt",
    )

    @Test
    fun `every declared constant is listed in ALL`() {
        assertTrue("CliIds.kt not found at ${idsFile.absolutePath}", idsFile.exists())
        val body = idsFile.readText().substringAfter("object CliIds {").substringBefore("\n}")
        val declared = Regex("""const val ([A-Z0-9_]+)""").findAll(body).count()
        assertTrue("parsed no constants — has the file's shape changed?", declared > 0)
        assertEquals(
            "a constant is declared but missing from CliIds.ALL, so nothing checks it " +
                "against the catalogue",
            declared,
            CliIds.ALL.size,
        )
    }

    @Test
    fun `the settings form spells no CLI id by hand`() {
        assertTrue("RemoteSettingsForm.kt not found", form.exists())
        val source = form.readText()
        val offenders = Regex(""""([a-z][a-z0-9]*(?:\.[a-z0-9]+)+)"""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filter { it in CliIds.ALL || it in CliFormFields.ALL }
            .distinct()
            .toList()
        assertTrue(
            "these are spelled as bare literals in the form; use CliIds/CliFormFields so " +
                "they cannot drift from the catalogue: $offenders",
            offenders.isEmpty(),
        )
    }
}
