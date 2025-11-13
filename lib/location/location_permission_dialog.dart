import 'package:flutter/material.dart';

class LocationPermissionDialog extends StatelessWidget {
  final VoidCallback onConfirm;
  final VoidCallback onCancel;

  const LocationPermissionDialog({
    super.key,
    required this.onConfirm,
    required this.onCancel,
  });

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      title: const Text('Enable Location Access'),
      content: const Text(
        'We use your location to track attendance and enable '
            'background updates.\n\n'
            'Please:\n'
            '1️⃣ Allow “Precise location”\n'
            '2️⃣ Tap “Allow all the time” on the next screen\n'
            '3️⃣ Make sure GPS is ON',
        style: TextStyle(height: 1.4),
      ),
      actions: [
        TextButton(
          onPressed: onCancel,
          child: const Text('Not now'),
        ),
        ElevatedButton(
          onPressed: onConfirm,
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.amber,
            foregroundColor: Colors.black,
          ),
          child: const Text('Continue'),
        ),
      ],
    );
  }
}
