package com.ezhou.sttbutton

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Setup status and the button's style. The style is read by [Overlay] from the same preferences as it changes. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(ButtonStyle.PREFS, MODE_PRIVATE) }
    private val density by lazy { resources.displayMetrics.density }
    private var style = ButtonStyle()
    private lateinit var serviceStatus: TextView
    private lateinit var microphoneStatus: TextView
    private lateinit var grantMicrophone: Button
    private lateinit var unhide: Button
    private lateinit var previewIdle: View
    private lateinit var previewRecording: View
    private lateinit var sizeLabel: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var gapLabel: TextView
    private lateinit var gapSlider: SeekBar
    private lateinit var idleSwatches: LinearLayout
    private lateinit var recordingSwatches: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        style = ButtonStyle.load(prefs)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(content)
            // The window draws edge to edge, so the bars' height is padded here rather than drawn under.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left + dp(20), bars.top + dp(12), bars.right + dp(20), bars.bottom + dp(20))
                insets
            }
        }
        content.addView(text(getString(R.string.app_name), 26f, bold = true))
        setup(content)
        button(content)
        content.addView(header(R.string.section_about))
        content.addView(text(getString(R.string.restricted_settings_hint), 13f))
        content.addView(text(getString(R.string.licenses), 12f))
        setContentView(scroll)
        restyle()
    }

    private fun setup(content: LinearLayout) {
        content.addView(header(R.string.section_setup))
        serviceStatus = text("", 15f).also(content::addView)
        content.addView(Button(this).apply {
            setText(R.string.open_accessibility)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        microphoneStatus = text("", 15f).also(content::addView)
        grantMicrophone = Button(this).apply {
            setText(R.string.grant_microphone)
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        }.also(content::addView)
    }

    private fun button(content: LinearLayout) {
        content.addView(header(R.string.section_button))
        previewIdle = View(this)
        previewRecording = View(this)
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(PREVIEW_DP * ButtonStyle.SIZE_RANGE.last / 100 + 8)
            addView(previewIdle)
            addView(previewRecording)
            addView(text(getString(R.string.preview_label), 13f).apply { setPadding(dp(12), 0, 0, 0) })
        })
        content.addView(RadioGroup(this).apply {
            val over = RadioButton(context).apply { id = View.generateViewId(); setText(R.string.placement_over) }
            val left = RadioButton(context).apply { id = View.generateViewId(); setText(R.string.placement_left) }
            addView(over)
            addView(left)
            check(if (style.placement == ButtonPlacement.OVER_MIC) over.id else left.id)
            setOnCheckedChangeListener { _, id ->
                change(style.copy(placement = if (id == over.id) ButtonPlacement.OVER_MIC else ButtonPlacement.LEFT_OF_MIC))
            }
        })
        gapLabel = text("", 15f).also(content::addView)
        gapSlider = slider(ButtonStyle.GAP_RANGE, style.gapPx, GAP_STEP) { change(style.copy(gapPx = it)) }.also(content::addView)
        sizeLabel = text("", 15f).also(content::addView)
        content.addView(slider(ButtonStyle.SIZE_RANGE, style.sizePct, SLIDER_STEP) { change(style.copy(sizePct = it)) })
        opacityLabel = text("", 15f).also(content::addView)
        content.addView(slider(ButtonStyle.OPACITY_RANGE, style.opacityPct, SLIDER_STEP) { change(style.copy(opacityPct = it)) })
        content.addView(text(getString(R.string.idle_color), 15f))
        idleSwatches = swatches { change(style.copy(idleRgb = it)) }.also(content::addView)
        content.addView(text(getString(R.string.recording_color), 15f))
        recordingSwatches = swatches { change(style.copy(recordingRgb = it)) }.also(content::addView)
        content.addView(text(getString(R.string.drag_hint), 13f))
        unhide = Button(this).apply {
            setText(R.string.unhide)
            setOnClickListener {
                Overlay.unhide(context)
                isEnabled = false
            }
        }.also(content::addView)
    }

    override fun onResume() {
        super.onResume()
        serviceStatus.setText(if (serviceEnabled()) R.string.service_on else R.string.service_off)
        refreshMicrophone()
        unhide.isEnabled = Overlay.isHidden(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refreshMicrophone()

    private fun refreshMicrophone() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphoneStatus.setText(if (granted) R.string.microphone_on else R.string.microphone_off)
        grantMicrophone.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun change(next: ButtonStyle) {
        style = next
        next.save(prefs)
        restyle()
    }

    private fun restyle() {
        val left = style.placement == ButtonPlacement.LEFT_OF_MIC
        gapLabel.text = getString(R.string.gap_label, style.gapPx)
        gapLabel.isEnabled = left
        gapSlider.isEnabled = left
        sizeLabel.text = getString(R.string.size_label, style.sizePct)
        opacityLabel.text = getString(R.string.opacity_label, style.opacityPct)
        val side = dp(PREVIEW_DP * style.sizePct / 100)
        for ((view, mode) in listOf(previewIdle to ButtonMode.IDLE, previewRecording to ButtonMode.RECORDING)) {
            view.background = Overlay.buttonDrawable(style.color(mode), density)
            view.layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = dp(12) }
        }
        mark(idleSwatches, style.idleRgb)
        mark(recordingSwatches, style.recordingRgb)
    }

    private fun swatches(onPick: (Int) -> Unit) = LinearLayout(this).apply {
        setPadding(0, dp(4), 0, dp(12))
        for (rgb in ButtonStyle.PRESETS) {
            addView(View(context).apply {
                tag = rgb
                layoutParams = LinearLayout.LayoutParams(dp(SWATCH_DP), dp(SWATCH_DP)).apply { marginEnd = dp(8) }
                setOnClickListener { onPick(rgb) }
            })
        }
    }

    private fun mark(row: LinearLayout, selected: Int) {
        val ring = TypedValue().also { theme.resolveAttribute(android.R.attr.colorForeground, it, true) }.data
        for (i in 0 until row.childCount) {
            val swatch = row.getChildAt(i)
            val rgb = swatch.tag as Int
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(rgb or (0xFF shl 24))
                if (rgb == selected) setStroke(dp(3), ring)
            }
        }
    }

    private fun slider(range: IntRange, value: Int, step: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        max = (range.last - range.first) / step
        progress = (value - range.first) / step
        setPadding(dp(12), dp(8), dp(12), dp(16))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(range.first + progress * step)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun header(title: Int) = text(getString(title), 13f, bold = true).apply {
        isAllCaps = true
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun text(value: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val self = ComponentName(this, DictationService::class.java)
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).contains(self)
    }

    private companion object {
        const val PREVIEW_DP = 44
        const val SWATCH_DP = 34
        const val SLIDER_STEP = 5
        const val GAP_STEP = 2
    }
}
