package com.example.betacensor

import android.content.Context

enum class Part(val label: String) {
    EYES("Eyes"),
    BREASTS("Breasts"),
    ASS("Ass"),
    PUSSY("Pussy"),
    DICK("Dicks"),
    ARMPITS("Armpits"),
    FEET("Feet"),
    BELLY_BUTTON("Belly buttons"),
}

enum class CensorStyle(val label: String, val defaultCaption: String) {
    BLACK_BAR("Black bar", "CENSORED"),
    PINK_BAR("Pink bar", "BETA SAFE ♡"),
    MOSAIC("Pixel mosaic", ""),
    HEARTS("Heart stickers", ""),
    TAPE("Caution tape", "NOT FOR YOU"),
}

data class Config(
    val parts: Set<Part> = setOf(Part.BREASTS, Part.ASS, Part.PUSSY, Part.DICK),
    /** Also censor parts that are clothed (bra, underwear, socks...). */
    val includeCovered: Boolean = false,
    val style: CensorStyle = CensorStyle.PINK_BAR,
    /** Blank = use the style's default caption. */
    val caption: String = "",
    /** Extra margin around each detection, as a fraction of its size. */
    val padding: Float = 0.15f,
    /** Detector confidence threshold. Lower = censors more aggressively. */
    val threshold: Float = 0.30f,
) {
    val effectiveCaption: String get() = caption.ifBlank { style.defaultCaption }
}

object ConfigStore {
    private const val PREFS = "beta_censor"

    @Volatile
    var current = Config()
        private set
    private var loaded = false

    fun load(ctx: Context) = synchronized(this) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Config()
        current = Config(
            parts = p.getStringSet("parts", null)
                ?.mapNotNull { n -> Part.entries.firstOrNull { it.name == n } }?.toSet()
                ?: d.parts,
            includeCovered = p.getBoolean("covered", d.includeCovered),
            style = CensorStyle.entries.firstOrNull { it.name == p.getString("style", null) } ?: d.style,
            caption = p.getString("caption", d.caption) ?: "",
            padding = p.getFloat("padding", d.padding),
            threshold = p.getFloat("threshold", d.threshold),
        )
        loaded = true
    }

    fun save(ctx: Context, cfg: Config) {
        current = cfg
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("parts", cfg.parts.map { it.name }.toSet())
            .putBoolean("covered", cfg.includeCovered)
            .putString("style", cfg.style.name)
            .putString("caption", cfg.caption)
            .putFloat("padding", cfg.padding)
            .putFloat("threshold", cfg.threshold)
            .apply()
    }
}
