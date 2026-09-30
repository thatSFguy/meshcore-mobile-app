package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.avatarColors

/**
 * Hash-colored avatar circle for contacts and channels: the background
 * derives from the node pubkey / channel seed (AvatarColors, ported
 * from the sibling app / Meshtastic scheme) so every row is instantly
 * distinguishable. Repeaters are deliberately NEUTRAL GRAY — same as
 * their map pins — because infrastructure shouldn't scream for
 * attention (and red reads as trouble).
 */
@Composable
fun NodeAvatar(
    seed: String,
    label: String,
    type: Int? = null,
    isChannel: Boolean = false,
    size: Dp = 40.dp,
    /**
     * A channel's kind, from its key. Only a [ChannelKind.Hashtag]
     * channel gets a '#': putting one on every channel made private
     * channels look like ones anyone can join. Null (key not readable)
     * falls back to the name's initial rather than claiming a kind.
     */
    channelKind: io.github.thatsfguy.meshcore.protocol.ChannelKind? = null,
) {
    // Infrastructure nodes reuse the MAP marker artwork (same colors +
    // glyphs) so a repeater looks identical in the list and on the map.
    if (type == Codes.ADV_TYPE_REPEATER ||
        type == Codes.ADV_TYPE_ROOM ||
        type == Codes.ADV_TYPE_SENSOR
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val badge = androidx.compose.runtime.remember(type, size) {
            io.github.thatsfguy.meshcore.android.platform.NodeMarkers
                .buildBadge(context, type, sizeDp = size.value.toInt())
        }
        androidx.compose.foundation.Image(
            bitmap = badge.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
        return
    }

    val c = avatarColors(seed)
    val background = Color(c.backgroundArgb)
    val darkText = c.useDarkText

    val ink = if (darkText) Color.Black else Color.White
    if (isChannel && channelKind != null && channelKind != io.github.thatsfguy.meshcore.protocol.ChannelKind.Hashtag) {
        Box(
            modifier = Modifier.size(size).background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (channelKind == io.github.thatsfguy.meshcore.protocol.ChannelKind.Private) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Lock,
                    contentDescription = "Private channel",
                    tint = ink,
                    modifier = Modifier.size(size * 0.5f),
                )
            } else {
                GlobeGlyph(ink, Modifier.size(size * 0.52f))
            }
        }
        return
    }

    val glyph = when {
        isChannel && channelKind == io.github.thatsfguy.meshcore.protocol.ChannelKind.Hashtag -> "#"
        // Not the first character: an emoji is two, and half of one drew
        // "�". See AvatarGlyph.
        else -> io.github.thatsfguy.meshcore.presentation.AvatarGlyph.of(label)
    }

    Box(
        modifier = Modifier
            .size(size)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (darkText) Color.Black else Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/**
 * A globe for the Public channel, drawn rather than imported: the core
 * icon set has none, and the extended set is a large dependency for one
 * glyph.
 */
@Composable
private fun GlobeGlyph(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = "Public channel" }) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.09f)
        val r = size.minDimension / 2f - stroke.width / 2f
        drawCircle(color, radius = r, style = stroke)
        // Meridian and the equator.
        drawOval(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - r * 0.45f, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 2f),
            style = stroke,
        )
        drawLine(color, androidx.compose.ui.geometry.Offset(center.x - r, center.y),
            androidx.compose.ui.geometry.Offset(center.x + r, center.y), strokeWidth = stroke.width)
    }
}

/** Neutral infrastructure gray, shared with the map's repeater pins. */
val REPEATER_GRAY = Color(0xFF757575)
