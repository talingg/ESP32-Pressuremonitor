# Pressure Monitor

An Android app that connects over **Bluetooth Low Energy (BLE)** to an ESP32 reading a differential pressure sensor, and shows the pressure as a live graph. Recordings are saved on the phone and can be reviewed later, including zooming into the details.

Built with **Kotlin** and **Jetpack Compose**.

## Features

- **BLE connection** to the ESP32 with a status LED (green = connected, red = not connected) and a spinner while connecting
- **Automatic reconnect** if the connection drops or the sensor is out of range
- **User-friendly connection feedback**
  - Message if Bluetooth is turned off on the phone
  - Message if no connection could be established within 5 seconds
- **Live pressure graph** (in hPa) with Start / Stop / Clear
  - While recording, shows a sliding window of the last 30 seconds
  - After stopping, shows the complete recording from start to stop
- **Logged sessions**
  - Every recording is saved automatically, named by date and time (`yyyy/MM/dd HH:mm:ss`)
  - Tap a session to view its graph
  - Pinch to zoom the time axis, drag to pan (the pressure axis rescales to the visible data)
  - Long-press to select entries, then delete one or several (with confirmation), as in a file explorer

## Screens

| Screen | Purpose |
|---|---|
| Home | Connect button with status LED and spinner, navigation to the other screens |
| Pressure Graph | Live graph with Start, Stop, Clear |
| Logged Sessions | List of saved recordings, multi-select delete |
| Session Detail | Saved graph with pinch-to-zoom and pan |

## Hardware

| Part | Notes |
|---|---|
| ESP32 | Acts as BLE peripheral |
| MPXV7025DP | Differential pressure sensor, range -25 to +25 kPa (-250 to +250 hPa), powered with 5 V |
| ADS1115 | 16-bit I2C ADC, address `0x48`, channel AIN0, gain `GAIN_ONE` (±4.096 V) |
| Voltage divider | 5.1 kΩ in series from the sensor output, 10 kΩ from that node to GND (scales the sensor output down to a safe range for the ADC) |

**Wiring**

- ADS1115 SDA → ESP32 GPIO 21
- ADS1115 SCL → ESP32 GPIO 22
- Sensor output → 5.1 kΩ → ADS1115 AIN0, with 10 kΩ from the AIN0 node to GND

## ESP32 firmware

The companion sketch is `esp32_ble_pressure.ino` (Arduino framework).

**Libraries:** ESP32 BLE (bundled with the ESP32 Arduino core), `Adafruit ADS1X15`, `Wire`.

What it does:

- Advertises as `ESP32-PressureSensor`
- Reads the ADS1115, undoes the voltage divider, and converts the voltage to pressure using the MPXV7025DP transfer function `P = ((Vout / VS) - 0.5) / 0.2` (kPa, VS = 5 V)
- Applies a zero-offset calibration and a small deadband (0.02 kPa) to suppress noise at rest
- Sends the value in **hPa** as a BLE notification every **100 ms**
- Restarts advertising after a disconnect
- Basic power savings: CPU at 80 MHz, Bluetooth Classic memory released, no busy polling

Calibration constants (`PRESSURE_ZERO_OFFSET_KPA`, `PRESSURE_DEADBAND_KPA`, `SENSOR_SUPPLY_VOLTAGE`, divider resistor values) are at the top of the sketch. Re-measure the zero offset if you rewire the sensor.

Set `USE_DUMMY_DATA` to `true` to send generated test data without the sensor connected.

## BLE protocol

| Item | Value |
|---|---|
| Device name | `ESP32-PressureSensor` |
| Service UUID | `4fafc201-1fb5-459e-8fcc-c5c9c331914b` |
| Characteristic UUID | `beb5483e-36e1-4688-b7f5-ea07361b26a8` (read, notify) |
| Payload | UTF-8 text of a float with 3 decimals, in hPa (e.g. `12.345`) |
| Rate | 10 notifications per second |

The UUIDs must match between the firmware and the app (`BleViewModel.kt`).

## Android app

### Requirements

- Android Studio (recent version) with the Android SDK
- A **physical Android phone** with BLE. The emulator cannot talk to real Bluetooth hardware.
- USB debugging enabled on the phone

### Dependencies

Besides the default Compose template, add these to `app/build.gradle.kts`:

```kotlin
implementation("androidx.navigation:navigation-compose:2.9.7")
implementation("androidx.compose.material:material-icons-core")
```

If Gradle cannot resolve a version for the icons artifact (no Compose BOM in the project), pin one, e.g. `material-icons-core:1.7.8`.

### Permissions

Declared in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

Android 12+ asks for the Bluetooth permissions at runtime the first time you press **Connect**. On Android 11 and below the location permission is requested instead.

### Build and run

1. Clone the repository and open it in Android Studio
2. Let Gradle sync
3. Connect your phone via USB and press **Run**
4. Power the ESP32, turn on Bluetooth on the phone, and press **Connect**

## Project structure

```
app/src/main/java/com/example/pressuremonitor/
├── MainActivity.kt            # Navigation, permission requests, connection timeout message
├── BleViewModel.kt            # BLE scan / connect / notify / reconnect, recording state
├── SessionStorage.kt          # Data models and JSON file storage for sessions
├── PressureChart.kt           # Custom Canvas line chart (themed with Material colors)
├── HomeScreen.kt
├── PressureGraphScreen.kt
├── LoggedSessionsScreen.kt
└── SessionDetailScreen.kt
```

**Design notes**

- The BLE logic lives in an `AndroidViewModel`, so the connection survives navigation between screens.
- Sessions are stored as one JSON file each in the app's private storage (`files/sessions/<startTimeMs>.json`); no database library is needed.
- The chart is drawn with Compose `Canvas`, so there is no external charting dependency.

## Known limitations

- Sessions recorded before the switch from kPa to hPa are stored in kPa but will be labeled hPa.
- Pinch-to-zoom is only available on saved sessions, not on the live graph.
- The app does not turn Bluetooth on for you; it asks you to enable it and press Connect again.
- Spinner animation requires the system animation scale in Developer Options to be on.

## Ideas for next steps

- Export sessions (CSV) and share them
- Gridlines and tick labels on the chart
- ESP32 light sleep / lower connection interval for longer battery life
- Show the current live value as a number

## License

Add a license of your choice (for example MIT) and a `LICENSE` file.
