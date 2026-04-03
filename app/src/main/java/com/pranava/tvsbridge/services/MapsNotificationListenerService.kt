package com.pranava.tvsbridge.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MapsNotificationListenerService : NotificationListenerService() {

    private val TAG = "MapsNotificationListener"
    private val MAPS_PACKAGE = "com.google.android.apps.maps"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        if (sbn.packageName == MAPS_PACKAGE) {
            val notification = sbn.notification
            val extras = notification.extras

            val title = extras.getString(Notification.EXTRA_TITLE)
            val text = extras.getString(Notification.EXTRA_TEXT)

            // Google Maps constantly updates its notification state in the background (like when switching apps).
            // We ignore these "ghost" updates if there's no actual instruction text.
            if (title.isNullOrEmpty() && text.isNullOrEmpty()) {
                return
            }

            // Typically:
            // Title: "In 500 ft - Turn right" or "Turn right"
            // Text: "Main St"
            Log.d(TAG, "Maps Notification Intercepted - Title: $title, Text: $text")

            // Simple parser for distance (Fallback to 0 if not found)
            var distanceMeters = 0
            if (title != null && title.startsWith("In ")) {
                val parts = title.split(" - ")
                if (parts.isNotEmpty()) {
                    val distString = parts[0].replace("In ", "").trim()
                    distanceMeters = parseDistanceToMeters(distString)
                }
            }

            // Using dummy ETA/Total settings for now
            val etaInSeconds: Long = 600
            val totalDistanceMeters = 5000 
            
            // Generate dual 20-byte TVS protocol payloads
            val instructionText = title ?: "Proceed"
            val payloads = com.pranava.tvsbridge.services.NavigationTranslator.createNavigationPayloads(
                distanceMeters,
                etaInSeconds,
                totalDistanceMeters,
                instructionText
            )

            // Forward both payloads to BLE Service
            try {
                val intent = android.content.Intent(this, BluetoothLeService::class.java).apply {
                    action = BluetoothLeService.ACTION_SEND_NAVIGATION
                    putExtra(BluetoothLeService.EXTRA_PAYLOAD_1, payloads.first)
                    putExtra(BluetoothLeService.EXTRA_PAYLOAD_2, payloads.second)
                }
                startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE Service: ${e.message}")
            }
        }
    }

    private fun parseDistanceToMeters(distPart: String): Int {
        try {
            if (distPart.contains("ft", ignoreCase = true)) {
                val value = distPart.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                return (value * 0.3048).toInt()
            } else if (distPart.contains("mi", ignoreCase = true)) {
                val value = distPart.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                return (value * 1609.34).toInt()
            } else if (distPart.contains("km", ignoreCase = true)) {
                val value = distPart.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                return (value * 1000).toInt()
            } else if (distPart.contains("m", ignoreCase = true)) {
                val value = distPart.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                return value.toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing distance failed: $distPart")
        }
        return 0
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == MAPS_PACKAGE) {
            Log.d(TAG, "Maps Notification removed. Navigation possibly ended.")
        }
    }
}
