package com.pranava.tvsbridge.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class BluetoothLeService : Service() {

    private val TAG = "BluetoothLeService"
    private var bluetoothGatt: BluetoothGatt? = null
    
    // Coroutine Scope for BLE operations
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Placeholder TVS UUIDs (Replace with real ones from Wireshark Phase 2)
    private val TVS_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb") 
    private val TVS_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb") 

    companion object {
        const val ACTION_SEND_NAVIGATION = "com.pranava.tvsbridge.action.SEND_NAVIGATION"
        const val EXTRA_PAYLOAD_1 = "com.pranava.tvsbridge.extra.PAYLOAD_1"
        const val EXTRA_PAYLOAD_2 = "com.pranava.tvsbridge.extra.PAYLOAD_2"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Started service, not bound
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Auto-connect if associated
        val prefs = getSharedPreferences("tvs_prefs", Context.MODE_PRIVATE)
        val scooterMac = prefs.getString("scooter_mac", null)
        
        if (scooterMac != null && bluetoothGatt == null) {
            connectToDevice(scooterMac)
        }

        if (intent?.action == ACTION_SEND_NAVIGATION) {
            val payload1 = intent.getByteArrayExtra(EXTRA_PAYLOAD_1)
            val payload2 = intent.getByteArrayExtra(EXTRA_PAYLOAD_2)
            if (payload1 != null && payload2 != null) {
                sendNavigationData(payload1, payload2)
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "ble_service_channel"
        val channel = NotificationChannel(
            channelId,
            "BLE Connection Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        @Suppress("DEPRECATION") // Assuming a simple notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TVS Bridge Active")
            .setContentText("Maintaining connection to dashboard...")
            .build()
            
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val device: BluetoothDevice? = adapter.getRemoteDevice(deviceAddress)
        
        device?.let {
            Log.d(TAG, "Attempting to connect to $deviceAddress")
            bluetoothGatt = it.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered. Ready to send data.")
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNavigationData(payload1: ByteArray, payload2: ByteArray) {
        serviceScope.launch {
            bluetoothGatt?.let { gatt ->
                val service = gatt.getService(TVS_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(TVS_CHAR_UUID)
                
                characteristic?.let {
                    // Write Packet 1 (ZP Control Payload)
                    @Suppress("DEPRECATION")
                    it.value = payload1
                    @Suppress("DEPRECATION")
                    var success = gatt.writeCharacteristic(it)
                    Log.d(TAG, "Payload 1 (ZP/Control) sent. Success: $success")

                    // Delay slightly to prevent dropping packets on BLE MTU queue
                    kotlinx.coroutines.delay(400)

                    // Write Packet 2 ([J String Payload)
                    @Suppress("DEPRECATION")
                    it.value = payload2
                    @Suppress("DEPRECATION")
                    success = gatt.writeCharacteristic(it)
                    Log.d(TAG, "Payload 2 ([J/Text) sent. Success: $success")
                } ?: run {
                    Log.e(TAG, "TVS Characteristic not found.")
                }
            } ?: run {
                Log.e(TAG, "BluetoothGatt is null. Not connected to scooter.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
