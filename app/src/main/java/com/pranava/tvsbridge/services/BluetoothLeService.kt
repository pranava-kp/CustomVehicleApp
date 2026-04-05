package com.pranava.tvsbridge.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.UUID

class BluetoothLeService : Service() {

    private val TAG = "BluetoothLeService"
    private var bluetoothGatt: BluetoothGatt? = null

    // Coroutine Scope for BLE operations
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var heartbeatJob: Job? = null

    // Unified Queue State Variables (The Fix!)
    private var currentPayload1: ByteArray? = null
    private var currentPayload2: ByteArray? = null
    private var isNavigating = false

    // TVS Jupiter 125 UUIDs
    private val TVS_SERVICE_UUID = UUID.fromString("5456534d-5647-5341-5342-454e544f5251")
    private val TVS_WRITE_CHAR_UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val navBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SEND_NAVIGATION) {
                val payload1 = intent.getByteArrayExtra(EXTRA_PAYLOAD_1)
                val payload2 = intent.getByteArrayExtra(EXTRA_PAYLOAD_2)
                if (payload1 != null && payload2 != null) {
                    Log.d(TAG, "Received live navigation data from Maps via Broadcast. Queueing for next heartbeat.")
                    currentPayload1 = payload1
                    currentPayload2 = payload2
                    isNavigating = true
                } else if (intent.getBooleanExtra(EXTRA_STOP_NAVIGATION, false)) {
                    Log.d(TAG, "Received stop navigation broadcast. Clearing queue.")
                    isNavigating = false
                    currentPayload1 = null
                    currentPayload2 = null
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT! Shifting to High Priority...")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                serviceScope.launch {
                    delay(100)
                    Log.d(TAG, "Requesting MTU Expansion...")
                    gatt.requestMtu(256)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.")
                heartbeatJob?.cancel()
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU successfully negotiated to: $mtu. Discovering services...")
                gatt.discoverServices()
            } else {
                Log.e(TAG, "MTU negotiation failed. Attempting to proceed anyway...")
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered. Hunting for CCCD...")
                val service = gatt.getService(TVS_SERVICE_UUID)
                if (service != null) {
                    var descriptorFound = false
                    for (characteristic in service.characteristics) {
                        val descriptor = characteristic.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            Log.d(TAG, "Found CCCD on characteristic: ${characteristic.uuid}")
                            gatt.setCharacteristicNotification(characteristic, true)
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            Log.d(TAG, "Wrote CCCD descriptor. Waiting for callback...")
                            descriptorFound = true
                            break
                        }
                    }
                    if (!descriptorFound) Log.e(TAG, "FATAL: No CCCD found!")
                } else {
                    Log.e(TAG, "TVS Service UUID not found.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                Log.d(TAG, "Dashboard unlocked successfully! Initiating Handshake...")
                serviceScope.launch {
                    sendHandshakeSequence(gatt)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startKeepAlive()

        // Register the broadcast receiver to catch Live Navigation Data from MapsNotificationListenerService
        val filter = IntentFilter(ACTION_SEND_NAVIGATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(navBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(navBroadcastReceiver, filter)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val macAddress = intent?.getStringExtra("mac_address")
        val payload1 = intent?.getByteArrayExtra(EXTRA_PAYLOAD_1)
        val payload2 = intent?.getByteArrayExtra(EXTRA_PAYLOAD_2)

        // 1. Initial Connection Trigger
        if (macAddress != null && bluetoothGatt == null) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(macAddress)
            Log.d(TAG, "Connecting to scooter: $macAddress")
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }

        // 2. Dummy Data Trigger (If invoked via startService manually)
        if (payload1 != null && payload2 != null) {
            Log.d(TAG, "Received navigation data via Intent. Queueing for next heartbeat.")
            currentPayload1 = payload1
            currentPayload2 = payload2
            isNavigating = true
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendHandshakeSequence(gatt: BluetoothGatt) {
        val service = gatt.getService(TVS_SERVICE_UUID)
        val writeChar = service?.getCharacteristic(TVS_WRITE_CHAR_UUID) ?: return

        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        delay(190)

        // Packet 1: Initial Handshake Z packet as seen in official logs
        val zpPacket = NavigationTranslator.createMobileStatusPacket(this)
        writeChar.value = zpPacket
        gatt.writeCharacteristic(writeChar)
        delay(100)

        // Packet 2: Rider Name [R
        val rPacket = NavigationTranslator.createRiderNamePacket("Rider")
        writeChar.value = rPacket
        gatt.writeCharacteristic(writeChar)
        delay(100)

        // Packet 3: First Heartbeat [J to set clock and battery
        val jPacket = NavigationTranslator.createHeartbeatPacket(this)
        writeChar.value = jPacket
        gatt.writeCharacteristic(writeChar)

        Log.d(TAG, "Handshake completed! Dashboard should now say 'Connection Successful'.")
        startHeartbeat(gatt, writeChar)
    }

    @SuppressLint("MissingPermission")
    private fun startHeartbeat(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            Log.d(TAG, "Starting unified 2-second Heartbeat/Navigation loop...")
            var loopCounter = 0
            while (isActive) {
                val p1 = currentPayload1
                val p2 = currentPayload2

                // If we have active navigation data, we continuously broadcast it
                if (isNavigating && p1 != null && p2 != null) {
                    
                    // The dashboard requires ZN for the control header instead of ZO after the first few frames
                    // To keep things simple and ensure it draws properly based on your logs:
                    // We'll alternate between sending ZN/ZO to ensure the dashboard constantly updates.
                    // For now, we will strictly enforce ZN (5a 4e) as the control header as seen in your logs.
                    p1[0] = 0x5a.toByte()
                    p1[1] = 0x4e.toByte() // 'N'

                    // Force P2 to use '[O' (5B 4F) - Navigation Text Header
                    p2[0] = 0x5b.toByte()
                    p2[1] = 0x4f.toByte()

                    // Keep the Null Terminator to prevent the buffer overflow crash!
                    p2[18] = 0x00.toByte()

                    val hex1 = p1.joinToString(" ") { "%02X".format(it) }
                    val hex2 = p2.joinToString(" ") { "%02X".format(it) }
                    
                    // We send the [O text packet first, then the ZN control packet, matching your log sequence
                    Log.d(TAG, "Attempting P2 (Nav Text): $hex2")
                    writeChar.value = p2
                    gatt.writeCharacteristic(writeChar)
                    delay(300)

                    Log.d(TAG, "Attempting P1 (Control): $hex1")
                    writeChar.value = p1
                    gatt.writeCharacteristic(writeChar)
                    
                    // We don't clear the queue anymore! 
                    // The dashboard needs these packets constantly to keep the nav arrows drawn.
                    // We only clear the queue if Maps tells us navigation ended.
                    
                    // Every 5th loop (10 seconds), we interleave a standard [J heartbeat just to keep the primary watchdog happy
                    loopCounter++
                    if (loopCounter >= 5) {
                        delay(500)
                        val jPacket = NavigationTranslator.createHeartbeatPacket(this@BluetoothLeService)
                        writeChar.value = jPacket
                        gatt.writeCharacteristic(writeChar)
                        loopCounter = 0
                    }
                    
                    sendBroadcast(Intent(ACTION_NAVIGATION_SENT))
                    Log.d(TAG, "Nav data loop sent continuously.")
                    
                } else {
                    // Standard idle heartbeat when not navigating - We will push the ZP status packet and [J heartbeat
                    // This ensures the battery percentage and clock update correctly even when maps isn't running!
                    
                    // We DO NOT send ZP anymore. Official logs showed they don't send it. 
                    // They only send the [J packet!
                    
                    val jPacket = NavigationTranslator.createHeartbeatPacket(this@BluetoothLeService)
                    writeChar.value = jPacket
                    gatt.writeCharacteristic(writeChar)
                    Log.d(TAG, "Idle Heartbeat & Status Update sent.")
                }

                delay(2000) // Strict 2-second interval for Watchdog and continuous nav pushing
            }
        }
    }

    private fun startKeepAlive() {
        val channelId = "tvs_bridge_channel"
        val channel = NotificationChannel(channelId, "TVS Bridge BLE", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TVS Navigation Bridge")
            .setContentText("Connected to dashboard")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        unregisterReceiver(navBroadcastReceiver)
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SEND_NAVIGATION = "com.pranava.tvsbridge.ACTION_SEND_NAVIGATION"
        const val ACTION_NAVIGATION_SENT = "com.pranava.tvsbridge.NAVIGATION_SENT"
        const val EXTRA_PAYLOAD_1 = "payload1"
        const val EXTRA_PAYLOAD_2 = "payload2"
        const val EXTRA_STOP_NAVIGATION = "stop_nav"
        const val EXTRA_MAC_ADDRESS = "mac_address"
    }
}