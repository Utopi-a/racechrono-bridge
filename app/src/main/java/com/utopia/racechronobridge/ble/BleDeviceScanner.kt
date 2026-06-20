package com.utopia.racechronobridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context

data class BleScanDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val likelyElmAdapter: Boolean,
    val bonded: Boolean,
)

class BleDeviceScanner(
    context: Context,
    private val onDeviceFound: (BleScanDevice) -> Unit,
    private val onScanStateChanged: (Boolean) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName ?: "(unknown)"
            onDeviceFound(
                BleScanDevice(
                    device = result.device,
                    name = deviceName,
                    address = result.device.address,
                    rssi = result.rssi,
                    likelyElmAdapter = isLikelyElmAdapter(deviceName),
                    bonded = result.device.bondState == BluetoothDevice.BOND_BONDED,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            onScanStateChanged(false)
            onLog("BLE scan failed: errorCode=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val activeScanner = scanner
        if (activeScanner == null || bluetoothAdapter?.isEnabled != true) {
            onLog("Bluetooth is unavailable or disabled.")
            onScanStateChanged(false)
            return
        }

        emitBondedDevices()
        activeScanner.startScan(scanCallback)
        onScanStateChanged(true)
        onLog("BLE scan started.")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        scanner?.stopScan(scanCallback)
        onScanStateChanged(false)
        onLog("BLE scan stopped.")
    }

    @SuppressLint("MissingPermission")
    private fun emitBondedDevices() {
        val bondedDevices = bluetoothAdapter?.bondedDevices.orEmpty()
        bondedDevices.forEach { device ->
            val deviceName = device.name ?: "(paired device)"
            onDeviceFound(
                BleScanDevice(
                    device = device,
                    name = deviceName,
                    address = device.address,
                    rssi = RSSI_UNKNOWN,
                    likelyElmAdapter = isLikelyElmAdapter(deviceName),
                    bonded = true,
                ),
            )
        }
        onLog("Loaded ${bondedDevices.size} paired Bluetooth devices.")
    }

    companion object {
        const val RSSI_UNKNOWN = Int.MIN_VALUE

        private val LIKELY_NAMES = listOf(
            "Android-Vlink",
            "V-LINK",
            "vLink",
            "Vgate",
            "iCar",
        )

        fun isLikelyElmAdapter(name: String): Boolean {
            return LIKELY_NAMES.any { candidate -> name.contains(candidate, ignoreCase = true) }
        }
    }
}
