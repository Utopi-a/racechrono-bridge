package com.utopia.racechronobridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.utopia.racechronobridge.elm.Elm327Transport
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

class ClassicBluetoothElmClient(
    private val device: BluetoothDevice,
    private val onLog: (String) -> Unit,
) : Elm327Transport {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission")
    fun connect(): Boolean {
        onLog("Trying paired Bluetooth SPP connection.")
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        if (tryConnect(device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE_UUID), "secure")) {
            return true
        }
        if (tryConnect(device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE_UUID), "insecure")) {
            return true
        }
        val channelOneSocket = createChannelOneSocket()
        return channelOneSocket != null && tryConnect(channelOneSocket, "channel 1")
    }

    @Synchronized
    override fun sendCommand(command: String, timeoutMillis: Long): String {
        val activeInput = input ?: error("Bluetooth SPP input is not ready.")
        val activeOutput = output ?: error("Bluetooth SPP output is not ready.")

        drainInput(activeInput)
        activeOutput.write("$command\r".toByteArray(StandardCharsets.US_ASCII))
        activeOutput.flush()

        val response = StringBuilder()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!response.contains(">")) {
            val remainingMillis = deadline - System.currentTimeMillis()
            if (remainingMillis <= 0) {
                break
            }

            val available = activeInput.available()
            if (available > 0) {
                val read = activeInput.read(buffer, 0, minOf(buffer.size, available))
                if (read > 0) {
                    response.append(String(buffer, 0, read, StandardCharsets.US_ASCII))
                }
            } else {
                Thread.sleep(minOf(remainingMillis, 20L))
            }
        }

        if (!response.contains(">")) {
            error("Bluetooth SPP response timed out for command=$command partial=$response")
        }
        return response.toString()
    }

    @Synchronized
    override fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        socket = null
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(candidateSocket: BluetoothSocket, label: String): Boolean {
        return try {
            close()
            candidateSocket.connect()
            socket = candidateSocket
            input = candidateSocket.inputStream
            output = candidateSocket.outputStream
            onLog("Bluetooth SPP $label connection ready.")
            true
        } catch (error: Exception) {
            try {
                candidateSocket.close()
            } catch (_: Exception) {
            }
            onLog("Bluetooth SPP $label connection failed: ${error.message}")
            false
        }
    }

    private fun drainInput(activeInput: InputStream) {
        val buffer = ByteArray(256)
        while (activeInput.available() > 0) {
            activeInput.read(buffer, 0, minOf(buffer.size, activeInput.available()))
        }
    }

    @SuppressLint("DiscouragedPrivateApi", "MissingPermission")
    private fun createChannelOneSocket(): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        } catch (error: Exception) {
            onLog("Bluetooth SPP channel 1 socket unavailable: ${error.message}")
            null
        }
    }

    companion object {
        private val SERIAL_PORT_PROFILE_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
