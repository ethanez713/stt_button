package com.ezhou.sttbutton

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var unhide: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this)
        unhide = Button(this).apply {
            setText(R.string.unhide)
            setOnClickListener {
                Overlay.unhide(context)
                isEnabled = false
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(status)
            addView(Button(context).apply {
                setText(R.string.open_accessibility)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            addView(TextView(context).apply { setText(R.string.restricted_settings_hint) })
            addView(TextView(context).apply { setText(R.string.drag_hint) })
            addView(unhide)
            addView(TextView(context).apply { setText(R.string.licenses) })
        })
    }

    override fun onResume() {
        super.onResume()
        status.setText(if (serviceEnabled()) R.string.service_on else R.string.service_off)
        unhide.isEnabled = Overlay.isHidden(this)
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val self = ComponentName(this, DictationService::class.java)
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).contains(self)
    }
}
