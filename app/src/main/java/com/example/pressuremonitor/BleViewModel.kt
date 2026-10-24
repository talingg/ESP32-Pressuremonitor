package com.example.pressuremonitor

/*
  Shared BLE logic for the whole app, as an AndroidViewModel.

  Living in a ViewModel (rather than the Activity) means the connection
  survives navigation between screens - e.g. connect from the Home
  screen, then go look at Logged Sessions, and the connection (and its
  green/red status dot) stays intact.

  Runtime permission requesting still happens in MainActivity (it needs
  an Activity to launch the permission dialog) - this ViewModel exposes
  beginConnection(), which MainActivity calls once permissions are
  confirmed granted.
*/

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import java.util.UUID

class BleViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val DEVICE_NAME = "ESP32-PressureSensor"
        // Must match the UUIDs used in the ESP32 sketch
        private val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 3_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val appContext: Context = application.applicationContext

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    // Observable so isConnecting (below) recomposes correctly when it changes.
    private var shouldStayConnected by mutableStateOf(false)

    // --- Observable state read by the UI --------------------------------
    var isConnected by mutableStateOf(false)
        private set
    var latestPressure by mutableStateOf<Float?>(null)
        private set
    var isRecording by mutableStateOf(false)
        private set
    val recordedPoints = mutableStateListOf<PressurePoint>()

    /** True from the moment a connection attempt starts until it actually succeeds. */
    val isConnecting: Boolean
        get() = shouldStayConnected && !isConnected

    private var recordingStartTimeMs: Long = 0L

    // --- Public controls -------------------------------------------------

    /** Call once permissions are confirmed granted (see MainActivity). */
    fun beginConnection() {
        if (isConnected || shouldStayConnected) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) return
        shouldStayConnected = true
        startScan()
    }

    fun startRecording() {
        recordedPoints.clear()
        recordingStartTimeMs = System.currentTimeMillis()
        isRecording = true
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val snapshot = recordedPoints.toList()
        if (snapshot.isNotEmpty()) {
            SessionStorage.saveSession(appContext, PressureSession(recordingStartTimeMs, snapshot))
        }
    }

    /** Empties the currently displayed graph without affecting the connection. */
    fun clearRecording() {
        recordedPoints.clear()
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** True only if this device has Bluetooth hardware AND it's currently switched on. */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    // --- Scanning ----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        isScanning = true

        val filters = listOf(ScanFilter.Builder().setDeviceName(DEVICE_NAME).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)

        handler.postDelayed({
            if (isScanning) {
                stopScan()
                scheduleReconnect()
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME) {
                stopScan()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            scheduleReconnect()
        }
    }

    // --- Connecting ----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    handler.post { isConnected = true }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    handler.post { isConnected = false }
                    gatt.close()
                    bluetoothGatt = null
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID) ?: return
            gatt.setCharacteristicNotification(characteristic, true)

            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        // Called on Android 12 (API 32) and below.
        // Suppressed: we're intentionally keeping this deprecated override
        // for backward compatibility with pre-Android-13 devices, alongside
        // the newer overload below.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleValue(characteristic.value)
            }
        }

        // Called on Android 13 (API 33) and above
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleValue(value)
        }
    }

    private fun handleValue(value: ByteArray?) {
        val text = value?.toString(Charsets.UTF_8) ?: return
        val pressure = text.trim().toFloatOrNull() ?: return

        handler.post {
            latestPressure = pressure
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartTimeMs) / 1000f
                recordedPoints.add(PressurePoint(elapsed, pressure))
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected) return
        handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    override fun onCleared() {
        super.onCleared()
        shouldStayConnected = false
        stopScan()
        bluetoothGatt?.close()
    }
}