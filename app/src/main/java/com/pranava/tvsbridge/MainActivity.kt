package com.pranava.tvsbridge

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.pranava.tvsbridge.databinding.ActivityMainBinding

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val deviceManager: CompanionDeviceManager by lazy {
        getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
    }

    private val pairingLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            var macAddress: String? = null
            var name: String? = null

            if (data != null) {
                // Attempt to retrieve AssociationInfo (Android 13+)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val association = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, android.companion.AssociationInfo::class.java)
                    if (association != null) {
                        macAddress = association.deviceMacAddress?.toString()?.uppercase()
                        name = association.displayName?.toString() ?: "TVS Dashboard"
                    }
                }
                
                // Fallback for older devices or if AssociationInfo is null
                if (macAddress == null) {
                    @Suppress("DEPRECATION")
                    val deviceExtra = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)
                    
                    if (deviceExtra is android.bluetooth.le.ScanResult) {
                        macAddress = deviceExtra.device.address?.uppercase()
                        @SuppressLint("MissingPermission")
                        name = deviceExtra.device.name ?: "TVS Dashboard"
                    } else if (deviceExtra is android.bluetooth.BluetoothDevice) {
                        macAddress = deviceExtra.address?.uppercase()
                        @SuppressLint("MissingPermission")
                        name = deviceExtra.name ?: "TVS Dashboard"
                    }
                }
            }

            if (macAddress != null) {
                saveAssociatedDevice(macAddress, name ?: "TVS Dashboard")
                updateStatus()
                // Auto-start service to initiate GATT connection
                startBleService()
            } else {
                binding.tvPayloadResult.text = "CDM Error: Could not extract device data."
            }
        }
    }

    private fun saveAssociatedDevice(address: String, name: String) {
        val prefs = getSharedPreferences("tvs_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("scooter_mac", address)
            putString("scooter_name", name)
            apply()
        }
    }

    private fun startBleService() {
        // Retrieve the saved MAC address we just got from the Companion Device Manager
        val prefs = getSharedPreferences("tvs_prefs", Context.MODE_PRIVATE)
        val macAddress = prefs.getString("scooter_mac", null)

        if (macAddress != null) {
            val intent = Intent(this, com.pranava.tvsbridge.services.BluetoothLeService::class.java).apply {
                // Pass the MAC address to the background service!
                putExtra("mac_address", macAddress)
            }
            startForegroundService(intent)
            android.util.Log.d("MainActivity", "Starting BLE Service with MAC: $macAddress")
        } else {
            android.util.Log.e("MainActivity", "Cannot start BLE Service: MAC Address is missing!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Global Exception Catcher for UI logging
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val prefs = getSharedPreferences("crash_logs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("last_crash", e.stackTraceToString()).commit()
            // Let the system kill the app afterwards so it can restart properly
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        // Request BLE runtime permissions for Android 12+ (API 31)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val requiredPermissions = arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
            val missingPermissions = requiredPermissions.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missingPermissions.isNotEmpty()) {
                requestPermissions(missingPermissions.toTypedArray(), 101)
            }
        }

        updateStatus()

        binding.btnEnableNotificationAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.btnAssociateScooter.setOnClickListener {
            // Let the user know the button was pressed
            binding.tvPayloadResult.text = "Initializing Scanner..."
            android.util.Log.d("MainActivity", "Associate button clicked. Starting CDM scan.")

            val deviceFilter = BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("^.*(TVS|TVAM).*", Pattern.CASE_INSENSITIVE))
                .build()

            val pairingRequest = AssociationRequest.Builder()
                .addDeviceFilter(deviceFilter)
                // REMOVED .setSingleDevice(true) so Android forces the Scanning UI to appear
                .build()

            deviceManager.associate(pairingRequest, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    android.util.Log.d("MainActivity", "CDM Device Found! Launching system chooser...")
                    val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(chooserLauncher).build()
                    pairingLauncher.launch(intentSenderRequest)
                }

                override fun onFailure(error: CharSequence?) {
                    android.util.Log.e("MainActivity", "CDM Error: $error")
                    // If you get an error, it will likely be "Missing Location Permission"
                    binding.tvPayloadResult.text = "CDM Error: $error\n(Check if Location/Nearby Devices is enabled in Android Settings)"
                }
            }, null)
        }

        binding.btnTestDummyPayload.setOnClickListener {
            val dummyDistance = 250 // 250 meters
            val dummyEta = 1800L // 30 minutes
            val dummyTotal = 15000 // 15 km
            val instruction = "Turn right onto Bridge St"
            
            val (payload1, payload2) = com.pranava.tvsbridge.services.NavigationTranslator.createNavigationPayloads(
                dummyDistance, dummyEta, dummyTotal, instruction
            )
            
            // Fixed the crash by correctly bitmasking the signed bytes
            val hex1 = payload1.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            val hex2 = payload2.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            
            val displayText = "Packet 1 (ZP):\n$hex1\n\nPacket 2 ([J):\n$hex2"
            binding.tvPayloadResult.text = displayText

            // Send intent
            try {
                val serviceIntent = Intent(this, com.pranava.tvsbridge.services.BluetoothLeService::class.java).apply {
                    action = com.pranava.tvsbridge.services.BluetoothLeService.ACTION_SEND_NAVIGATION
                    putExtra(com.pranava.tvsbridge.services.BluetoothLeService.EXTRA_PAYLOAD_1, payload1)
                    putExtra(com.pranava.tvsbridge.services.BluetoothLeService.EXTRA_PAYLOAD_2, payload2)
                }
                startService(serviceIntent)
            } catch (e: Exception) {
                binding.tvPayloadResult.append("\n\nFailed to start service: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        
        val prefs = getSharedPreferences("crash_logs", android.content.Context.MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", null)
        if (lastCrash != null) {
            binding.tvPayloadResult.text = "LAST CRASH LOG:\n$lastCrash"
            prefs.edit().remove("last_crash").apply()
        }
    }

    private fun updateStatus() {
        if (isNotificationServiceEnabled()) {
            binding.tvStatus.text = "Notification Access: Granted\nListening for Maps..."
            binding.btnEnableNotificationAccess.isEnabled = false
        } else {
            binding.tvStatus.text = "Notification Access: Denied\nPlease enable access."
            binding.btnEnableNotificationAccess.isEnabled = true
        }

        // Display Associated TVS Dashboard
        val prefs = getSharedPreferences("tvs_prefs", Context.MODE_PRIVATE)
        val scooterMac = prefs.getString("scooter_mac", null)
        val scooterName = prefs.getString("scooter_name", null)

        if (scooterMac != null) {
            binding.tvBluetoothDevice.text = "Associated Dashboard:\n$scooterName [$scooterMac]"
        } else {
            // Fallback to legacy paired devices check
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
                val adapter = bluetoothManager.adapter
                if (adapter == null || !adapter.isEnabled) {
                    binding.tvBluetoothDevice.text = "Bluetooth Disabled"
                } else if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val pairedDevices = adapter.bondedDevices
                    val tvsDevice = pairedDevices?.firstOrNull { it.name?.contains("TVS", ignoreCase = true) == true || it.name?.contains("TVAM", ignoreCase = true) == true }
                    if (tvsDevice != null) {
                        binding.tvBluetoothDevice.text = "Paired Dashboard:\n${tvsDevice.name} [${tvsDevice.address}]"
                    } else {
                        val fallback = pairedDevices.firstOrNull()
                        if (fallback != null) {
                            binding.tvBluetoothDevice.text = "Paired Device:\n${fallback.name} [${fallback.address}]"
                        } else {
                            binding.tvBluetoothDevice.text = "No Associated/Paired Devices"
                        }
                    }
                } else {
                    binding.tvBluetoothDevice.text = "Awaiting Bluetooth Permission"
                }
            } catch (e: Exception) {
                binding.tvBluetoothDevice.text = "BL Error: ${e.message}"
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (i in names.indices) {
                val cn = ComponentName.unflattenFromString(names[i])
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }
}
