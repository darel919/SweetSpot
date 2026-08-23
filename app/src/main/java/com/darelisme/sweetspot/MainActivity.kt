package com.darelisme.sweetspot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class MainActivity : Activity() {

    companion object {
        private const val TAG = "SweetSpot"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The persistent floating overlay is owned and shown by SweetSpotService,
        // so the Activity has no UI of its own — it just ensures the service is
        // running, then gets out of the way.
        startSweetSpotService()
        finish()
    }

    /**
     * Ensure the persistent audio service (engine + web server + overlay) is
     * running. The Activity never owns or releases the global Equalizer.
     */
    private fun startSweetSpotService() {
        try {
            val intent = Intent(this, SweetSpotService::class.java).apply {
                action = SweetSpotService.ACTION_START
                putExtra(SweetSpotService.EXTRA_SHOW_UI, true)
            }
            startForegroundService(intent)
            Log.i(TAG, "Requested SweetSpotService start")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SweetSpotService", e)
        }
    }
}
