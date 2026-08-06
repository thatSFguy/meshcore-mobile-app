package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `shared` must not use JVM-only APIs.
 *
 * This is the guard for the defect that killed the iOS build. A single
 * `toSortedMap()` in `ConfigBackup.kt` — `java.util`, JVM-only — sat in
 * `commonMain` for weeks. It compiled for Android, it read as ordinary
 * Kotlin, and it could never compile for Native. Nobody noticed because
 * **nothing in this repo builds `commonMain` for a non-JVM target**, so
 * the compiler that would have caught it was never run.
 *
 * Restoring iOS CI is the real fix and this is not a substitute for it.
 * But CI needs a macOS runner and a working iOS target, and this needs
 * neither, so it is what stands between the two.
 *
 * If you are adding an API here, the question is not "does Kotlin have
 * it" but "does Kotlin/Native have it". `String.format`, `java.*`,
 * `Locale` and `SimpleDateFormat` do not — use `util/Format.kt` and
 * `kotlin.time` instead.
 */
class SharedIsPlatformNeutralTest {

    private val banned = listOf(
        "java." to "java.* is JVM-only",
        "javax." to "javax.* is JVM-only",
        "toSortedMap(" to "toSortedMap is java.util — use entries.sortedBy { it.key }",
        "toSortedSet(" to "toSortedSet is java.util",
        ".format(" to "String.format is JVM-only — use util/Format.kt's fixed()/hexPadded()",
        "SimpleDateFormat" to "SimpleDateFormat is JVM-only",
        "System.currentTimeMillis" to "use the injected clock, not the JVM one",
        // java.lang is imported implicitly on the JVM, so these appear
        // with NO `java.` prefix and read as ordinary Kotlin. That is
        // how Character.digit reached commonMain and broke the first
        // iOS CI run after the presentation models moved here.
        "Character." to "java.lang.Character — use Char.digitToIntOrNull / Char methods",
        "Integer." to "java.lang.Integer — use Int / toIntOrNull",
        "Math." to "java.lang.Math — use kotlin.math",
        "Thread." to "java.lang.Thread — use coroutines",
        "StringBuffer" to "java.lang.StringBuffer — use StringBuilder",
        "Arrays." to "java.util.Arrays — use Kotlin collection APIs",
        "Collections." to "java.util.Collections — use Kotlin collection APIs",
    )

    private fun sourcesUnder(dir: String): List<File> {
        val root = File("../shared/src/$dir")
        assertTrue(root.isDirectory, "not found: ${root.absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Strip comments so a rule may still be *described* in prose. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `commonMain uses no JVM-only APIs`() {
        val offences = mutableListOf<String>()
        for (file in sourcesUnder("commonMain")) {
            val body = code(file.readText())
            for ((needle, why) in banned) {
                if (body.contains(needle)) offences += "${file.name}: '$needle' — $why"
            }
        }
        assertTrue(offences.isEmpty(), "JVM-only API in shared/commonMain:\n" + offences.joinToString("\n"))
    }

    @Test
    fun `commonTest uses no JVM-only APIs either`() {
        // Tests compile for every target too, so a JVM-only helper here
        // fails the iOS build just as surely as one in commonMain.
        val offences = mutableListOf<String>()
        for (file in sourcesUnder("commonTest")) {
            val body = code(file.readText())
            for ((needle, why) in banned) {
                if (body.contains(needle)) offences += "${file.name}: '$needle' — $why"
            }
        }
        assertTrue(offences.isEmpty(), "JVM-only API in shared/commonTest:\n" + offences.joinToString("\n"))
    }

    @Test
    fun `the presentation models really did move out of androidApp`() {
        // The point of the move is that SwiftUI can reach them. If they
        // drift back into androidApp/ui/screens, iOS loses the app's
        // information architecture and its live subtitles all over again.
        val moved = listOf("SettingsHubModel.kt", "RepeaterHubModel.kt", "HeardRepeatsModel.kt")
        for (name in moved) {
            assertTrue(
                File("../shared/src/commonMain/kotlin/io/github/thatsfguy/meshcore/presentation/$name").isFile,
                "$name is not in shared/presentation",
            )
            assertTrue(
                !File("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/$name").exists(),
                "$name came back to androidApp",
            )
        }
    }
}

/**
 * The thread key is built in exactly one place.
 *
 * The reader (`MessageRepository`, deciding whether to badge or buzz)
 * and the writer (`MeshCoreViewModel`, reporting which conversation is
 * on screen) have to agree on the string. They were five hand-written
 * `"$kind|$peerKey"` literals across two files — a value built in
 * several places and updated in one, which is the shape behind six
 * separate defects in this codebase.
 */
class ThreadKeyIsBuiltOnceTest {

    @Test
    fun `nothing hand-builds a thread key`() {
        val offenders = java.io.File("src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("\"\$kind|\$peerKey\"") ||
                Regex("""activeThread\s*[!=]=\s*"\$""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "hand-built thread key in: $offenders — use Inbox.threadKey()",
        )
    }
}
