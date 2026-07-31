package io.github.thatsfguy.meshcore.android.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * Canvas-drawn map markers — colored pin + white glyph per node type
 * (person / radio tower / house / sensor, mirroring the reference
 * client's icon scheme) and an optional always-visible name label chip
 * below the pin. Hand-drawn so the map needs no icon/marker libraries.
 */
object NodeMarkers {

    /** Marker bitmap + the anchor point (fraction of size) for osmdroid. */
    class Pin(val drawable: BitmapDrawable, val anchorU: Float, val anchorV: Float)

    fun colorFor(type: Int, isSelf: Boolean): Int = when {
        isSelf -> 0xFF43A047.toInt()                       // green — this node
        type == Codes.ADV_TYPE_REPEATER -> 0xFFE53935.toInt() // red
        type == Codes.ADV_TYPE_ROOM -> 0xFF8E24AA.toInt()     // purple
        type == Codes.ADV_TYPE_SENSOR -> 0xFFF4511E.toInt()   // orange
        else -> 0xFF1E88E5.toInt()                            // blue — companion/chat
    }

    fun build(
        context: Context,
        type: Int,
        label: String?,
        isSelf: Boolean = false,
    ): Pin {
        val density = context.resources.displayMetrics.density
        val pinD = (34 * density).toInt()          // pin circle diameter
        val tail = (7 * density).toInt()           // pointer below the circle
        val labelPad = (4 * density)
        val labelGap = (2 * density).toInt()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * density
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            color = Color.WHITE
        }
        val labelText = label?.take(18)
        val labelW = labelText?.let { (textPaint.measureText(it) + labelPad * 2).toInt() } ?: 0
        val labelH = labelText?.let { (textPaint.textSize + labelPad * 1.4f).toInt() } ?: 0

        val width = maxOf(pinD, labelW)
        val pinBottom = pinD + tail
        val height = pinBottom + (if (labelText != null) labelGap + labelH else 0)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = width / 2f
        val r = pinD / 2f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorFor(type, isSelf)
        }
        // Pointer tail then circle (tail under circle edge).
        val tailPath = Path().apply {
            moveTo(cx - r * 0.35f, pinD * 0.82f)
            lineTo(cx + r * 0.35f, pinD * 0.82f)
            lineTo(cx, pinBottom.toFloat())
            close()
        }
        canvas.drawPath(tailPath, fill)
        canvas.drawCircle(cx, r, r, fill)
        // White rim for contrast on any tile.
        canvas.drawCircle(
            cx, r, r - 1 * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                color = Color.argb(200, 255, 255, 255)
            },
        )

        drawGlyph(canvas, cx, r, r, type, isSelf, density)

        if (labelText != null) {
            val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(210, 27, 43, 51)
            }
            val top = (pinBottom + labelGap).toFloat()
            val left = cx - labelW / 2f
            canvas.drawRoundRect(
                RectF(left, top, left + labelW, top + labelH),
                4 * density, 4 * density, chip,
            )
            val baseline = top + labelH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(labelText, left + labelPad, baseline, textPaint)
        }

        // Anchor at the pin tip (center-x, bottom of the tail).
        return Pin(
            BitmapDrawable(context.resources, bmp),
            anchorU = 0.5f,
            anchorV = pinBottom.toFloat() / height,
        )
    }

    /** White glyph inside the pin circle, centered at (cx, cy), radius r. */
    private fun drawGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        type: Int,
        isSelf: Boolean,
        density: Float,
    ) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        when {
            isSelf -> {
                // Location-dot: ring + center dot.
                canvas.drawCircle(cx, cy, r * 0.45f, stroke)
                canvas.drawCircle(cx, cy, r * 0.18f, fill)
            }
            type == Codes.ADV_TYPE_REPEATER -> {
                // Radio tower: two legs, rungs, antenna dot.
                canvas.drawLine(cx - r * 0.32f, cy + r * 0.5f, cx, cy - r * 0.42f, stroke)
                canvas.drawLine(cx + r * 0.32f, cy + r * 0.5f, cx, cy - r * 0.42f, stroke)
                canvas.drawLine(cx - r * 0.20f, cy + r * 0.12f, cx + r * 0.20f, cy + r * 0.12f, stroke)
                canvas.drawLine(cx - r * 0.12f, cy - r * 0.16f, cx + r * 0.12f, cy - r * 0.16f, stroke)
                canvas.drawCircle(cx, cy - r * 0.5f, r * 0.10f, fill)
            }
            type == Codes.ADV_TYPE_ROOM -> {
                // House: roof + walls + door notch.
                val roof = Path().apply {
                    moveTo(cx - r * 0.45f, cy)
                    lineTo(cx, cy - r * 0.45f)
                    lineTo(cx + r * 0.45f, cy)
                }
                canvas.drawPath(roof, stroke)
                canvas.drawRect(cx - r * 0.32f, cy, cx + r * 0.32f, cy + r * 0.45f, stroke)
                canvas.drawLine(cx, cy + r * 0.45f, cx, cy + r * 0.15f, stroke)
            }
            type == Codes.ADV_TYPE_SENSOR -> {
                // Sensor: dot + two radiating arcs.
                canvas.drawCircle(cx, cy + r * 0.25f, r * 0.12f, fill)
                canvas.drawArc(
                    RectF(cx - r * 0.35f, cy - r * 0.10f, cx + r * 0.35f, cy + r * 0.60f),
                    -150f, 120f, false, stroke,
                )
                canvas.drawArc(
                    RectF(cx - r * 0.55f, cy - r * 0.40f, cx + r * 0.55f, cy + r * 0.70f),
                    -150f, 120f, false, stroke,
                )
            }
            else -> {
                // Person: head + shoulders.
                canvas.drawCircle(cx, cy - r * 0.22f, r * 0.18f, fill)
                canvas.drawArc(
                    RectF(cx - r * 0.35f, cy + r * 0.02f, cx + r * 0.35f, cy + r * 0.75f),
                    180f, 180f, true, fill,
                )
            }
        }
    }
}
