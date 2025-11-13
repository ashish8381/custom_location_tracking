package com.example.custom_location_tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("RestartReceiver", "⏰ Alarm triggered — attempting to restart LocationService")

        // ✅ Prevent restart if user manually stopped it
        if (LocationService.isManuallyStopped) {
            Log.d("RestartReceiver", "🚫 Service was manually stopped — not restarting")
            return
        }

        try {
            val serviceIntent = Intent(context, LocationService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d("RestartReceiver", "✅ LocationService restarted successfully")

        } catch (e: Exception) {
            Log.e("RestartReceiver", "❌ Failed to restart LocationService", e)
        }
    }
}
