package io.github.thatsfguy.meshcore.android.ui.screens

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
) {
    val isRepeater = type == Codes.ADV_TYPE_REPEATER
    val (background, darkText) = if (isRepeater) {
        REPEATER_GRAY to false
    } else {
        val c = avatarColors(seed)
        Color(c.backgroundArgb) to c.useDarkText
    }

    val glyph = when {
        isRepeater -> "⛰"
        type == Codes.ADV_TYPE_SENSOR -> "🌡"
        isChannel -> "#"
        else -> label.firstOrNull { !it.isWhitespace() }?.uppercase() ?: "?"
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

/** Neutral infrastructure gray, shared with the map's repeater pins. */
val REPEATER_GRAY = Color(0xFF757575)
