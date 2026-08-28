package com.darelisme.sweetspot.service

import com.darelisme.sweetspot.audio.engine.ProfileStore
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SweetSpotBoot"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        if (!ProfileStore(appContext).isStartOnBootEnabled()) {
            Log.i(TAG, "Boot completed — start on boot is disabled; leaving service stopped")
            return
        }
        Log.i(TAG, "Boot completed — starting SweetSpotService silently")
        try {
            val serviceIntent = Intent(appContext, SweetSpotService::class.java).apply {
                action = SweetSpotService.ACTION_START
                putExtra(SweetSpotService.EXTRA_SHOW_UI, false)
                putExtra(EXTRA_START_REASON, SweetSpotStartReason.BOOT_COMPLETED.name)
            }
            appContext.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service on boot", e)
        }
    }
}
