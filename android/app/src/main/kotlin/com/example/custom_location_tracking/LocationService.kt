package com.example.custom_location_tracking

import android.content.pm.ServiceInfo
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import io.flutter.plugin.common.EventChannel
import android.content.SharedPreferences

class LocationService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private var localCallback: LocationCallback? = null
    private lateinit var prefs: SharedPreferences

    companion object {
        @JvmStatic var isRunning: Boolean = false
        @JvmStatic var isManuallyStopped: Boolean = false

        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "LocationServiceChannel"
        private const val PREFS = "bg_location_prefs"
        private const val KEY_RUNNING = "is_running"
        private const val KEY_MANUAL_STOP = "is_manual_stop"

        private var staticEventSink: EventChannel.EventSink? = null

        @JvmStatic
        fun setEventSinkStatic(sink: EventChannel.EventSink?) {
            staticEventSink = sink
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Initialize fused client & request
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L
        )
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        // Restore flags from prefs
        isRunning = prefs.getBoolean(KEY_RUNNING, false)
        isManuallyStopped = prefs.getBoolean(KEY_MANUAL_STOP, false)

        // Create notification channel but don't start foreground here (startForeground in onStartCommand)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Background Location Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Tracking Active")
            .setContentText("Tracking location in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }


    private fun startLocationUpdates() {
        try {
            if (localCallback == null) {
                localCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (location in result.locations) {
                            sendLocationUpdate(location)
                        }
                    }
                }
            }

            fusedClient.requestLocationUpdates(
                locationRequest,
                localCallback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Started location updates")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
        }
    }

    private fun sendLocationUpdate(location: Location) {
        val data = mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "timestamp" to System.currentTimeMillis()
        )
        try {
            staticEventSink?.success(data)
        } catch (e: Exception) {
            Log.w(TAG, "EventSink write failed (maybe no listener): ${e.message}")
        }
        Log.d(TAG, "Location update: $data")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called (isManuallyStopped=$isManuallyStopped)")

        // Start foreground ASAP to avoid background execution limits
        startForegroundNotification()

        // Mark running state
        isRunning = true
        prefs.edit().putBoolean(KEY_RUNNING, true).apply()

        // If intent carries manual flag, update stored manual flag
        val manual = intent?.getBooleanExtra("manual", false) ?: false
        if (manual) {
            isManuallyStopped = true
            prefs.edit().putBoolean(KEY_MANUAL_STOP, true).apply()
        }

        // Only schedule restart alarm if NOT manually stopped
        if (!isManuallyStopped) {
            scheduleRestartAlarm()
        } else {
            cancelRestartAlarm() // ensure not scheduled
        }

        // Start location updates
        startLocationUpdates()
        return START_STICKY
    }

    private fun scheduleRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, RestartReceiver::class.java)
            val pendingFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getBroadcast(this, 0, restartIntent, pendingFlags)

            alarmManager.cancel(pendingIntent)

            val intervalMillis = 15 * 60 * 1000L // 15 minutes
            val triggerAt = System.currentTimeMillis() + intervalMillis

            // ✅ Fallback safely if exact alarms are restricted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    Log.d(TAG, "⏰ Exact restart alarm scheduled (allowed)")
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    Log.d(TAG, "⏰ Inexact restart alarm scheduled (fallback)")
                }
            } else {
                // ✅ Older Android: always allowed
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Log.d(TAG, "⏰ Exact restart alarm scheduled (legacy)")
            }
        } catch (e: SecurityException) {
            // ❗Handle gracefully instead of crashing
            Log.w(TAG, "⚠️ Exact alarm permission missing, using fallback", e)
            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val restartIntent = Intent(this, RestartReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(this, 0, restartIntent, PendingIntent.FLAG_IMMUTABLE)
                alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15 * 60 * 1000L, pendingIntent)
            } catch (inner: Exception) {
                Log.e(TAG, "❌ Failed fallback alarm scheduling", inner)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to schedule restart alarm", e)
        }
    }

    private fun cancelRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, RestartReceiver::class.java)
            val pendingFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getBroadcast(this, 0, restartIntent, pendingFlags)
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Restart alarm cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel restart alarm", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")

        // Stop updates & cleanup
        localCallback?.let {
            try {
                fusedClient.removeLocationUpdates(it)
                Log.d(TAG, "Stopped location updates")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove location updates: ${e.message}")
            }
        }
        localCallback = null

        // Mark not running (unless you want to preserve)
        isRunning = false
        prefs.edit().putBoolean(KEY_RUNNING, false).apply()

        // If manually stopped, keep that flag true (so restart won't happen)
        if (!isManuallyStopped) {
            // clear manual flag
            prefs.edit().putBoolean(KEY_MANUAL_STOP, false).apply()
        }

        cancelRestartAlarm()

        try {
            staticEventSink?.endOfStream()
        } catch (e: Exception) {
            Log.w(TAG, "EventSink end failed: ${e.message}")
        }
        staticEventSink = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
