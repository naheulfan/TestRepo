package com.example.betacensor

import android.graphics.RectF

fun iou(a: RectF, b: RectF): Float {
    val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
    val r = minOf(a.right, b.right); val btm = minOf(a.bottom, b.bottom)
    if (r <= l || btm <= t) return 0f
    val inter = (r - l) * (btm - t)
    return inter / (a.width() * a.height() + b.width() * b.height() - inter)
}

fun RectF.inflateBy(fraction: Float) {
    val dx = width() * fraction / 2f; val dy = height() * fraction / 2f
    set(left - dx, top - dy, right + dx, bottom + dy)
}

fun RectF.clampTo(w: Int, h: Int) {
    set(left.coerceIn(0f, w.toFloat()), top.coerceIn(0f, h.toFloat()),
        right.coerceIn(0f, w.toFloat()), bottom.coerceIn(0f, h.toFloat()))
}

fun lerpRect(from: RectF, to: RectF, t: Float) = RectF(
    from.left + (to.left - from.left) * t,
    from.top + (to.top - from.top) * t,
    from.right + (to.right - from.right) * t,
    from.bottom + (to.bottom - from.bottom) * t,
)
