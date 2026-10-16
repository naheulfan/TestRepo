package com.example.betacensor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.FloatBuffer

/**
 * Runs the NudeNet v3 "320n" YOLOv8 model (assets/320n.onnx).
 * Output classes (index -> label) follow NudeNet's ordering.
 */
class NudeDetector(ctx: Context) : AutoCloseable {

    data class Det(val cls: Int, val score: Float, val box: RectF)

    private val size = 320
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val letterbox = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(letterbox)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(size * size)
    private val input = FloatBuffer.allocate(3 * size * size)

    init {
        val bytes = ctx.assets.open(MODEL_FILE).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            // Optional: try addNnapi() here; faster on some phones, slower on others.
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
    }

    /** Detects within the top-left [w]x[h] region of [src]. Boxes are returned in src pixels. */
    fun detect(src: Bitmap, w: Int, h: Int, threshold: Float): List<Det> {
        // Letterbox: pad bottom/right with black to a square, then scale to 320 (same as NudeNet).
        val scale = size.toFloat() / maxOf(w, h)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, Rect(0, 0, w, h), RectF(0f, 0f, w * scale, h * scale), paint)
        letterbox.getPixels(pixels, 0, size, 0, 0, size, size)

        val area = size * size
        for (i in 0 until area) {
            val p = pixels[i]
            input.put(i, ((p shr 16) and 0xFF) / 255f)
            input.put(area + i, ((p shr 8) and 0xFF) / 255f)
            input.put(2 * area + i, (p and 0xFF) / 255f)
        }
        input.rewind()

        val raw = ArrayList<Det>()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val out = (res[0].value as Array<Array<FloatArray>>)[0] // [4 + classes][anchors]
                val classes = out.size - 4
                val anchors = out[0].size
                for (j in 0 until anchors) {
                    var best = -1; var bestScore = threshold
                    for (c in 0 until classes) {
                        val s = out[4 + c][j]
                        if (s > bestScore) { bestScore = s; best = c }
                    }
                    if (best < 0) continue
                    val cx = out[0][j] / scale; val cy = out[1][j] / scale
                    val bw = out[2][j] / scale; val bh = out[3][j] / scale
                    val box = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
                    box.clampTo(w, h)
                    if (box.width() > 2 && box.height() > 2) raw += Det(best, bestScore, box)
                }
            }
        }
        return nms(raw, 0.45f)
    }

    private fun nms(dets: List<Det>, thr: Float): List<Det> {
        val pool = dets.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Det>()
        while (pool.isNotEmpty()) {
            val d = pool.removeAt(0)
            keep += d
            pool.removeAll { it.cls == d.cls && iou(it.box, d.box) > thr }
        }
        return keep
    }

    override fun close() { session.close() }

    companion object {
        const val MODEL_FILE = "320n.onnx"

        // NudeNet v3 class indices
        const val FEMALE_GENITALIA_COVERED = 0
        const val FACE_FEMALE = 1
        const val BUTTOCKS_EXPOSED = 2
        const val FEMALE_BREAST_EXPOSED = 3
        const val FEMALE_GENITALIA_EXPOSED = 4
        const val MALE_BREAST_EXPOSED = 5
        const val ANUS_EXPOSED = 6
        const val FEET_EXPOSED = 7
        const val BELLY_COVERED = 8
        const val FEET_COVERED = 9
        const val ARMPITS_COVERED = 10
        const val ARMPITS_EXPOSED = 11
        const val FACE_MALE = 12
        const val BELLY_EXPOSED = 13
        const val MALE_GENITALIA_EXPOSED = 14
        const val ANUS_COVERED = 15
        const val FEMALE_BREAST_COVERED = 16
        const val BUTTOCKS_COVERED = 17
    }
}
