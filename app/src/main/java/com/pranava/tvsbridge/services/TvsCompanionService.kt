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

        // 2. Show a temporary Heads-Up Notification so you know it worked
        showWakeUpNotification()

        // 3. Start the actual background connection service
        val intent = Intent(this, BluetoothLeService::class.java).apply {
            putExtra("mac_address", address)
        }
        startForegroundService(intent)
    }

    override fun onDeviceDisappeared(address: String) {
        Log.d(TAG, "Device disappeared: $address")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "TVS Scooter Disconnected", Toast.LENGTH_SHORT).show()
        }
    }

//    private fun showWakeUpNotification() {
//        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//
//        // Create a high-priority channel so it pops up on the screen (Heads-Up Notification)
//        val channel = NotificationChannel(
//            CHANNEL_ID,
//            "Scooter Connection Alerts",
//            NotificationManager.IMPORTANCE_HIGH
//        ).apply {
//            description = "Alerts when the scooter turns on and off"
//        }
//        notificationManager.createNotificationChannel(channel)
//
//        val intent = Intent(this, MainActivity::class.java)
//        val pendingIntent = PendingIntent.getActivity(
//            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
//        )
//
//        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
//            .setSmallIcon(android.R.drawable.ic_dialog_info)
//            .setContentTitle("TVS Scooter Detected")
//            .setContentText("Your app woke up successfully and is connecting.")
//            .setPriority(NotificationCompat.PRIORITY_HIGH)
//            .setAutoCancel(true) // Dismisses when clicked
//            .setContentIntent(pendingIntent)
//            .build()
//
//        notificationManager.notify(NOTIFICATION_ID, notification)
//    }
}
