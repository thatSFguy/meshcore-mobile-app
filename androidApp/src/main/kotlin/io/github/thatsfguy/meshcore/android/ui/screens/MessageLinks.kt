package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import io.github.thatsfguy.meshcore.presentation.Mentions

/**
 * Turns the marked-up parts of a received message into styled spans.
 *
 * Two of them are links, and one is a mention. They share a single pass
 * because they share one overlap guard: a URL and a `@[Name]` that
 * claim the same characters must resolve to exactly one span, and two
 * independent passes over the same string cannot agree on which.
 *
 * Two kinds of link are recognised:
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
 *
 * Mentions are styled and NOT tappable. A `@[Name]` carries a name and
 * no key (see [Mentions]), so "open that node" would mean guessing
 * which of possibly several contacts answers to the string — the same
 * guess `PathGeometry` refuses to make about a hop. Tinting it is a
 * reading aid and claims nothing.
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

    /** True when [text] holds anything worth styling — lets callers skip
     *  the annotated-string build for the common plain message. */
    fun hasMarkup(text: String): Boolean =
        HTTP_PATTERN.containsMatchIn(text) ||
            MESHCORE_PATTERN.containsMatchIn(text) ||
            text.contains("@[")

    fun annotate(
        text: String,
        linkColor: Color,
        mentionColor: Color,
        selfName: String?,
        onHttpLink: (String) -> Unit,
        onMeshcoreLink: (String) -> Unit,
    ): AnnotatedString = buildAnnotatedString {
        val me = selfName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val matches = (
            HTTP_PATTERN.findAll(text).map { it.range to Kind.Http } +
                MESHCORE_PATTERN.findAll(text).map { it.range to Kind.Meshcore } +
                Mentions.spans(text).map { span ->
                    val mine = me != null && span.name.trim().lowercase() == me
                    span.range to if (mine) Kind.MentionOfMe else Kind.Mention
                }
            ).sortedBy { it.first.first }

        val styles = TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )

        var cursor = 0
        for ((range, kind) in matches) {
            // Overlap guard: a malformed message could in principle make
            // two patterns claim the same span — a URL with a `@[` in
            // its query string is enough.
            if (range.first < cursor) continue
            if (range.first > cursor) append(text.substring(cursor, range.first))

            if (kind == Kind.Mention || kind == Kind.MentionOfMe) {
                withStyle(
                    SpanStyle(
                        color = mentionColor,
                        // Bold only for the one that is about the reader.
                        // Bolding every mention would make a message that
                        // tags three people shout, and lose the single
                        // distinction the weight exists to carry.
                        fontWeight = if (kind == Kind.MentionOfMe) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        },
                    ),
                ) { append(text.substring(range.first, range.last + 1)) }
                cursor = range.last + 1
                continue
            }

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
                            else -> Unit
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

    private enum class Kind { Http, Meshcore, Mention, MentionOfMe }

    private fun trimTrailingPunctuation(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1] in ".,;:!?)]}>'\"") end--
        return url.substring(0, end)
    }
}
