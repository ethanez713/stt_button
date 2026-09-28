package com.ezhou.sttbutton

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            addView(status)
            addView(Button(context).apply {
                setText(R.string.open_accessibility)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            addView(TextView(context).apply { setText(R.string.restricted_settings_hint) })
            addView(Button(context).apply {
                setText(R.string.reset_position)
                setOnClickListener {
                    Overlay.resetPosition(context)
                    Toast.makeText(context, R.string.position_reset, Toast.LENGTH_SHORT).show()
                }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        status.setText(if (serviceEnabled()) R.string.service_on else R.string.service_off)
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val self = ComponentName(this, DictationService::class.java)
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).contains(self)
    }
}
