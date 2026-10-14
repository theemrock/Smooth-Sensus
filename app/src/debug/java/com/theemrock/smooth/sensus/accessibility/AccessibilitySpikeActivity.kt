package com.theemrock.smooth.sensus.accessibility

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log

class AccessibilitySpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences(SPIKE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(SPIKE_ARMED, true)
            .apply()
        Log.i(SPIKE_TAG, "ARM {\"target\":\"settings_installed_accessibility_apps\"}")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        finish()
    }
}

internal const val SPIKE_TAG = "SMOOTH_A11Y_SPIKE"
internal const val SPIKE_PREFS = "accessibility_spike"
internal const val SPIKE_ARMED = "armed"
