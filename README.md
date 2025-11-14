## 📍 Custom Background Location Tracking (Flutter + Native Android)

A Flutter application that provides continuous background location updates using a native Android foreground service, communicating with Flutter through MethodChannel and EventChannel.

This setup is built for reliable real-time location tracking — even when the app is killed or in the background — and fully supports Android 14 (API 34/35).

---

🚀 Features

- ✅ Continuous location tracking (foreground + background)
- ✅ Works even when app is closed
- ✅ Uses native Foreground Service (Kotlin)
- ✅ Communicates via EventChannel for live updates
- ✅ Flutter UI shows current address and coordinates
- ✅ Persistent service state via SharedPreferences
- ✅ Android 14 (API 34+) compatible with FOREGROUND_SERVICE_LOCATION
- ✅ Handles runtime permissions (foreground + background)

---


```bash
🏗️ Project Structure
lib/
 ├── main.dart                 # Flutter app entry
 ├── location/
 │   ├── location_service.dart # Communicates with native service
 │   ├── location_permission_dialog.dart # Custom permission dialog UI
android/
 ├── app/
 │   └── src/main/
 │       ├── AndroidManifest.xml   # Contains all required permissions
 │       ├── kotlin/com/example/custom_location_tracking/
 │       │   └── LocationService.kt # Native foreground service
```

---


## 🧩 1. Flutter Setup
✅ main.dart
```dart
Initialize Flutter bindings, start the native location service, and ensure service is alive.

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final locationService = LocationService();
  await locationService.init();
  await locationService.syncWithPrefs();
  await locationService.ensureServiceAlive();

  runApp(const MyApp());
}
```
⚠️ Always call WidgetsFlutterBinding.ensureInitialized() before using platform channels or SharedPreferences.


---


✅ Permission Handling

You must handle location permissions before starting the service:

```dart
if (Platform.isAndroid && permission == LocationPermission.whileInUse) {
  LocationPermission bg = await Geolocator.requestPermission();
  if (bg != LocationPermission.always) return false;
}
```

For Android 14+, both fine and background permissions must be granted.

---


⚙️ 2. Android Native Setup
✅ AndroidManifest.xml

Ensure you include all required permissions:
``` bash
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.custom_location_tracking">

    <!-- Location permissions -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

    <!-- Foreground Service permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

    <!-- Optional -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:label="custom_location_tracking"
        android:icon="@mipmap/ic_launcher">

        <service
            android:name=".LocationService"
            android:exported="false"
            android:foregroundServiceType="location" />

        <!-- Optional boot restart -->
        <!--
        <receiver
            android:name=".BootCompletedReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
        -->
    </application>
</manifest>
```

---


# ✅ LocationService.kt (Native Android Service)
```dart
package com.example.custom_location_tracking

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.content.Context
import android.location.Location
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.EventChannel
import com.google.android.gms.location.*

class LocationService : Service() {
    private lateinit var fusedClient: FusedLocationProviderClient
    private var eventSink: EventChannel.EventSink? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val channelId = "location_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Location Service", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("📍 Tracking location")
            .setContentText("Service running in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).build()
        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location? = result.lastLocation
            loc?.let {
                val data = mapOf("latitude" to it.latitude, "longitude" to it.longitude)
                eventSink?.success(data)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

---

# 🔄 3. Flutter ↔ Native Communication

The Dart LocationService uses a MethodChannel to send commands (start/stop)
and an EventChannel to receive continuous location updates:

```dart

static const _eventChannel = EventChannel('background_location/events');
static const _methodChannel = MethodChannel('background_location/methods');

Stream<Map<String, dynamic>> get locationStream =>
    _eventChannel.receiveBroadcastStream().map((event) => Map<String, dynamic>.from(event));

Future<void> start() => _methodChannel.invokeMethod('startService');
Future<void> stop() => _methodChannel.invokeMethod('stopService');
```

---

📲 Example Usage (in MyHomePage)
```dart
ElevatedButton(
  onPressed: () async {
    await LocationService().start();
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text("📍 Location tracking started")),
    );
  },
  child: const Text("Start Location Service"),
),

```

# 🧠 Troubleshooting
| Problem                                          | Cause                                                            | Fix                                                                                     |
| ------------------------------------------------ | ---------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `Binding has not yet been initialized`           | Used `SharedPreferences` or platform code before Flutter binding | Add `WidgetsFlutterBinding.ensureInitialized()`                                         |
| `No MaterialLocalizations found`                 | Showing dialog in `initState()`                                  | Wrap `getpermission()` in `addPostFrameCallback()`                                      |
| `SecurityException: FOREGROUND_SERVICE_LOCATION` | Missing Android 14+ permission                                   | Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />` |
| No updates when app is killed                    | Service not started in native                                    | Check `ensureServiceAlive()` or BootReceiver                                            |


---

# 🧾 License

This project is provided as-is for learning and integration.
You may modify and reuse it freely in your apps.

---
