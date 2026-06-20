package com.utopia.racechronobridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BleElmClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onLog: (String) -> Unit,
) {
    private val responseLock = Object()
    private val responseBuffer = StringBuilder()

    private var connectLatch = CountDownLatch(1)
    private var connected = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE connection state failed: status=$status newState=$newState")
                connected = false
                connectLatch.countDown()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onLog("BLE connected. Discovering services.")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    onLog("BLE disconnected.")
                    connected = false
                    connectLatch.countDown()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("BLE service discovery failed: status=$status")
                connectLatch.countDown()
                return
            }

            val serialPair = findSerialCharacteristics(gatt)
            if (serialPair == null) {
                onLog("BLE serial characteristics were not found.")
                connectLatch.countDown()
                return
            }

            writeCharacteristic = serialPair.write
            notifyCharacteristic = serialPair.notify
            enableNotifications(gatt, serialPair.notify)
            connected = true
            onLog(
                "BLE serial ready. service=${serialPair.serviceUuid} " +
                    "write=${serialPair.write.uuid} notify=${serialPair.notify.uuid}",
            )
            connectLatch.countDown()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            appendResponse(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            appendResponse(value)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(timeoutMillis: Long = 15_000): Boolean {
        connectLatch = CountDownLatch(1)
        bluetoothGatt = device.connectGatt(context, false, callback)
        if (!connectLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            onLog("BLE connection timed out.")
            close()
            return false
        }
        return connected
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(command: String, timeoutMillis: Long = 2_000): String {
        val gatt = bluetoothGatt ?: error("BLE is not connected.")
        val characteristic = writeCharacteristic ?: error("BLE write characteristic is not ready.")

        synchronized(responseLock) {
            responseBuffer.clear()
        }

        val payload = "$command\r".toByteArray(StandardCharsets.US_ASCII)
        val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, payload, characteristic.writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = payload
            gatt.writeCharacteristic(characteristic)
        }

        if (!writeStarted) {
            error("BLE write failed to start for command=$command")
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(responseLock) {
            while (!responseBuffer.contains(">")) {
                val remainingMillis = deadline - System.currentTimeMillis()
                if (remainingMillis <= 0) {
                    break
                }
                responseLock.wait(remainingMillis)
            }
            if (!responseBuffer.contains(">")) {
                error("BLE response timed out for command=$command partial=${responseBuffer}")
            }
            return responseBuffer.toString()
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        connected = false
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    private fun appendResponse(value: ByteArray) {
        val text = String(value, StandardCharsets.US_ASCII)
        synchronized(responseLock) {
            responseBuffer.append(text)
            if (text.contains(">")) {
                responseLock.notifyAll()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun findSerialCharacteristics(gatt: BluetoothGatt): SerialCharacteristics? {
        for (service in gatt.services) {
            val write = service.characteristics.firstOrNull { it.canWrite }
            val notify = service.characteristics.firstOrNull { it.canNotify }
            if (write != null && notify != null) {
                write.writeType = if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                return SerialCharacteristics(service.uuid, write, notify)
            }
        }
        return null
    }

    private val BluetoothGattCharacteristic.canWrite: Boolean
        get() = (properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            )) != 0

    private val BluetoothGattCharacteristic.canNotify: Boolean
        get() = (properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            )) != 0

    private data class SerialCharacteristics(
        val serviceUuid: UUID,
        val write: BluetoothGattCharacteristic,
        val notify: BluetoothGattCharacteristic,
    )

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
