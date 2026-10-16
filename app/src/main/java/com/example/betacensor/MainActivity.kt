package com.example.betacensor

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val cfg get() = ConfigStore.current
    private fun update(f: (Config) -> Config) = ConfigStore.save(this, f(cfg))

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                        .putExtra(CaptureService.EXTRA_DATA, data)
                )
            }
            status.postDelayed({ refresh() }, 500)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigStore.load(this)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContentView(buildUi())
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val a11y = if (OverlayAccessibilityService.isConnected) "✅ enabled" else "❌ not enabled"
        val cap = if (CaptureService.isRunning) "✅ running" else "⏸ stopped"
        status.text = "Accessibility service: $a11y\nCensoring: $cap"
    }

    private fun startCensoring() {
        if (!OverlayAccessibilityService.isConnected) {
            Toast.makeText(this, "Enable the Beta Censor accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) // full screen only
        else mpm.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }

    // ---------- UI (built in code to keep the project small) ----------

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad * 2) }

        col.addView(TextView(this).apply { text = "Beta Censor"; textSize = 26f; setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(status)

        col.addView(Button(this).apply {
            text = "1 · Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply { text = "2 · Start censoring"; setOnClickListener { startCensoring() } })
        col.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                status.postDelayed({ refresh() }, 400)
            }
        })

        col.addView(header("Censor these", pad))
        for (part in Part.entries) {
            col.addView(Switch(this).apply {
                text = part.label
                isChecked = part in cfg.parts
                setOnCheckedChangeListener { _, on -> update { it.copy(parts = if (on) it.parts + part else it.parts - part) } }
            })
        }
        col.addView(Switch(this).apply {
            text = "Also censor covered parts (bras, panties, socks…)"
            isChecked = cfg.includeCovered
            setOnCheckedChangeListener { _, on -> update { it.copy(includeCovered = on) } }
        })

        col.addView(header("Style", pad))
        val group = RadioGroup(this)
        for (s in CensorStyle.entries) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = s.label; isChecked = s == cfg.style }
            group.addView(rb)
            rb.setOnCheckedChangeListener { _, on -> if (on) update { it.copy(style = s) } }
        }
        col.addView(group)

        col.addView(header("Caption (blank = style default)", pad))
        col.addView(EditText(this).apply {
            setText(cfg.caption)
            hint = "e.g. CENSORED, NO PEEKING, BETA SAFE ♡"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { update { it.copy(caption = e?.toString() ?: "") } }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        col.addView(header("Bar size (extra margin)", pad))
        col.addView(seek((cfg.padding * 100).toInt(), 60) { p -> update { it.copy(padding = p / 100f) } })

        col.addView(header("Sensitivity (higher = censors more, more false positives)", pad))
        col.addView(seek(((0.6f - cfg.threshold) / 0.45f * 100).toInt(), 100) { p ->
            update { it.copy(threshold = 0.6f - p / 100f * 0.45f) }
        })

        col.addView(TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = "Tip: in Reddit settings, turn off \"Blur NSFW images\" so the detector can see the original image."
        })

        return ScrollView(this).apply { addView(col) }
    }

    private fun header(t: String, pad: Int) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 4)
    }

    private fun seek(initial: Int, max: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        progress = initial.coerceIn(0, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
}
