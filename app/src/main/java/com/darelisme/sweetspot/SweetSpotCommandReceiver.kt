package com.darelisme.sweetspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Exported bridge that forwards SweetSpot commands to [SweetSpotService].
 *
 * The service is intentionally [android.app.Service] exported=false, so external
 * callers (ADB during development, or a future companion app) cannot start it
 * directly. This receiver is exported and only forwards the known SweetSpot
 * actions, keeping the DSP service itself internal.
 */
class SweetSpotCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SweetSpot"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        val handled = when (action) {
            SweetSpotService.ACTION_START,
            SweetSpotService.ACTION_PRESET,
            SweetSpotService.ACTION_BYPASS -> true
            else -> false
        }
        if (!handled) {
            Log.w(TAG, "CommandReceiver: ignoring unknown action $action")
            return
        }

        val serviceIntent = Intent(context, SweetSpotService::class.java).apply {
            this.action = action
            if (intent.hasExtra(SweetSpotService.EXTRA_PRESET)) {
                putExtra(
                    SweetSpotService.EXTRA_PRESET,
                    intent.getIntExtra(SweetSpotService.EXTRA_PRESET, 1)
                )
            }
        }
        try {
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "CommandReceiver forwarded action $action to service")
        } catch (e: Exception) {
            Log.e(TAG, "CommandReceiver failed to start service", e)
        }
    }
}
