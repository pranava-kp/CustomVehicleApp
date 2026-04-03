# TVS Jupiter 125 Custom Navigation Bridge

This Android application acts as a custom bridge between your smartphone and the TVS Jupiter 125 dashboard. It intercepts real-time turn-by-turn navigation data from Google Maps and forwards it directly to the scooter's display via Bluetooth Low Energy (BLE), effectively replacing the need to use the default OEM navigation app.

## Project Phases

This project is structured around five core development phases:

1.  **Data Interception:** Using Android's `NotificationListenerService`, the app captures background notifications posted by Google Maps and extracts the direction (e.g., "right turn") and distance text strings.
2.  **Reverse Engineering (IoT/BLE Layer):** Capturing and understanding the hex payloads expected by the TVS dashboard by analyzing HCI Snoop logs from the official TVS Connect app.
3.  **Seamless Connectivity:** Utilizing Android's `CompanionDeviceManager` (CDM) to automatically and silently pair/reconnect with the scooter without severe battery drain.
4.  **Core Foreground Service:** A Kotlin Coroutines-based translation background service that asynchronously maps the extracted map strings to the correct GATT characteristic byte arrays and pushes them to the vehicle.
5.  **Physical Triggering:** Integrating a physical NFC sticker on the vehicle console for seamless tap-to-ride app launching and connection initiation using Deep Linking.

## How It Works

1. **Permissions:** The app requests `Notification Access` to read incoming Maps directions, as well as Bluetooth and Location permissions to communicate over BLE.
2. **Interception:** When a Google Maps route is started, `MapsNotificationListenerService` parses the Android UI notifications.
3. **Translation:** The `NavigationTranslator` utility converts "In 500 ft - Turn right" into the specific byte array required by the dashboard firmware.
4. **Transmission:** `BluetoothLeService` writes the payload to the scooter's connected GATT server.

## Setup Instructions

1. Clone this repository.
2. Open the project folder (`CustomVehicleApp`) in **Android Studio**.
3. Allow Gradle to sync and build the project dependencies.
4. Connect a physical Android device (emulators usually lack the necessary navigation and Bluetooth capabilities).
5. Build and install the app on your device.
6. Open the app and tap **"Enable Notification Access"**, then toggle the switch for "TVS Bridge".
7. Start a route in Google Maps; you can monitor the interception logs locally via Logcat.

## Current State

Phase 1 (Data Interception), Phase 2 (Reverse Engineering), and **Phase 3 (Connectivity & Handshake)** are now implemented. 

**Progress Logs:**
- ✅ **Data Interception:** Successfully parsing Google Maps notifications into dual-packet hex strings.
- ✅ **Companion Device Manager:** Integrated native Android IoT discovery for persistent scooter association.
- ✅ **Handshake Protocol:** Implemented the mandatory `[R`, `ZP`, and `[L` greeting sequence required by the TVS Jupiter 125 series.
- 🟡 **Status:** We are currently troubleshooting a "Silent Dashboard" issue where the vehicle is not yet acknowledging the handshake despite a successful BLE association.

The next step is to perform deeper GATT level diagnostics to identify if the dashboard requires a specific MTU size or a secure bonding challenge before it enters the "Successful Connection" state.

