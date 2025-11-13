// location_service.dart (Dart)
import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

class LocationService {
  static const MethodChannel _method =
  MethodChannel('background_location/methods');
  static const EventChannel _events =
  EventChannel('background_location/events');

  static final LocationService _instance = LocationService._();
  factory LocationService() => _instance;
  LocationService._();

  Stream<Map<String, dynamic>>? _locationStream;
  StreamSubscription? _locationSub;
  EventChannel? _eventChannel;

  /// Public stream for callers
  Stream<Map<String, dynamic>> get locationStream {
    _locationStream ??= _events
        .receiveBroadcastStream()
        .map((e) => _safeMapFromEvent(e))
        .handleError((err) {
      debugPrint('LocationService stream error: $err');
    });
    return _locationStream!;
  }

  Map<String, dynamic> _safeMapFromEvent(dynamic e) {
    try {
      if (e is Map) return Map<String, dynamic>.from(e as Map);
      if (e is Map<dynamic, dynamic>) {
        return e.map((k, v) => MapEntry(k.toString(), v));
      }
      return <String, dynamic>{};
    } catch (err) {
      debugPrint('Failed to parse event: $err');
      return <String, dynamic>{};
    }
  }

  /// Returns the latest known location (from cache or next event).
  Future<Map<String, dynamic>?> getLatestLocation({
    Duration timeout = const Duration(seconds: 5),
  }) async {
    final prefs = await SharedPreferences.getInstance();

    // 🗺 Try reading cached coordinates first
    final cachedLat = prefs.getDouble('last_latitude');
    final cachedLon = prefs.getDouble('last_longitude');
    if (cachedLat != null && cachedLon != null) {
      return {
        'latitude': cachedLat,
        'longitude': cachedLon,
        'timestamp': prefs.getInt('last_timestamp') ?? DateTime.now().millisecondsSinceEpoch,
      };
    }

    // 🕒 Otherwise, wait for the next location event
    final completer = Completer<Map<String, dynamic>?>();
    late StreamSubscription sub;

    sub = locationStream.listen((data) {
      if (!completer.isCompleted && data['latitude'] != null) {
        completer.complete(data);
        sub.cancel();
      }
    });

    // Timeout fallback
    Future.delayed(timeout, () {
      if (!completer.isCompleted) completer.complete(null);
      sub.cancel();
    });

    return completer.future;
  }


  Future<void> init() async {
    debugPrint('🔧 LocationService: init completed');
  }

  Future<void> syncWithPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    final wasRunning = prefs.getBool(_prefKey) ?? false;
    final manualStop = prefs.getBool(_manualKey) ?? false;
    debugPrint('🔁 syncWithPrefs wasRunning=$wasRunning manualStop=$manualStop');
    if (wasRunning && !manualStop) {
      try {
        await start();
        debugPrint('♻️ LocationService: restarted from prefs');
      } catch (e, st) {
        debugPrint('❌ failed to restart from prefs: $e\n$st');
      }
    }
  }

  Future<bool> ensureServiceAlive() async {
    try {
      final bool alive = await _method.invokeMethod('checkServiceAlive');
      debugPrint('🔍 LocationService: native alive? $alive');
      if (!alive) {
        // attempt to start it
        await start();
        return true;
      }
      return alive;
    } on PlatformException catch (e) {
      debugPrint('⚠️ LocationService.ensureServiceAlive error: $e');
      return false;
    }
  }

  Future<void> start() async {
    try {
      await _method.invokeMethod('startService');
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_prefKey, true);
      await prefs.setBool(_manualKey, false);
      _bindEvents();
    } on PlatformException catch (e) {
      debugPrint('❌ LocationService.start error: $e');
      rethrow;
    }
  }

  Future<void> stop({bool manual = true}) async {
    try {
      await _method.invokeMethod('stopService', {'manual': manual});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_prefKey, false);
      await prefs.setBool(_manualKey, manual);
      _unbindEvents();
    } on PlatformException catch (e) {
      debugPrint('❌ LocationService.stop error: $e');
      rethrow;
    }
  }

  Future<bool> isRunning() async {
    try {
      final bool alive = await _method.invokeMethod('checkServiceAlive');
      debugPrint('📡 LocationService: native alive? $alive');
      return alive;
    } catch (e) {
      debugPrint('⚠️ LocationService.isRunning error: $e');
      return false;
    }
  }

  void _bindEvents() {
    if (_locationSub != null) return;

    _locationSub = _events.receiveBroadcastStream().listen((event) async {
      final map = _safeMapFromEvent(event);
      debugPrint('📍 location event: $map');

      // 🧭 Save latest coordinates
      if (map['latitude'] != null && map['longitude'] != null) {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setDouble('last_latitude', (map['latitude'] as num).toDouble());
        await prefs.setDouble('last_longitude', (map['longitude'] as num).toDouble());
        await prefs.setInt('last_timestamp', DateTime.now().millisecondsSinceEpoch);
      }

    }, onError: (err) {
      debugPrint('EventChannel error: $err');
      Future.delayed(const Duration(seconds: 3), () => _reconnectEvents());
    }, onDone: () {
      debugPrint('EventChannel done, trying to reconnect in 3s');
      Future.delayed(const Duration(seconds: 3), () => _reconnectEvents());
    }, cancelOnError: false);
  }


  void _unbindEvents() {
    _locationSub?.cancel();
    _locationSub = null;
  }

  Future<void> _reconnectEvents() async {
    if (await isRunning()) {
      _unbindEvents();
      _bindEvents();
      debugPrint('🔁 Rebound EventChannel');
    } else {
      debugPrint('🔌 Not running — skipping event rebind');
    }
  }

  static const String _prefKey = 'location_running';
  static const String _manualKey = 'location_manual_stop';
}
