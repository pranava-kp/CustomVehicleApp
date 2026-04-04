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

Phase 1 (Data Interception), Phase 2 (Reverse Engineering), and **Phase 3 (Connectivity & Handshake)** are now **100% COMPLETED**.

**Progress Logs:**
- ✅ **Data Interception:** Successfully parsing Google Maps notifications into dual-packet hex strings.
- ✅ **Companion Device Manager:** Integrated native Android IoT discovery for persistent scooter association.
- ✅ **Handshake Protocol:** Implemented the mandatory `[R` (Identity), `ZP` (Status), and `[J` (Heartbeat) sequence.
- ✅ **Hardware Stability:** Negotiated BLE MTU expansion to 65 bytes and implemented a unified "Trojan Horse" heartbeat loop. The dashboard now maintains an infinite, stable connection without triggering the 15-second watchdog timer.
- ✅ **Dashboard Navigation Unlock:** Successfully reverse-engineered the undocumented Navigation headers (`ZO` for Control, `[O` for Text).
- ✅ **Buffer Overflow Patch:** Implemented real-time byte manipulation to inject a `0x00` Null Terminator before the checksum, preventing the dashboard's C-based firmware from crashing when reading custom strings.
- 🟡 **Status (Next Step):** The Bluetooth pipeline is fully stable and rendering dummy graphics. Transitioning to Phase 4: wiring the `NotificationListenerService` GPS data stream directly into the unified BLE transmission queue.

---

### 🔬 Technical Documentation: TVS BLE Architecture Notes
* **Heartbeat (`[J`):** Must be fired every 2 seconds or the dashboard watchdog triggers a disconnect.
* **Status Update (`ZP`):** Used for background phone status (battery, network). Text payloads following this are hidden from the UI.
* **Navigation Header (`ZO` / `[O`):** The exact header pair required to force the LCD into Navigation Mode and render turn arrows.
* **String Formatting:** The dashboard uses C-style strings. ALL custom text payloads *must* end with a `0x00` byte immediately preceding the `0xFF` checksum, or the dashboard will experience a fatal buffer overflow and crash the connection.