package com.example.betacensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import java.nio.ByteBuffer

/** Mirrors the screen at reduced resolution, runs detection, and publishes censor boxes. */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CAPTURE_SCALE = 0.5f
        private const val INTERACTION_QUIET_MS = 120L // must be < Tracker.TTL_MS
        private const val CHANNEL = "censor"
        private const val TAG = "BetaCensor"

        @Volatile var isRunning = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var detector: NudeDetector? = null
    private val tracker = Tracker()

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    private var screenW = 0; private var screenH = 0
    private var frameW = 0; private var frameH = 0; private var dpi = 0
    private var frameBitmap: Bitmap? = null
    private var wasActive = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        ConfigStore.load(this)
        worker = HandlerThread("censor-capture").apply { start() }
        handler = Handler(worker.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Censoring", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || projection != null) return START_NOT_STICKY

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        isRunning = true

        handler.post {
            setupDisplay()
            try {
                detector = NudeDetector(this)
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Couldn't load assets/${NudeDetector.MODEL_FILE}: ${e.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun measure(): Boolean {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        val changed = m.widthPixels != screenW || m.heightPixels != screenH
        screenW = m.widthPixels; screenH = m.heightPixels
        frameW = ((screenW * CAPTURE_SCALE).toInt() / 2) * 2
        frameH = ((screenH * CAPTURE_SCALE).toInt() / 2) * 2
        dpi = (m.densityDpi * CAPTURE_SCALE).toInt()
        return changed
    }

    private fun setupDisplay() {
        val proj = projection ?: return
        measure()
        val old = reader
        val r = ImageReader.newInstance(frameW, frameH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = proj.createVirtualDisplay(
                "beta-censor", frameW, frameH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )
        } else {
            vd.resize(frameW, frameH, dpi)
            vd.surface = r.surface
        }
        old?.close()
        tracker.clear()
        publish()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            if (virtualDisplay != null) {
                val oldW = screenW; val oldH = screenH
                measure()
                if (oldW != screenW || oldH != screenH) setupDisplay()
            }
        }
    }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val det = detector
        if (!CensorBus.redditForeground || det == null || image.width != frameW) {
            image.close()
            if (wasActive) { tracker.clear(); publish(); wasActive = false }
            return
        }
        wasActive = true

        val frame = toBitmap(image)
        image.close()

        val cfg = ConfigStore.current
        val now = SystemClock.uptimeMillis()
        val keepAlive = now - CensorBus.lastInteractionMs > INTERACTION_QUIET_MS
        val regions = if (cfg.parts.isEmpty()) emptyList()
                      else PartMapper.map(det.detect(frame, frameW, frameH, cfg.threshold), cfg, frameW, frameH)
        tracker.update(regions, frame, cfg.style == CensorStyle.MOSAIC, now, keepAlive)
        publish()
    }

    /** Copies the RGBA image into a reused bitmap (which may be wider than the frame due to row padding). */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        var bmp = frameBitmap
        if (bmp == null || bmp.width != rowPixels || bmp.height != image.height) {
            bmp = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            frameBitmap = bmp
        }
        val buf = plane.buffer
        val needed = plane.rowStride * image.height
        if (buf.remaining() >= needed) {
            bmp.copyPixelsFromBuffer(buf)
        } else {
            val tmp = ByteBuffer.allocate(needed); tmp.put(buf); tmp.rewind()
            bmp.copyPixelsFromBuffer(tmp)
        }
        return bmp
    }

    private fun publish() {
        CensorBus.publish(Snapshot(tracker.snapshot(), frameW, frameH, screenW, screenH))
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Beta Censor is active")
            .setContentText("Censoring while Reddit is open")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        handler.post {
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            detector?.close()
            virtualDisplay = null; reader = null; projection = null; detector = null
            CensorBus.publish(Snapshot.EMPTY)
            worker.quitSafely()
        }
        super.onDestroy()
    }
}
