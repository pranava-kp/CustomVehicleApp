package com.pranava.tvsbridge.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pranava.tvsbridge.MainActivity

class TvsCompanionService : CompanionDeviceService() {
    companion object {
        private const val TAG = "TvsCompanionService"
        private const val NOTIFICATION_ID = 999
        private const val CHANNEL_ID = "tvs_companion_alerts"
    }

    override fun onDeviceAppeared(address: String) {
        Log.d(TAG, "Device appeared: $address")
        
        // 1. Show a quick Toast on the Home Screen
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "🛴 TVS Scooter Detected! Connecting in background...", Toast.LENGTH_LONG).show()
        }

        // 2. Start the actual background connection service
        try {
            val intent = Intent(this, BluetoothLeService::class.java).apply {
                putExtra("mac_address", address)
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground BLE service: ${e.message}")
        }
    }

    override fun onDeviceDisappeared(address: String) {
        Log.d(TAG, "Device disappeared: $address")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "TVS Scooter Disconnected", Toast.LENGTH_SHORT).show()
        }
        
        // Optional: stop the BLE service when device disappears
        // stopService(Intent(this, BluetoothLeService::class.java))
    }
}
