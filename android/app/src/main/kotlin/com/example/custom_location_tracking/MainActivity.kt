package com.example.custom_location_tracking

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val METHOD_CHANNEL = "background_location/methods"
    private val EVENT_CHANNEL = "background_location/events"

    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        Log.d("MainActivity", "⚙️ FlutterEngine configured")

        // ✅ Method Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startService" -> {
                        startLocationService()
                        result.success(true)
                    }

                    "stopService" -> {
                        val manual = call.argument<Boolean>("manual") ?: false
                        LocationService.isManuallyStopped = manual

                        val stopIntent = Intent(this, LocationService::class.java)
                        stopService(stopIntent)

                        Log.d("MainActivity", "🛑 stopService() called (manual=$manual)")
                        result.success(true)
                    }

                    "checkServiceAlive" -> {
                        // ✅ Checks persisted + static flag
                        val alive = LocationService.isRunning
                        Log.d("MainActivity", "📡 checkServiceAlive → $alive")
                        result.success(alive)
                    }

                    else -> result.notImplemented()
                }
            }

        // ✅ Event Channel
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    LocationService.setEventSinkStatic(eventSink)
                    Log.d("MainActivity", "📡 EventChannel attached")
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                    LocationService.setEventSinkStatic(null)
                    Log.d("MainActivity", "❌ EventChannel detached")
                }
            })
    }

    private fun startLocationService() {
        try {
            val intent = Intent(this, LocationService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            Log.d("MainActivity", "🚀 startLocationService() called")

        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Failed to start LocationService", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure the sink reference is cleaned up when activity is destroyed
        LocationService.setEventSinkStatic(null)
        Log.d("MainActivity", "💨 Activity destroyed, event sink cleared")
    }
}
