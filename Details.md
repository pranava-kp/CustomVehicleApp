# TVS Jupiter 125 Custom Navigation Bridge: Kotlin Development Methodology

This document outlines the step-by-step methodology for building a custom Android application that intercepts Google Maps navigation data and transmits it to a TVS Jupiter 125 dashboard. Using Kotlin is the optimal path here—it handles the heavy lifting of asynchronous BLE operations seamlessly via Coroutines. 

---

## Phase 1: Data Interception (The Google Maps Scraper)
Before connecting to the vehicle, the app needs to successfully capture the navigation instructions from the phone's system.

* **Objective:** Read active Google Maps turn-by-turn navigation data.
* **Key Component:** `NotificationListenerService`.
* **Tasks:**
    1.  Create a Kotlin service extending `NotificationListenerService`.
    2.  Implement the logic to request notification access permissions from the user.
    3.  Filter incoming notifications specifically for the `com.google.android.apps.maps` package.
    4.  Extract the `Notification.EXTRA_TITLE` (Distance and turn direction) and `Notification.EXTRA_TEXT` (Street name) strings.
    5.  Set up local logging to verify the text extraction is working accurately while driving or simulating a route.

## Phase 2: Reverse Engineering (The IoT / BLE Layer)
This phase involves understanding the language the vehicle speaks. You need to map the text data from Phase 1 to the hexadecimal payloads the dashboard expects.

* **Objective:** Identify the correct BLE GATT characteristics and payload structures.
* **Key Tools:** Android Developer Options (HCI Snoop Log), Wireshark.
* **Tasks:**
    1.  Enable Bluetooth HCI Snoop logging on the phone.
    2.  Use the official TVS Connect app to run a test Mappls navigation route (capture left turn, right turn, straight, and distance updates).
    3.  Extract the log file (`.log` or `.cfa`) via ADB and open it in Wireshark.
    4.  Identify the scooter's MAC address and the specific GATT Write Commands being sent during navigation updates.
    5.  Document the mapping between the visual dashboard updates and the corresponding hex byte arrays.

## Phase 3: Seamless Connectivity (Companion Device Manager)
Instead of continuous, battery-draining background scanning, the app will hand off the connection responsibility to the Android OS.

* **Objective:** Connect to the scooter seamlessly without heavy battery drain.
* **Key Component:** `CompanionDeviceManager` (CDM).
* **Tasks:**
    1.  Implement the CDM API to associate the TVS Jupiter's MAC address or GATT Service UUID with the app.
    2.  Set up a `BroadcastReceiver` to listen for the OS-level intent fired when the scooter is detected nearby.
    3.  Ensure this trigger can silently wake the app from the background.

## Phase 4: Core Service Architecture & Translation (Coroutines)
This ties the intercepted data to the vehicle connection. The app needs a persistent background presence to run the translation logic while you are riding. Kotlin Coroutines will manage the asynchronous BLE callbacks here.

* **Objective:** Maintain connection and translate data on the fly asynchronously.
* **Key Components:** Foreground Service, `BluetoothGatt`, Kotlin Coroutines (`suspend` functions), Translation Utility.
* **Tasks:**
    1.  Create a persistent Foreground Service with a silent notification to prevent Android from killing the process.
    2.  Initialize the `BluetoothGatt` client within this service. Use Coroutines to handle the `BluetoothGattCallback` methods so you don't end up with nested callback hell.
    3.  Build a Translation Kotlin `object` (Singleton): this will take the parsed strings from Google Maps (from Phase 1) and map them to the specific byte arrays (from Phase 2).
    4.  Write the suspendable logic to push these byte arrays to the correct GATT characteristic whenever a notification update occurs.

## Phase 5: The Physical Trigger (NFC & Deep Linking)
The final polish phase creates the seamless physical interaction using an NFC sticker on the vehicle console.

* **Objective:** Trigger the app instantly, or provide an installation route for new devices.
* **Key Components:** NFC Tag, NDEF URL, Android App Links.
* **Tasks:**
    1.  Host the compiled APK file on a simple web page or repository (e.g., GitHub Pages).
    2.  Program an NFC sticker with the URL of that hosted page using standard NDEF format.
    3.  Configure the `AndroidManifest.xml` with an `<intent-filter>` listening for `ACTION_VIEW` for that specific URL.
    4.  Implement the logic in the main `Activity` so that tapping the NFC tag instantly launches the app and triggers the background service (if already installed) or falls back to the browser for installation.
