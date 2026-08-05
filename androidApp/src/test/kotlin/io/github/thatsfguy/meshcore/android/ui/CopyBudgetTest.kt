package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-line budget for inline hints, enforced against the source.
 *
 * LESSONS §14: this app's refusal to overstate is a genuine strength
 * and every one of those caveats was true. What went wrong is that
 * they went on *every row of every screen*, three sentences at a
 * time, until the whole app read like a disclaimer while the app it
 * competes with said nothing and looked clean.
 *
 * The rule that came out of it (REBUILD-PLAYBOOK §6.3) is a budget:
 * one line per control, nuance behind a tap. `HintText` is the
 * one-line control; `ExpandableHint` is the one with the tap. Nothing
 * stops the next person writing a paragraph into `HintText` except
 * this test.
 *
 * Deliberately NOT covered: `Text(...)` inside a dialog. A modal you
 * opened to make an irreversible decision — share a channel key,
 * export secrets, enable plaintext TCP — is exactly where the whole
 * warning belongs, and shortening those would be the opposite
 * mistake.
 */
class CopyBudgetTest {

    private val screens = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens")

    /**
     * Replace `${…}` and `$name` with a short stand-in.
     *
     * The budget is about how much PROSE a user reads, and
     * `${unresolved.joinToString(", ") { (it + 1).toString() }}`
     * renders as about four characters. Measuring the source would
     * fail a perfectly short sentence for having a well-named variable
     * in it, which would push the next person to shorten real copy to
     * satisfy a mismeasurement.
     */
    private fun rendered(literal: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < literal.length) {
            if (literal[i] == '$' && i + 1 < literal.length && literal[i + 1] == '{') {
                var depth = 1
                var j = i + 2
                while (j < literal.length && depth > 0) {
                    when (literal[j]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    j++
                }
                out.append("0000")
                i = j
            } else if (literal[i] == '$') {
                var j = i + 1
                while (j < literal.length && (literal[j].isLetterOrDigit() || literal[j] == '_')) j++
                out.append("0000")
                i = j
            } else {
                out.append(literal[i])
                i++
            }
        }
        return out.toString()
    }

    private fun hintsIn(source: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (true) {
            val at = source.indexOf("HintText(", i)
            if (at < 0) break
            i = at + 9
            // Read the argument list up to the matching close paren.
            var depth = 1
            var j = i
            while (j < source.length && depth > 0) {
                when (source[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            }
            val arg = source.substring(i, (j - 1).coerceAtLeast(i))
            // Only literal arguments — interpolated/computed hints
            // (counts, device names) are not prose and are exempt.
            if (!arg.trimStart().startsWith("\"")) continue
            val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
                .findAll(arg).joinToString("") { it.groupValues[1] }
            if (literal.isNotBlank()) {
                out += source.take(at).count { it == '\n' } + 1 to literal
            }
        }
        return out
    }

    @Test
    fun `no inline hint runs past one line`() {
        val files = screens.listFiles { f: File -> f.name.endsWith(".kt") }
            ?: error("no screen sources at ${screens.absolutePath}")
        assertTrue("expected many screen files, found ${files.size}", files.size > 20)

        var checked = 0
        val over = mutableListOf<String>()
        for (file in files) {
            val source = file.readText()
            for ((line, literal) in hintsIn(source)) {
                checked++
                val text = rendered(literal)
                val sentences = text.split(Regex("(?<=[.!?]) +")).count { it.isNotBlank() }
                if (text.length > 170) {
                    over += "${file.name}:$line is ${text.length} chars — use ExpandableHint"
                }
                if (sentences > 2) {
                    over += "${file.name}:$line is $sentences sentences — use ExpandableHint"
                }
            }
        }
        // A positive control: if the scanner found nothing, it is not
        // proving the budget is respected, it is proving it is broken.
        assertTrue("scanner matched no HintText calls at all", checked > 20)
        assertTrue(over.joinToString("\n"), over.isEmpty())
    }
}
