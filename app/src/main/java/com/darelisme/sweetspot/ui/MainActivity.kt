package com.darelisme.sweetspot.ui

import com.darelisme.sweetspot.service.EXTRA_START_REASON
import com.darelisme.sweetspot.service.SweetSpotService
import com.darelisme.sweetspot.service.SweetSpotStartReason
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
                putExtra(EXTRA_START_REASON, SweetSpotStartReason.USER_LAUNCH.name)
            }
            startForegroundService(intent)
            Log.i(TAG, "Requested SweetSpotService start")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SweetSpotService", e)
        }
    }
}
