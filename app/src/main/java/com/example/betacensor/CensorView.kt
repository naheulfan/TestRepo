package com.example.betacensor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Draws one censor in the selected style. Always fully opaque so nothing shows through. */
class CensorView(ctx: Context) : View(ctx) {

    private var style = CensorStyle.BLACK_BAR
    private var caption = ""
    private var mosaic: Bitmap? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }
    private val pixelPaint = Paint() // no filtering → hard-edged mosaic blocks
    private val heart = Path()
    private val dst = RectF()

    fun bind(b: CensorBox, cfg: Config) {
        val cap = cfg.effectiveCaption
        if (style != cfg.style || caption != cap || mosaic !== b.mosaic) {
            style = cfg.style; caption = cap; mosaic = b.mosaic
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (style) {
            CensorStyle.BLACK_BAR -> {
                c.drawColor(Color.BLACK)
                drawCaption(c, Color.WHITE)
            }
            CensorStyle.PINK_BAR -> {
                c.drawColor(HOT_PINK)
                drawCaption(c, Color.WHITE)
            }
            CensorStyle.MOSAIC -> {
                val m = mosaic
                if (m != null) { dst.set(0f, 0f, w, h); c.drawBitmap(m, null, dst, pixelPaint) }
                else c.drawColor(Color.rgb(120, 110, 115))
                drawCaption(c, Color.WHITE)
            }
            CensorStyle.HEARTS -> {
                c.drawColor(LIGHT_PINK)
                val cell = maxOf(minOf(w, h) / 2.5f, 22f * density)
                var row = 0
                var y = cell / 2
                while (y < h + cell) {
                    var x = if (row % 2 == 0) cell / 2 else 0f
                    while (x < w + cell) {
                        fill.color = if ((row + (x / cell).toInt()) % 2 == 0) HOT_PINK else Color.WHITE
                        heartPath(x, y, cell * 0.9f)
                        c.drawPath(heart, fill)
                        x += cell
                    }
                    y += cell * 0.85f; row++
                }
                drawCaption(c, Color.WHITE)
            }
            CensorStyle.TAPE -> {
                c.drawColor(Color.BLACK)
                val stripe = maxOf(w, h) / 7f
                c.save()
                c.rotate(-35f, w / 2, h / 2)
                fill.color = HOT_PINK
                val span = (w + h) * 1.5f
                var x = -span
                while (x < span) { c.drawRect(x, -span, x + stripe, span, fill); x += stripe * 2 }
                c.restore()
                if (caption.isNotEmpty()) {
                    val vertical = h > w * 1.6f
                    fill.color = Color.BLACK
                    if (vertical) c.drawRect(w * 0.18f, 0f, w * 0.82f, h, fill)
                    else c.drawRect(0f, h * 0.3f, w, h * 0.7f, fill)
                    drawCaption(c, HOT_PINK, 0.6f)
                }
            }
        }
    }

    /** Fits the caption to the box; rotates it for tall, narrow censors. */
    private fun drawCaption(c: Canvas, color: Int, acrossScale: Float = 1f) {
        if (caption.isEmpty()) return
        val vertical = height > width * 1.6f
        val along = (if (vertical) height else width).toFloat()
        val across = (if (vertical) width else height).toFloat() * acrossScale
        text.color = color
        text.textSize = across * 0.42f
        val tw = text.measureText(caption)
        if (tw > along * 0.9f) text.textSize *= along * 0.9f / tw
        if (text.textSize < 8f * density) return
        c.save()
        if (vertical) c.rotate(-90f, width / 2f, height / 2f)
        val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(caption, width / 2f, baseline, text)
        c.restore()
    }

    private fun heartPath(cx: Float, cy: Float, s: Float) {
        heart.reset()
        heart.moveTo(cx, cy + s * 0.42f)
        heart.cubicTo(cx - s * 0.62f, cy + s * 0.02f, cx - s * 0.5f, cy - s * 0.5f, cx, cy - s * 0.2f)
        heart.cubicTo(cx + s * 0.5f, cy - s * 0.5f, cx + s * 0.62f, cy + s * 0.02f, cx, cy + s * 0.42f)
        heart.close()
    }

    companion object {
        val HOT_PINK = Color.rgb(255, 64, 160)
        val LIGHT_PINK = Color.rgb(255, 196, 226)
    }
}
