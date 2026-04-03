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
            // Title: "In 500 ft - Turn right"
            // Text: "Main St"
            Log.d(TAG, "Maps Notification Intercepted - Title: $title, Text: $text")

            // Translating string to byte array payload
            val payload = com.pranava.tvsbridge.utils.NavigationTranslator.getPayloadForNavigation(title, text)

            // Forward payload to BLE Service (DISABLED FOR PHASE 1 TESTING)
            /* try {
                val intent = android.content.Intent(this, BluetoothLeService::class.java).apply {
                    action = BluetoothLeService.ACTION_SEND_NAVIGATION
                    putExtra(BluetoothLeService.EXTRA_PAYLOAD, payload)
                }
                startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE Service: ${e.message}")
            } */
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == MAPS_PACKAGE) {
            Log.d(TAG, "Maps Notification removed. Navigation possibly ended.")
        }
    }
}
