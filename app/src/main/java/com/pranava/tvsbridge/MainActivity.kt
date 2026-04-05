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
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.View
import android.nfc.NfcAdapter
import android.nfc.Tag

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
                
                // Start observing device presence
                try {
                    deviceManager.startObservingDevicePresence(macAddress)
                    android.util.Log.d("MainActivity", "Started observing device presence for $macAddress")
                    Toast.makeText(this, "Started observing device presence!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to start observing device presence: ${e.message}")
                    Toast.makeText(this, "Failed to start observing presence", Toast.LENGTH_SHORT).show()
                }
            } else {
                binding.tvPayloadResult.text = "CDM Error: Could not extract device data."
            }
        }
        
        // Hide the loading spinner regardless of whether association was successful or cancelled
        binding.loadingSpinner.visibility = View.GONE
        
        // Clean up the scanner text state
        if (binding.tvPayloadResult.text.toString() == "Initializing Scanner...") {
            binding.tvPayloadResult.text = ""
        }
    }
    private val navSuccessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.pranava.tvsbridge.services.BluetoothLeService.ACTION_NAVIGATION_SENT) {
                // Update the UI to show it is safe!
                binding.tvStatus.text = "✅ SUCCESS: Data Transmitted!"
                binding.tvPayloadResult.append("\n\n✅ [TRANSMISSION COMPLETE]\nThe scooter has received the data. It is now safe to disconnect or send a new instruction.")
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

        // Request Notification permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        updateStatus()
        
        // Handle NFC Deep Link Invocation
        handleIntent(intent)

        // ALWAYS MAKE SURE WE ARE OBSERVING THE PRESENCE IF WE HAVE A DEVICE
        val prefs = getSharedPreferences("tvs_prefs", Context.MODE_PRIVATE)
        val macAddress = prefs.getString("scooter_mac", null)
        if (macAddress != null) {
             try {
                 deviceManager.startObservingDevicePresence(macAddress)
                 android.util.Log.d("MainActivity", "Ensuring observation for $macAddress on boot/open")
             } catch (e: Exception) {
                 android.util.Log.e("MainActivity", "Failed to start observing device presence: ${e.message}")
             }
        }

        binding.btnEnableNotificationAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.btnAssociateScooter.setOnClickListener {
            initiateAssociation(null)
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
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data = intent?.data
        
        // Handle native NFC NDEF tag discovery intent (if the app was already open and in foreground)
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, android.nfc.NdefMessage::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }

            if (messages != null && messages.isNotEmpty()) {
                val ndefMessage = messages[0] as android.nfc.NdefMessage
                val records = ndefMessage.records
                for (record in records) {
                    val payload = String(record.payload)
                    if (payload.contains("tvsbridge://")) {
                        // Extract MAC address from payload like " tvsbridge://associate?mac=74:02:E1:A4:C0:99"
                        // or " tvsbridge://74:02:E1:A4:C0:99" (Note: URI records often have a prefix byte)
                        val cleanPayload = payload.substring(1) // skip the URI prefix byte
                        val parsedUri = android.net.Uri.parse(cleanPayload)
                        val targetMac = parsedUri.host ?: parsedUri.getQueryParameter("mac")
                        if (targetMac != null) {
                            binding.tvPayloadResult.text = "NFC Tag Scanned in Foreground!\nAttempting to associate to: $targetMac"
                            initiateAssociation(targetMac)
                            return
                        }
                    }
                }
            }
        }
        
        // Handle standard deep link intents (when app was closed or opened via URL)
        if (Intent.ACTION_VIEW == action && data != null) {
            
            // Check if this was opened via the GitHub App Link!
            if (data.host == "github.com" && data.path?.startsWith("/pranava-kp/CustomVehicleApp") == true) {
                val targetMac = data.getQueryParameter("mac")
                if (targetMac != null) {
                    binding.tvPayloadResult.text = "GitHub App Link Scanned!\nAttempting to associate to: $targetMac"
                    initiateAssociation(targetMac)
                }
            }
            
            // Check if this was opened via our direct tvsbridge:// NFC Deep Link!
            else if (data.scheme == "tvsbridge") {
                // If it's a direct format like tvsbridge://74:02:E1:A4:C0:99
                val targetMac = data.host ?: data.getQueryParameter("mac")
                if (targetMac != null) {
                    binding.tvPayloadResult.text = "NFC Tag Scanned!\nAttempting to associate to: $targetMac"
                    // Trigger the association process specifically searching for this MAC address
                    initiateAssociation(targetMac)
                } else {
                    binding.tvPayloadResult.text = "NFC Tag Scanned!\nNo MAC Address found in URL."
                }
            }
        }
    }

    private fun initiateAssociation(targetMac: String?) {
        binding.tvPayloadResult.text = "Initializing Scanner..."
        binding.loadingSpinner.visibility = View.VISIBLE
        android.util.Log.d("MainActivity", "Starting CDM scan. Target MAC: $targetMac")

        val filterBuilder = BluetoothLeDeviceFilter.Builder()
        
        // If a specific MAC was provided by the NFC card, scan exclusively for it!
        // Otherwise, fallback to the generic TVS name matching
        if (targetMac != null && Pattern.matches("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$", targetMac)) {
            // Android's BluetoothLeDeviceFilter does not allow directly injecting a String MAC address. 
            // It only accepts raw regex name matching.
            filterBuilder.setNamePattern(Pattern.compile(".*")) // Accept any name to surface the scanner
        } else {
            filterBuilder.setNamePattern(Pattern.compile("^.*(TVS|TVAM).*", Pattern.CASE_INSENSITIVE))
        }

        val pairingRequest = AssociationRequest.Builder()
            .addDeviceFilter(filterBuilder.build())
            // Remove single device enforce so the UI always pops up, even if it doesn't see the scooter right away.
            .build()
            
        // We set a manual timeout in case the Android scanner just hangs silently in the background
        // and doesn't fire the onFailure callback immediately when the device isn't around.
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            binding.loadingSpinner.visibility = View.GONE
            binding.tvPayloadResult.text = "Device Not Detected!\nPlease turn on your scooter and try again."
            Toast.makeText(this@MainActivity, "Device not found. Make sure it is turned on.", Toast.LENGTH_LONG).show()
            // We can't reliably cancel a CDM association request on all Android versions, 
            // but we can update the UI so the user isn't stuck waiting forever.
        }
        timeoutHandler.postDelayed(timeoutRunnable, 7000) // 7 second timeout

        deviceManager.associate(pairingRequest, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                timeoutHandler.removeCallbacks(timeoutRunnable) // Scanner worked, kill the manual timeout!
                android.util.Log.d("MainActivity", "CDM Device Found! Launching system chooser...")
                // We DO hide the spinner here now! Because the system bottom-sheet is fully rendered.
                binding.loadingSpinner.visibility = View.GONE
                if (binding.tvPayloadResult.text.toString() == "Initializing Scanner...") {
                    binding.tvPayloadResult.text = ""
                }
                
                val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(chooserLauncher).build()
                pairingLauncher.launch(intentSenderRequest)
            }

            override fun onFailure(error: CharSequence?) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                android.util.Log.e("MainActivity", "CDM Error: $error")
                binding.loadingSpinner.visibility = View.GONE
                
                // Format the error nicely if the device wasn't physically nearby
                if (error?.contains("No devices found") == true || error?.contains("timeout") == true || error?.contains("timed out") == true) {
                    binding.tvPayloadResult.text = "Device Not Detected!\nPlease turn on your scooter and try again."
                    Toast.makeText(this@MainActivity, "Device not found. Make sure it is turned on.", Toast.LENGTH_LONG).show()
                } else {
                    binding.tvPayloadResult.text = "CDM Error: $error\n(Check if Location/Nearby Devices is enabled in Android Settings)"
                }
            }
        }, null)
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

        // NEW: Start listening for the Success Broadcast
        val filter = IntentFilter(com.pranava.tvsbridge.services.BluetoothLeService.ACTION_NAVIGATION_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(navSuccessReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(navSuccessReceiver, filter)
        }
        
        // Re-ensure presence observation when app returns to foreground
        val prefs_mac = getSharedPreferences("tvs_prefs", Context.MODE_PRIVATE)
        val macAddress = prefs_mac.getString("scooter_mac", null)
        if (macAddress != null) {
             try {
                 deviceManager.startObservingDevicePresence(macAddress)
                 android.util.Log.d("MainActivity", "Ensuring observation for $macAddress on resume")
             } catch (e: Exception) {
                 android.util.Log.e("MainActivity", "Failed to start observing device presence: ${e.message}")
             }
        }
    }

    // NEW: Stop listening when the background app returns
    override fun onPause() {
        super.onPause()
        unregisterReceiver(navSuccessReceiver)
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