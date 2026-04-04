package com.pranava.tvsbridge.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
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

    // TVS Jupiter 125 UUIDs
    private val TVS_SERVICE_UUID = UUID.fromString("5456534d-5647-5341-5342-454e544f5251") 
    private val TVS_WRITE_CHAR_UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT! Shifting to High Priority...")

                // 1. Force High Priority (Prevents dashboard timeout)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                // 2. Request MTU Size Expansion (Crucial TVS Requirement)
                serviceScope.launch {
                    delay(100) // Brief pause to let priority shift settle
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
                // 3. ONLY discover services AFTER MTU is agreed upon
                gatt.discoverServices()
            } else {
                Log.e(TAG, "MTU negotiation failed. Attempting to proceed anyway...")
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered. Hunting for CCCD Notification Descriptor...")
                val service = gatt.getService(TVS_SERVICE_UUID)

                if (service != null) {
                    var descriptorFound = false

                    // Loop through ALL characteristics to find the one with the CCCD
                    for (characteristic in service.characteristics) {
                        val descriptor = characteristic.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            Log.d(TAG, "Found CCCD on characteristic: ${characteristic.uuid}")

                            // 4. Subscribe to notifications
                            gatt.setCharacteristicNotification(characteristic, true)

                            // 5. Write 0x01 0x00 to CCCD
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            Log.d(TAG, "Wrote CCCD descriptor. Waiting for callback...")
                            descriptorFound = true
                            break // Stop looping once we unlock it
                        }
                    }

                    if (!descriptorFound) {
                        Log.e(TAG, "FATAL: Could not find any characteristic with a CCCD descriptor!")
                    }
                } else {
                    Log.e(TAG, "TVS Service UUID not found on device.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                Log.d(TAG, "Dashboard unlocked successfully! Initiating Handshake...")

                // 6. Fire the 10ms Handshake via Coroutines
                serviceScope.launch {
                    sendHandshakeSequence(gatt)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startKeepAlive()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val macAddress = intent?.getStringExtra("mac_address")
        val payload1 = intent?.getByteArrayExtra("payload1")
        val payload2 = intent?.getByteArrayExtra("payload2")

        if (macAddress != null && bluetoothGatt == null) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(macAddress)
            Log.d(TAG, "Connecting to scooter: $macAddress")
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }

        if (payload1 != null && payload2 != null && bluetoothGatt != null) {
            serviceScope.launch {
                sendNavigationUpdate(payload1, payload2)
            }
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendHandshakeSequence(gatt: BluetoothGatt) {
        val service = gatt.getService(TVS_SERVICE_UUID)
        val writeChar = service?.getCharacteristic(TVS_WRITE_CHAR_UUID) ?: return

        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        // Wait ~190ms AFTER the descriptor write callback before starting the handshake
        delay(190)

        // Packet 1: ZP Mobile Status Sync
        val zpPacket = byteArrayOf(
            0x5a.toByte(), 0xf1.toByte(), 0x03, 0x09, 0x00, 0x02, 0x00, 0x00,
            0x00, 0x03, 0x03, 0xea.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte()
        )
        @Suppress("DEPRECATION")
        writeChar.value = zpPacket
        @Suppress("DEPRECATION")
        val success1 = gatt.writeCharacteristic(writeChar)
        Log.d(TAG, "Handshake Packet 1 (ZP Status) sent. Success: $success1")

        // CRITICAL FIX 1: Increase delay to 100ms so Android has time to receive the ACK
        delay(100)

        // Packet 2: [R Rider Identity Packet ("PRANAVA")
        val rPacket = byteArrayOf(
            0x5b.toByte(), 0x52.toByte(), 0x50, 0x52, 0x41, 0x4e, 0x41, 0x56,
            0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte()
        )
        @Suppress("DEPRECATION")
        writeChar.value = rPacket
        @Suppress("DEPRECATION")
        val success2 = gatt.writeCharacteristic(writeChar)
        Log.d(TAG, "Handshake Packet 2 ([R Identity) sent. Success: $success2")

        // CRITICAL FIX 2: The missing heartbeat sequence
        delay(100)

        // Packet 3: [J Initial Navigation Heartbeat
        val jPacket = byteArrayOf(
            0x5b.toByte(), 0x4a.toByte(), 0x34, 0x50, 0x28, 0x00, 0x06, 0x0b,
            0x09, 0x01, 0x00, 0x04, 0x03, 0x04, 0x1a, 0x00, 0x01, 0x00, 0x00, 0xff.toByte()
        )
        @Suppress("DEPRECATION")
        writeChar.value = jPacket
        @Suppress("DEPRECATION")
        val success3 = gatt.writeCharacteristic(writeChar)
        Log.d(TAG, "Handshake Packet 3 ([J Heartbeat) sent. Success: $success3")

        Log.d(TAG, "Handshake completed! Dashboard should now say 'Connection Successful'.")
        startHeartbeat(gatt, writeChar)
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendNavigationUpdate(payload1: ByteArray, payload2: ByteArray) {
        bluetoothGatt?.let { gatt ->
            val service = gatt.getService(TVS_SERVICE_UUID)
            val writeChar = service?.getCharacteristic(TVS_WRITE_CHAR_UUID)
            
            writeChar?.let {
                it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            
                // Write Packet 1 (ZP Control Payload)
                it.value = payload1
                var success = gatt.writeCharacteristic(it)
                Log.d(TAG, "Payload 1 (ZP/Control) sent. Success: $success")

                // Delay slightly to prevent dropping packets on BLE MTU queue
                delay(400)

                // Write Packet 2 ([J String Payload)
                it.value = payload2
                success = gatt.writeCharacteristic(it)
                Log.d(TAG, "Payload 2 ([J/Text) sent. Success: $success")
            } ?: run {
                Log.e(TAG, "TVS Write Characteristic not found.")
            }
        } ?: run {
            Log.e(TAG, "BluetoothGatt is null. Not connected to scooter.")
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
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    companion object {
        const val ACTION_SEND_NAVIGATION = "com.pranava.tvsbridge.ACTION_SEND_NAVIGATION"
        const val EXTRA_PAYLOAD_1 = "payload1"
        const val EXTRA_PAYLOAD_2 = "payload2"
        const val EXTRA_MAC_ADDRESS = "mac_address"
    }
@SuppressLint("MissingPermission")
    private fun startHeartbeat(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        heartbeatJob?.cancel() // Cancel any existing loop
        heartbeatJob = serviceScope.launch {
            Log.d(TAG, "Starting 2-second Heartbeat loop to keep dashboard alive...")
            while (isActive) {
                delay(2000) // Strict 2-second interval
                val jPacket = byteArrayOf(
                    0x5b.toByte(), 0x4a.toByte(), 0x34, 0x50, 0x28, 0x00, 0x06, 0x0b, 
                    0x09, 0x01, 0x00, 0x04, 0x03, 0x04, 0x1a, 0x00, 0x01, 0x00, 0x00, 0xff.toByte()
                )
                @Suppress("DEPRECATION")
                writeChar.value = jPacket
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(writeChar)
                // We won't log this to avoid flooding Logcat, but it is keeping the scooter awake!
            }
        }
    }
}