package com.example.betacensor

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Owns the censor windows. Accessibility overlays are "trusted", so taps pass through them to
 * Reddit (normal SYSTEM_ALERT_WINDOW overlays block touches on Android 12+ when opaque).
 * Each censor is its own small FLAG_SECURE window so it shows as black in the screen capture
 * instead of blacking out the whole screen.
 */
class OverlayAccessibilityService : AccessibilityService() {

    companion object {
        val TARGET_PACKAGES = setOf("com.reddit.frontpage")
        private val IGNORED = setOf("com.android.systemui", "com.example.betacensor")
        @Volatile var isConnected = false
            private set
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val views = HashMap<Int, CensorView>()
    private val scheduled = AtomicBoolean(false)
    private val renderTask = Runnable {
        scheduled.set(false)
        render(CensorBus.latest.get())
    }

    override fun onServiceConnected() {
        ConfigStore.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        CensorBus.sink = { if (scheduled.compareAndSet(false, true)) main.post(renderTask) }
        CensorBus.redditForeground = rootInActiveWindow?.packageName?.toString() in TARGET_PACKAGES
        isConnected = true
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in IGNORED || "inputmethod" in pkg || "keyboard" in pkg) return
                CensorBus.lastInteractionMs = SystemClock.uptimeMillis()
                val active = pkg in TARGET_PACKAGES
                if (active != CensorBus.redditForeground) {
                    CensorBus.redditForeground = active
                    if (!active) clearAll()
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (pkg in TARGET_PACKAGES) {
                    CensorBus.redditForeground = true
                    CensorBus.lastInteractionMs = SystemClock.uptimeMillis()
                }
            }
        }
    }

    private fun render(s: Snapshot) {
        if (!CensorBus.redditForeground || s.frameW == 0) { clearAll(); return }
        val cfg = ConfigStore.current
        val sx = s.screenW.toFloat() / s.frameW
        val sy = s.screenH.toFloat() / s.frameH
        val alive = HashSet<Int>()

        for (b in s.boxes) {
            alive += b.id
            val x = (b.rect.left * sx).roundToInt()
            val y = (b.rect.top * sy).roundToInt()
            val w = (b.rect.width() * sx).roundToInt().coerceAtLeast(1)
            val h = (b.rect.height() * sy).roundToInt().coerceAtLeast(1)

            val existing = views[b.id]
            if (existing == null) {
                val v = CensorView(this).also { it.bind(b, cfg) }
                try {
                    wm.addView(v, params(x, y, w, h))
                    views[b.id] = v
                } catch (ex: Exception) {
                    Log.w("BetaCensor", "addView failed", ex)
                }
            } else {
                existing.bind(b, cfg)
                val p = existing.layoutParams as WindowManager.LayoutParams
                if (p.x != x || p.y != y || p.width != w || p.height != h) {
                    p.x = x; p.y = y; p.width = w; p.height = h
                    try { wm.updateViewLayout(existing, p) } catch (_: Exception) {}
                }
            }
        }

        val dead = views.keys.filter { it !in alive }
        for (id in dead) views.remove(id)?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
    }

    private fun params(x: Int, y: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x; this.y = y
        windowAnimations = 0
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private fun clearAll() {
        main.post {
            for (v in views.values) try { wm.removeViewImmediate(v) } catch (_: Exception) {}
            views.clear()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isConnected = false
        CensorBus.sink = null
        CensorBus.redditForeground = false
        for (v in views.values) try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        views.clear()
        super.onDestroy()
    }
}
