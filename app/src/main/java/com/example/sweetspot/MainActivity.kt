package com.example.sweetspot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.util.Log
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val TAG = "SweetSpot"
        private const val ACTION_PRESET = "com.example.sweetspot.PRESET"
    }

    private lateinit var status: TextView
    private var equalizer: Equalizer? = null

    private val presetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("preset", 0)) {
                1 -> applyPreset1()
                2 -> applyPreset2()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 28f
            setPadding(40, 40, 40, 40)
        }

        setContentView(status)

        equalizer = Equalizer(1000, 0).apply {
            enabled = true
        }

        registerReceiver(
            presetReceiver,
            IntentFilter(ACTION_PRESET),
            RECEIVER_EXPORTED
        )

        applyPreset1()
    }

    private fun applyPreset1() {
        val eq = equalizer ?: return

        // Original TCL values we measured.
        eq.setBandLevel(0, 300)  // 60 Hz +3 dB
        eq.setBandLevel(1, 0)
        eq.setBandLevel(2, 0)
        eq.setBandLevel(3, 0)
        eq.setBandLevel(4, 300)  // 14 kHz +3 dB

        status.text = "SweetSpot\n\nPreset 1 — Original"
        Log.i(TAG, "PRESET 1: ORIGINAL")
    }

    private fun applyPreset2() {
        val eq = equalizer ?: return

        eq.setBandLevel(0, 0)
        eq.setBandLevel(1, -1500)
        eq.setBandLevel(2, -1500)
        eq.setBandLevel(3, -1500)
        eq.setBandLevel(4, 0)

        status.text = "SweetSpot\n\nPreset 2 — Test"
        Log.i(TAG, "PRESET 2: TEST")
    }

    override fun onDestroy() {
        unregisterReceiver(presetReceiver)

        equalizer?.release()
        equalizer = null

        super.onDestroy()
    }
}