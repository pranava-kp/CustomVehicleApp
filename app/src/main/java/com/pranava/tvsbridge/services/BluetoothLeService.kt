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

    // Unified Queue State Variables (The Fix!)
    private var currentPayload1: ByteArray? = null
    private var currentPayload2: ByteArray? = null

    // TVS Jupiter 125 UUIDs
    private val TVS_SERVICE_UUID = UUID.fromString("5456534d-5647-5341-5342-454e544f5251")
    private val TVS_WRITE_CHAR_UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val macAddress = intent?.getStringExtra("mac_address")
        val payload1 = intent?.getByteArrayExtra("payload1")
        val payload2 = intent?.getByteArrayExtra("payload2")

        // 1. Initial Connection Trigger
        if (macAddress != null && bluetoothGatt == null) {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(macAddress)
            Log.d(TAG, "Connecting to scooter: $macAddress")
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }

        // 2. Dummy Data Trigger (Unified Queue Manager)
        if (payload1 != null && payload2 != null) {
            Log.d(TAG, "Received new navigation data from Dummy button. Queueing for next heartbeat.")
            currentPayload1 = payload1
            currentPayload2 = payload2
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendHandshakeSequence(gatt: BluetoothGatt) {
        val service = gatt.getService(TVS_SERVICE_UUID)
        val writeChar = service?.getCharacteristic(TVS_WRITE_CHAR_UUID) ?: return

        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        delay(190)

        // Packet 1
        val zpPacket = byteArrayOf(
            0x5a.toByte(), 0xf1.toByte(), 0x03, 0x09, 0x00, 0x02, 0x00, 0x00,
            0x00, 0x03, 0x03, 0xea.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte()
        )
        @Suppress("DEPRECATION")
        writeChar.value = zpPacket
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(writeChar)
        delay(100)

        // Packet 2
        val rPacket = byteArrayOf(
            0x5b.toByte(), 0x52.toByte(), 0x50, 0x52, 0x41, 0x4e, 0x41, 0x56,
            0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte()
        )
        @Suppress("DEPRECATION")
        writeChar.value = rPacket
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(writeChar)
        delay(100)

        // Packet 3
        val jPacket = byteArrayOf(
            0x5b.toByte(), 0x4a.toByte(), 0x34, 0x50, 0x28, 0x00, 0x06, 0x0b,
            0x09, 0x01, 0x00, 0x04, 0x03, 0x04, 0x1a, 0x00, 0x01, 0x00, 0x00, 0xff.toByte()
        )
        @Suppress("DEPRECATION")
        writeChar.value = jPacket
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(writeChar)

        Log.d(TAG, "Handshake completed! Dashboard should now say 'Connection Successful'.")
        startHeartbeat(gatt, writeChar)
    }

    @SuppressLint("MissingPermission")
    private fun startHeartbeat(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            Log.d(TAG, "Starting unified 2-second Heartbeat/Navigation loop...")
            while (isActive) {
                val p1 = currentPayload1
                val p2 = currentPayload2

                if (p1 != null && p2 != null) {

                    // 1. Force P1 to use 'ZO' (5A 4F) - Navigation Control Header
                    p1[0] = 0x5a.toByte()
                    p1[1] = 0x4f.toByte()

                    // 2. Force P2 to use '[O' (5B 4F) - Navigation Text Header
                    p2[0] = 0x5b.toByte()
                    p2[1] = 0x4f.toByte()

                    // 3. Keep the Null Terminator to prevent the buffer overflow crash!
                    p2[18] = 0x00.toByte()

                    val hex1 = p1.joinToString(" ") { "%02X".format(it) }
                    val hex2 = p2.joinToString(" ") { "%02X".format(it) }
                    Log.d(TAG, "Attempting P1 (Control): $hex1")
                    Log.d(TAG, "Attempting P2 (Nav): $hex2")

                    writeChar.value = p1
                    gatt.writeCharacteristic(writeChar)
                    delay(300)

                    writeChar.value = p2
                    gatt.writeCharacteristic(writeChar)

                    sendBroadcast(Intent(ACTION_NAVIGATION_SENT))
                    currentPayload1 = null
                    currentPayload2 = null
                    Log.d(TAG, "Nav data sent, UI notified, and queue cleared. Returning to blank heartbeats.")
                } else {
                    val jPacket = byteArrayOf(
                        0x5b.toByte(), 0x4a.toByte(), 0x34, 0x50, 0x28, 0x00, 0x06, 0x0b,
                        0x09, 0x01, 0x00, 0x04, 0x03, 0x04, 0x1a, 0x00, 0x01, 0x00, 0x00, 0xff.toByte()
                    )
                    @Suppress("DEPRECATION")
                    writeChar.value = jPacket
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(writeChar)
                }

                delay(2000) // Strict 2-second interval for Watchdog
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
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SEND_NAVIGATION = "com.pranava.tvsbridge.ACTION_SEND_NAVIGATION"
        const val ACTION_NAVIGATION_SENT = "com.pranava.tvsbridge.NAVIGATION_SENT"
        const val EXTRA_PAYLOAD_1 = "payload1"
        const val EXTRA_PAYLOAD_2 = "payload2"
        const val EXTRA_MAC_ADDRESS = "mac_address"
    }
}