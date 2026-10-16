package com.example.betacensor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF

/**
 * Keeps censors stable between frames.
 *
 * Censor windows are FLAG_SECURE, so they appear pure black in the screen capture. That means once
 * a part is covered, the detector can no longer see it. A box whose area is still black in the
 * capture is therefore "still doing its job" and is kept alive. That keep-alive is suspended for
 * a moment after scrolls/taps, because content moves and the old black box would go stale.
 */
class Tracker {

    private class Track(val id: Int, val part: Part, var box: RectF, var lastSeen: Long, var mosaic: Bitmap?)

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    fun clear() = tracks.clear()
    fun isEmpty() = tracks.isEmpty()

    fun update(regions: List<Region>, frame: Bitmap, wantMosaic: Boolean, now: Long, keepAliveAllowed: Boolean) {
        val used = BooleanArray(regions.size)

        for (t in tracks) {
            var best = -1; var bestIou = MATCH_IOU
            regions.forEachIndexed { i, r ->
                if (!used[i] && r.part == t.part) {
                    val v = iou(t.box, r.box)
                    if (v > bestIou) { bestIou = v; best = i }
                }
            }
            if (best >= 0) {
                used[best] = true
                t.box = lerpRect(t.box, regions[best].box, SMOOTHING)
                t.lastSeen = now
                if (wantMosaic && blackFraction(frame, t.box) < 0.2f) t.mosaic = mosaic(frame, t.box) ?: t.mosaic
            } else if (keepAliveAllowed && blackFraction(frame, t.box) > 0.75f) {
                t.lastSeen = now // our censor is still covering it
            }
        }

        regions.forEachIndexed { i, r ->
            if (!used[i]) tracks += Track(nextId++, r.part, RectF(r.box), now, if (wantMosaic) mosaic(frame, r.box) else null)
        }

        tracks.removeAll { now - it.lastSeen > TTL_MS }
    }

    fun snapshot(): List<CensorBox> = tracks.map { CensorBox(it.id, it.part, RectF(it.box), it.mosaic) }

    private fun blackFraction(frame: Bitmap, r: RectF): Float {
        var black = 0; var n = 0
        for (iy in 1..7) for (ix in 1..7) {
            val x = (r.left + r.width() * ix / 8f).toInt().coerceIn(0, frame.width - 1)
            val y = (r.top + r.height() * iy / 8f).toInt().coerceIn(0, frame.height - 1)
            val p = frame.getPixel(x, y)
            n++
            if (Color.red(p) < 8 && Color.green(p) < 8 && Color.blue(p) < 8) black++
        }
        return black.toFloat() / n
    }

    /** Tiny downscaled copy of the region; drawn scaled-up without filtering = pixel mosaic. */
    private fun mosaic(frame: Bitmap, r: RectF): Bitmap? {
        val rect = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        if (!rect.intersect(0, 0, frame.width, frame.height) || rect.width() < 2 || rect.height() < 2) return null
        val longSide = maxOf(rect.width(), rect.height())
        val cols = maxOf(1, MOSAIC_BLOCKS * rect.width() / longSide)
        val rows = maxOf(1, MOSAIC_BLOCKS * rect.height() / longSide)
        val crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
        return Bitmap.createScaledBitmap(crop, cols, rows, true)
    }

    companion object {
        const val TTL_MS = 250L
        const val MATCH_IOU = 0.25f
        const val SMOOTHING = 0.7f
        const val MOSAIC_BLOCKS = 9
    }
}
