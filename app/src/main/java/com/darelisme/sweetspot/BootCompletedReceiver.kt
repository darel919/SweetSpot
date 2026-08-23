package com.darelisme.sweetspot

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
        Log.i(TAG, "Boot completed — starting SweetSpotService (UI hidden)")
        try {
            val serviceIntent = Intent(context, SweetSpotService::class.java).apply {
                action = SweetSpotService.ACTION_START
                putExtra(SweetSpotService.EXTRA_SHOW_UI, false)
            }
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service on boot", e)
        }
    }
}
