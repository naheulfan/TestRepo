package com.example.betacensor

import android.graphics.RectF

data class Region(val part: Part, val box: RectF)

/** Turns raw detector classes into the user's toggleable parts and censor rectangles. */
object PartMapper {

    private val EXPOSED = mapOf(
        NudeDetector.FEMALE_BREAST_EXPOSED to Part.BREASTS,
        NudeDetector.BUTTOCKS_EXPOSED to Part.ASS,
        NudeDetector.ANUS_EXPOSED to Part.ASS,
        NudeDetector.FEMALE_GENITALIA_EXPOSED to Part.PUSSY,
        NudeDetector.MALE_GENITALIA_EXPOSED to Part.DICK,
        NudeDetector.ARMPITS_EXPOSED to Part.ARMPITS,
        NudeDetector.FEET_EXPOSED to Part.FEET,
        NudeDetector.BELLY_EXPOSED to Part.BELLY_BUTTON,
    )

    private val COVERED = mapOf(
        NudeDetector.FEMALE_BREAST_COVERED to Part.BREASTS,
        NudeDetector.BUTTOCKS_COVERED to Part.ASS,
        NudeDetector.ANUS_COVERED to Part.ASS,
        NudeDetector.FEMALE_GENITALIA_COVERED to Part.PUSSY,
        NudeDetector.ARMPITS_COVERED to Part.ARMPITS,
        NudeDetector.FEET_COVERED to Part.FEET,
        NudeDetector.BELLY_COVERED to Part.BELLY_BUTTON,
    )

    private val FACES = setOf(NudeDetector.FACE_FEMALE, NudeDetector.FACE_MALE)

    fun map(dets: List<NudeDetector.Det>, cfg: Config, frameW: Int, frameH: Int): List<Region> {
        val out = ArrayList<Region>()
        for (d in dets) {
            val part: Part
            val r: RectF
            if (d.cls in FACES) {
                if (Part.EYES !in cfg.parts) continue
                part = Part.EYES
                r = eyeBar(d.box)
            } else {
                part = EXPOSED[d.cls]
                    ?: (if (cfg.includeCovered) COVERED[d.cls] else null)
                    ?: continue
                if (part !in cfg.parts) continue
                r = if (part == Part.BELLY_BUTTON) navel(d.box) else RectF(d.box)
            }
            r.inflateBy(cfg.padding)
            r.clampTo(frameW, frameH)
            if (r.width() > 4 && r.height() > 4) out += Region(part, r)
        }
        return out
    }

    /** Classic horizontal "eye bar" across the upper-middle of a face box. Tweak fractions to taste. */
    private fun eyeBar(face: RectF) = RectF(
        face.left + face.width() * 0.04f,
        face.top + face.height() * 0.28f,
        face.right - face.width() * 0.04f,
        face.top + face.height() * 0.54f,
    )

    /** The navel sits roughly in the middle of the belly box; censor a small square there. */
    private fun navel(belly: RectF): RectF {
        val s = minOf(belly.width(), belly.height()) * 0.38f
        val cx = belly.centerX(); val cy = belly.centerY() + belly.height() * 0.05f
        return RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
    }
}
