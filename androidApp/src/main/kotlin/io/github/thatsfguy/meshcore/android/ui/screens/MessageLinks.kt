package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Turns URLs inside a received message into tappable spans.
 *
 * Two kinds are recognised:
 *  - `http(s)://…` — handed to [onHttpLink], NOT straight to the
 *    browser. See below.
 *  - `meshcore://…` — a contact share; handed to [onMeshcoreLink], which
 *    runs it through the same confirmation as a scanned QR.
 *
 * SECURITY: an http link in a mesh message is the one thing in this app
 * that leaves the mesh. The address is chosen by the *sender*, and
 * opening it tells that server the user's real IP, their network, and
 * that they are online right now — from an app whose whole point is that
 * it makes no outbound connections. So a tap must never navigate
 * directly; it raises a confirmation that says what the user is about to
 * give away. Same reasoning as the sibling Reticulum client's audit
 * finding L8.
 */
object MessageLinks {

    private val HTTP_PATTERN = Regex(
        """https?://[^\s<>"'\]]+""",
        RegexOption.IGNORE_CASE,
    )

    private val MESHCORE_PATTERN = Regex(
        """meshcore://[^\s<>"'\]]+""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [text] holds anything tappable — lets callers skip the
     *  annotated-string build for the common plain message. */
    fun hasLinks(text: String): Boolean =
        HTTP_PATTERN.containsMatchIn(text) || MESHCORE_PATTERN.containsMatchIn(text)

    fun annotate(
        text: String,
        linkColor: Color,
        onHttpLink: (String) -> Unit,
        onMeshcoreLink: (String) -> Unit,
    ): AnnotatedString = buildAnnotatedString {
        val matches = (
            HTTP_PATTERN.findAll(text).map { it.range to Kind.Http } +
                MESHCORE_PATTERN.findAll(text).map { it.range to Kind.Meshcore }
            ).sortedBy { it.first.first }

        val styles = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )

        var cursor = 0
        for ((range, kind) in matches) {
            // Overlap guard: a malformed message could in principle make
            // two patterns claim the same span.
            if (range.first < cursor) continue
            if (range.first > cursor) append(text.substring(cursor, range.first))

            val raw = text.substring(range.first, range.last + 1)
            val url = trimTrailingPunctuation(raw)
            withLink(
                LinkAnnotation.Clickable(
                    tag = url,
                    styles = styles,
                    linkInteractionListener = {
                        when (kind) {
                            Kind.Http -> onHttpLink(url)
                            Kind.Meshcore -> onMeshcoreLink(url)
                        }
                    },
                ),
            ) { append(url) }
            // Sentence punctuation that the regex swallowed belongs to
            // the prose, not the link.
            if (url.length < raw.length) append(raw.substring(url.length))
            cursor = range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

    private enum class Kind { Http, Meshcore }

    private fun trimTrailingPunctuation(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in ".,;:!?)]}>'\"") end--
        return url.substring(0, end)
    }
}
