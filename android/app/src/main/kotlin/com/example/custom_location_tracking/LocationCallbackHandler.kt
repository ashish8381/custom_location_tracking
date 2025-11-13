package com.example.custom_location_tracking

import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.*
import androidx.annotation.Keep

@Keep
object LocationCallbackHandler {
    private const val TAG = "LocationCallbackHandler"

    /**
     * Background-safe handler that fetches last known or fresh location.
     * Called from background task, alarm, or boot restart context.
     */
    @JvmStatic
    @Keep
    fun handle(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)

                // 🟢 Try last known location first
                val lastLocation: Location? = try {
                    Tasks.await(fusedClient.lastLocation)
                } catch (e: Exception) {
                    null
                }

                if (lastLocation != null) {
                    Log.d(
                        TAG,
                        "📍 Background last location: ${lastLocation.latitude}, ${lastLocation.longitude}"
                    )
                    // TODO: Send to your backend or store locally
                    return@launch
                }

                // 🟡 Request a single fresh location if last known is null
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L)
                    .setMinUpdateIntervalMillis(0L)
                    .build()

                val deferred = CompletableDeferred<Location?>()
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val loc = result.lastLocation
                        if (loc != null && !deferred.isCompleted) {
                            deferred.complete(loc)
                        }
                    }
                }

                fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

                val newLocation: Location? = withTimeoutOrNull(10_000L) { deferred.await() }
                fusedClient.removeLocationUpdates(callback)

                if (newLocation != null) {
                    Log.d(
                        TAG,
                        "📡 Background fetched location: ${newLocation.latitude}, ${newLocation.longitude}"
                    )
                    // TODO: Send newLocation to Firebase or server
                } else {
                    Log.w(TAG, "⚠️ Background location fetch timed out")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Background location handler failed: ${e.message}", e)

            }
        }

    }
}