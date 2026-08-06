package io.github.thatsfguy.meshcore.android.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap

/**
 * Pins for the message-route map.
 *
 * A filled pin and a hollow one have to be tellable apart at a glance,
 * because the difference between them is the difference between "this
 * node is here" and "this node is somewhere near here, and we placed it
 * so you could see the shape of the route".
 *
 * Dashing the LINE alone was not enough. A normal solid marker sitting
 * at a made-up coordinate still reads as a surveyed position — and a
 * screenshot of it certainly does. So an inferred node gets a hollow,
 * dashed-outline pin carrying a question mark, and never the filled one.
 */
object PathPins {

    fun forNode(context: Context, inferred: Boolean, isEndpoint: Boolean): Drawable {
        val size = 44
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val r = size / 2f - 5f
        val cx = size / 2f
        val cy = size / 2f
        val accent = Color.rgb(0x4F, 0xC3, 0xF7)

        if (inferred) {
            // Hollow, dashed outline, question mark: visibly provisional.
            canvas.drawCircle(
                cx, cy, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb(60, 0x10, 0x10, 0x10)
                },
            )
            canvas.drawCircle(
                cx, cy, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = accent
                    pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
                },
            )
            canvas.drawText(
                "?",
                cx,
                cy + 7f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    textSize = 22f
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                },
            )
        } else {
            canvas.drawCircle(
                cx, cy, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = if (isEndpoint) Color.rgb(0x66, 0xBB, 0x6A) else accent
                },
            )
            canvas.drawCircle(
                cx, cy, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.argb(220, 0x10, 0x10, 0x10)
                },
            )
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
