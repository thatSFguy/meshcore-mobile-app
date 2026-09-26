package io.github.thatsfguy.meshcore.presentation

/**
 * The letter in a node's avatar circle.
 *
 * It used to be the name's first non-space CHARACTER. An emoji outside the
 * Basic Multilingual Plane — 🐄, 🐸, most of them — is two characters (a
 * surrogate pair), so a name like "🐄Donk-N8FWG" put half of one in the
 * circle, which renders as "�". Reported 2026-09-25.
 *
 * Now: the first letter or digit, upper-cased, skipping any emoji or
 * symbols in front of it — an initial, which is what the circle is for.
 * A name with no letter or digit at all gets its first emoji, whole, and a
 * name with nothing in it gets "?".
 */
object AvatarGlyph {

    fun of(label: String): String {
        val initial = label.firstOrNull { it.isLetterOrDigit() }
        if (initial != null) return initial.uppercase()
        // No letter or digit: show the first visible thing, keeping a
        // surrogate pair together rather than splitting it.
        var i = 0
        while (i < label.length && label[i].isWhitespace()) i++
        if (i >= label.length) return "?"
        val c = label[i]
        return if (c.isHighSurrogate() && i + 1 < label.length && label[i + 1].isLowSurrogate()) {
            label.substring(i, i + 2)
        } else if (c.isSurrogate()) {
            "?" // an unpaired half cannot be drawn
        } else {
            c.toString()
        }
    }
}
