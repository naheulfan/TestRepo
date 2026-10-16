package com.example.betacensor

import android.graphics.Bitmap
import android.graphics.RectF
import java.util.concurrent.atomic.AtomicReference

/** One censor to draw. [rect] is in capture-frame pixels. */
data class CensorBox(val id: Int, val part: Part, val rect: RectF, val mosaic: Bitmap?)

data class Snapshot(
    val boxes: List<CensorBox>,
    val frameW: Int, val frameH: Int,
    val screenW: Int, val screenH: Int,
) {
    companion object { val EMPTY = Snapshot(emptyList(), 0, 0, 0, 0) }
}

/** Shared state between the capture service and the overlay (accessibility) service. */
object CensorBus {
    /** True while Reddit is the foreground app. */
    @Volatile var redditForeground = false

    /** Last time the user scrolled/tapped inside Reddit (uptime ms). */
    @Volatile var lastInteractionMs = 0L

    val latest = AtomicReference(Snapshot.EMPTY)

    /** Set by the overlay service; called (from the capture thread) when a new snapshot is ready. */
    @Volatile var sink: (() -> Unit)? = null

    fun publish(s: Snapshot) {
        latest.set(s)
        sink?.invoke()
    }
}
