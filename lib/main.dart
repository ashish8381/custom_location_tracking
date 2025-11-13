import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geocoding/geocoding.dart';
import 'package:geolocator/geolocator.dart';

import 'location/location_permission_dialog.dart';
import 'location/location_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // ✅ Initialize Background Location Service
  final locationService = LocationService();
  await locationService.init();

  // 🔄 Sync any previously saved state or start service if it was active
  await locationService.syncWithPrefs();

  // 🧩 Optionally, ensure background service auto-starts on boot or restart
  // This assumes you've implemented BootCompletedReceiver natively
  await locationService.ensureServiceAlive();

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // This widget is the root of your application.

  @override
  void initState() {
    // TODO: implement initState
    super.initState();

    WidgetsBinding.instance.addPostFrameCallback((_) {
      getpermission();
    });

  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Demo',
      theme: ThemeData(

        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
      ),
      home: const MyHomePage(title: 'Flutter Demo Home Page'),
    );
  }

  Future<bool> checkLocationPermission() async {
    bool proceed = false;

    // 🪄 Ask user nicely first
    await showDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => LocationPermissionDialog(
        onConfirm: () {
          proceed = true;
          Navigator.pop(ctx);
        },
        onCancel: () {
          proceed = false;
          Navigator.pop(ctx);
        },
      ),
    );

    if (!proceed) return false;

    try {
      // ✅ Step 1: GPS service enabled?
      bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        await Geolocator.openLocationSettings();
        serviceEnabled = await Geolocator.isLocationServiceEnabled();
        if (!serviceEnabled) return false;
      }

      // ✅ Step 2: Foreground permission
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) return false;
      }
      if (permission == LocationPermission.deniedForever) {
        await Geolocator.openAppSettings();
        return false;
      }

      // ✅ Step 3: Background permission (Android 10+)
      if (Platform.isAndroid && permission == LocationPermission.whileInUse) {
        LocationPermission bg = await Geolocator.requestPermission();
        if (bg != LocationPermission.always) return false;
      }

      // ✅ Step 4: Android 14+ FGS permission check (manifest only)
      if (Platform.isAndroid) {
        const channel = MethodChannel('background_location/methods');
        try {
          await channel.invokeMethod('checkServiceAlive');
        } catch (_) {}
      }

      return true;
    } catch (e) {
      debugPrint("❌ Error requesting location permission: $e");
      return false;
    }
  }

  Future<void> getpermission() async {

    bool hasPermission = await checkLocationPermission();

    if (!hasPermission) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text("Location permission is required to continue."),
          backgroundColor: Colors.red,
        ),
      );
      return; // stop login
    }

  }



}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  String? currentAddress = "Fetching location...";
  Position? currentPosition;

  StreamSubscription<Map<String, dynamic>>? _locationSub;

  Future<void> _listenToBackgroundLocation() async {
    // Ensure service is alive
    await LocationService().ensureServiceAlive();

    // Cancel any existing listener
    await _locationSub?.cancel();

    // 🟢 Start listening to continuous background updates
    _locationSub = LocationService().locationStream.listen((data) async {
      if (!mounted || data['latitude'] == null) return;

      final lat = (data['latitude'] as num).toDouble();
      final lon = (data['longitude'] as num).toDouble();

      // Convert to human-readable address
      final address = await _reverseGeocode(lat, lon);

      if (mounted) {
        setState(() {
          currentAddress = "${address} Lat: ${lat.toStringAsFixed(5)}, Long: ${lon.toStringAsFixed(5)}";
        });
      }
    }, onError: (err) {
      debugPrint("❌ Location stream error: $err");
    });

    // 🟡 Also show cached/latest one immediately
    final latest = await LocationService().getLatestLocation();
    if (mounted && latest != null) {
      final lat = (latest['latitude'] as num).toDouble();
      final lon = (latest['longitude'] as num).toDouble();
      final address = await _reverseGeocode(lat, lon);

      setState(() {
        currentAddress = "${address} Lat: ${lat.toStringAsFixed(5)}, Long: ${lon.toStringAsFixed(5)}";
      });
    }
  }

  @override
  void dispose() {
    _locationSub?.cancel();
    super.dispose();
  }


  Future<String?> _reverseGeocode(double lat, double lon) async {
    try {
      final placemarks = await placemarkFromCoordinates(lat, lon);
      if (placemarks.isNotEmpty) {
        final p = placemarks.first;
        // Example: “Koramangala, Bengaluru, Karnataka, India”
        return [
          if (p.subLocality?.isNotEmpty ?? false) p.subLocality,
          if (p.locality?.isNotEmpty ?? false) p.locality,
          if (p.administrativeArea?.isNotEmpty ?? false) p.administrativeArea,
          if (p.country?.isNotEmpty ?? false) p.country,
        ].whereType<String>().join(', ');
      }
    } catch (e) {
      debugPrint("❌ Reverse-geocoding failed: $e");
    }
    return null;
  }

@override
  void initState() {
    // TODO: implement initState
    super.initState();
    _listenToBackgroundLocation();

  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: Text(widget.title),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            // Title
            const Text(
              '📍 Location Details:',
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
            ),

            const SizedBox(height: 12),

            // Display location info (latitude/longitude)
            Text(
              currentAddress ?? 'No location data yet.',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 16),
            ),

            const SizedBox(height: 30),

            // Start service button
            ElevatedButton.icon(
              onPressed: () async {
                bool running = await LocationService().isRunning();
                debugPrint("📡 Service currently running: $running");

                if (!running) {
                  await LocationService().start();
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text("Checked In — tracking started."),
                      backgroundColor: Colors.green,
                    ),
                  );
                } else {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text("Service already running."),
                      backgroundColor: Colors.orangeAccent,
                    ),
                  );
                }
              },
              icon: const Icon(Icons.play_arrow),
              label: const Text('Start Location Service'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
              ),
            ),
            ElevatedButton.icon(
              onPressed: () async {
                // 🛑 Stop background location service (manual stop)
                      await LocationService().stop();

                      // ✅ Do NOT call ensureServiceAlive here (it restarts service)
                      bool running = await LocationService().isRunning(); // optional helper
                      debugPrint("🛑 Service running after stop: $running");

                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text("Checked Out — tracking stopped."),
                          backgroundColor: Colors.redAccent,
                        ),
                      );
              },
              icon: const Icon(Icons.play_arrow),
              label: const Text('Stop Location Service'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
