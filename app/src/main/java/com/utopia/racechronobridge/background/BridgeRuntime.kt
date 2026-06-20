package com.utopia.racechronobridge.background

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.utopia.racechronobridge.ble.BleDeviceScanner
import com.utopia.racechronobridge.ble.BleElmClient
import com.utopia.racechronobridge.ble.BleScanDevice
import com.utopia.racechronobridge.bluetooth.ClassicBluetoothElmClient
import com.utopia.racechronobridge.elm.Elm327Session
import com.utopia.racechronobridge.elm.Elm327Transport
import com.utopia.racechronobridge.logging.DebugLog
import com.utopia.racechronobridge.racechrono.RaceChronoTcpServer
import com.utopia.racechronobridge.racechrono.RaceChronoTcpStatus
import com.utopia.racechronobridge.racechrono.Rc3Sentence
import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannel
import com.utopia.racechronobridge.ssm2.FakeSubaruTelemetrySource
import com.utopia.racechronobridge.ssm2.Ssm2Reader
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class BridgeRuntimeState(
    val tcpStatus: RaceChronoTcpStatus = RaceChronoTcpStatus(running = false, clientConnected = false),
    val bleStatus: String = "disconnected",
    val elmStatus: String = "not initialized",
    val pollingStatus: String = "stopped",
    val scanDevices: List<BleScanDevice> = emptyList(),
    val telemetry: SubaruTelemetry = SubaruTelemetry.EMPTY,
    val logs: List<String> = emptyList(),
)

class BridgeRuntime(
    context: Context,
) {
    interface Listener {
        fun onBridgeRuntimeStateChanged(state: BridgeRuntimeState)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val debugLog = DebugLog()
    private val fakeTelemetrySource = FakeSubaruTelemetrySource()
    private val pollExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val rc3Sentence = Rc3Sentence()
    private val scanDevicesLock = Any()
    private val scanDevices = linkedMapOf<String, BleScanDevice>()
    private val tcpServer = RaceChronoTcpServer(
        onStatusChanged = { status -> updateState { copy(tcpStatus = status) } },
        onLog = ::appendLog,
    )

    @Volatile
    private var state = BridgeRuntimeState()

    @Volatile
    private var channelModes: Map<TelemetryChannel, ChannelMode> = TelemetryChannel.defaultModes()

    @Volatile
    private var customChannels: List<CustomTelemetryChannel> = emptyList()

    private var bleScanner: BleDeviceScanner? = null
    private var elmTransport: Elm327Transport? = null
    private var elmSession: Elm327Session? = null
    private var ssm2Reader: Ssm2Reader? = null
    private var fakePollingTask: ScheduledFuture<*>? = null
    private var realPollingTask: ScheduledFuture<*>? = null

    @Volatile
    private var lastTelemetry: SubaruTelemetry = SubaruTelemetry.EMPTY

    private var rc3Count = 0
    private var autoConnectAttempted = false

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onBridgeRuntimeStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setChannelModes(modes: Map<TelemetryChannel, ChannelMode>) {
        channelModes = modes.toMap()
        updateState { copy(telemetry = lastTelemetry) }
    }

    fun setCustomChannels(channels: List<CustomTelemetryChannel>) {
        customChannels = channels.toList()
        updateState { copy(telemetry = lastTelemetry) }
    }

    fun startTcpServer() {
        tcpServer.start()
    }

    fun stopTcpServer() {
        tcpServer.stop()
    }

    fun startBleScan() {
        stopBleScan()
        synchronized(scanDevicesLock) {
            scanDevices.clear()
        }
        publishScanDevices()
        appendLog("Starting Bluetooth scan.")
        val scanner = BleDeviceScanner(
            context = appContext,
            onDeviceFound = { device ->
                synchronized(scanDevicesLock) {
                    scanDevices[device.address] = device
                }
                publishScanDevices()
            },
            onScanStateChanged = { scanning ->
                updateState { copy(bleStatus = if (scanning) "scanning" else "scan stopped") }
            },
            onLog = ::appendLog,
        )
        bleScanner = scanner
        scanner.start()
    }

    fun stopBleScan() {
        bleScanner?.stop()
        bleScanner = null
    }

    fun connectBleDevice(scanDevice: BleScanDevice) {
        stopBleScan()
        stopTelemetry()
        updateState { copy(bleStatus = "connecting\n${scanDevice.name}") }
        appendLog("Connecting Bluetooth device: ${scanDevice.name} ${scanDevice.address}")
        pollExecutor.execute {
            try {
                elmTransport?.close()
                elmSession = null
                ssm2Reader = null
                val transport = connectElmTransport(scanDevice)
                if (transport != null) {
                    elmTransport = transport
                    saveLastBluetoothDevice(scanDevice)
                    updateState {
                        copy(
                            bleStatus = "connected\n${scanDevice.name}",
                            elmStatus = "not initialized",
                        )
                    }
                    appendLog("Bluetooth connected: ${scanDevice.name}")
                    initializeElm327(startPollingOnSuccess = true)
                } else {
                    updateState { copy(bleStatus = "connection failed") }
                    appendLog("Bluetooth connection failed.")
                }
            } catch (error: Exception) {
                updateState { copy(bleStatus = "connection failed") }
                appendLog("Bluetooth connection failed: ${error.message}")
            }
        }
    }

    fun initializeElm327(startPollingOnSuccess: Boolean = false) {
        val client = elmTransport
        if (client == null) {
            appendLog("Select and connect a Bluetooth device before ELM327 initialization.")
            return
        }

        updateState { copy(elmStatus = "initializing") }
        pollExecutor.execute {
            try {
                val session = Elm327Session(
                    client = client,
                    onRawResponse = ::appendLog,
                    onLog = ::appendLog,
                )
                session.initialize()
                elmSession = session
                ssm2Reader = Ssm2Reader(session)
                updateState { copy(elmStatus = "initialized for SSM2") }
                if (startPollingOnSuccess) {
                    startRealTelemetry()
                }
            } catch (error: Exception) {
                updateState { copy(elmStatus = "initialization failed") }
                appendLog("ELM327 initialization failed: ${error.message}")
            }
        }
    }

    fun startFakeTelemetry() {
        if (fakePollingTask != null) {
            appendLog("Fake telemetry is already running.")
            return
        }

        stopRealTelemetry()
        appendLog("Starting fake telemetry at 5 Hz.")
        updateState { copy(pollingStatus = "fake telemetry\n5 Hz") }
        fakePollingTask = pollExecutor.scheduleAtFixedRate(
            {
                val telemetry = fakeTelemetrySource.next()
                publishTelemetry(telemetry)
            },
            0,
            200,
            TimeUnit.MILLISECONDS,
        )
    }

    fun startRealTelemetry() {
        val reader = ssm2Reader
        if (reader == null) {
            appendLog("Initialize ELM327 before starting SSM2 polling.")
            return
        }
        if (realPollingTask != null) {
            appendLog("SSM2 polling is already running.")
            return
        }

        stopFakeTelemetry()
        appendLog("Starting SSM2 polling.")
        updateState { copy(pollingStatus = "SSM2 over CAN") }
        realPollingTask = pollExecutor.scheduleWithFixedDelay(
            {
                try {
                    publishTelemetry(reader.readTelemetry(lastTelemetry, channelModes, customChannels))
                } catch (error: Exception) {
                    appendLog("SSM2 polling failed: ${error.message}")
                }
            },
            0,
            250,
            TimeUnit.MILLISECONDS,
        )
    }

    fun stopTelemetry() {
        stopFakeTelemetry()
        stopRealTelemetry()
    }

    fun attemptAutoConnectLastDevice() {
        if (autoConnectAttempted || elmTransport != null) {
            return
        }
        autoConnectAttempted = true
        val preferences = preferences()
        val address = preferences.getString(KEY_LAST_DEVICE_ADDRESS, null) ?: return
        val savedName = preferences.getString(KEY_LAST_DEVICE_NAME, "(last device)") ?: "(last device)"
        appendLog("Auto-selecting last Bluetooth device: $savedName $address")
        try {
            val bluetoothAdapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (bluetoothAdapter == null) {
                appendLog("Bluetooth adapter is unavailable.")
                return
            }
            @SuppressLint("MissingPermission")
            val device = bluetoothAdapter.getRemoteDevice(address)
            @SuppressLint("MissingPermission")
            val name = device.name ?: savedName
            @SuppressLint("MissingPermission")
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED
            val scanDevice = BleScanDevice(
                device = device,
                name = name,
                address = address,
                rssi = BleDeviceScanner.RSSI_UNKNOWN,
                likelyElmAdapter = BleDeviceScanner.isLikelyElmAdapter(name),
                bonded = bonded,
            )
            synchronized(scanDevicesLock) {
                scanDevices[address] = scanDevice
            }
            publishScanDevices()
            connectBleDevice(scanDevice)
        } catch (error: Exception) {
            appendLog("Auto Bluetooth connection failed before connect: ${error.message}")
        }
    }

    fun appendUserLog(message: String) {
        appendLog(message)
    }

    fun shutdown() {
        stopTelemetry()
        stopBleScan()
        elmTransport?.close()
        elmTransport = null
        elmSession = null
        ssm2Reader = null
        tcpServer.stop()
        pollExecutor.shutdownNow()
    }

    private fun stopFakeTelemetry() {
        val task = fakePollingTask ?: return
        task.cancel(true)
        fakePollingTask = null
        updateState { copy(pollingStatus = "stopped") }
        appendLog("Fake telemetry stopped.")
    }

    private fun stopRealTelemetry() {
        val task = realPollingTask ?: return
        task.cancel(true)
        realPollingTask = null
        updateState { copy(pollingStatus = "stopped") }
        appendLog("SSM2 polling stopped.")
    }

    private fun publishTelemetry(telemetry: SubaruTelemetry) {
        lastTelemetry = telemetry
        val sentence = rc3Sentence.format(
            count = rc3Count,
            telemetry = telemetry,
            channelModes = channelModes,
            customChannels = customChannels,
        )
        rc3Count = (rc3Count + 1) and 0xFFFF
        tcpServer.send(sentence)
        updateState { copy(telemetry = telemetry) }
    }

    private fun publishScanDevices() {
        val devices = synchronized(scanDevicesLock) {
            scanDevices.values
                .sortedWith(
                    compareByDescending<BleScanDevice> { it.bonded }
                        .thenByDescending { it.likelyElmAdapter }
                        .thenByDescending { it.rssi },
                )
        }
        updateState { copy(scanDevices = devices) }
    }

    private fun connectElmTransport(scanDevice: BleScanDevice): Elm327Transport? {
        if (scanDevice.bonded) {
            val sppClient = ClassicBluetoothElmClient(
                device = scanDevice.device,
                onLog = ::appendLog,
            )
            if (sppClient.connect()) {
                appendLog("Using paired Bluetooth SPP transport.")
                return sppClient
            }
            sppClient.close()
            appendLog("Paired Bluetooth SPP failed. Falling back to BLE GATT.")
        }

        val bleClient = BleElmClient(
            context = appContext,
            device = scanDevice.device,
            onLog = ::appendLog,
        )
        return if (bleClient.connect()) {
            appendLog("Using BLE GATT transport.")
            bleClient
        } else {
            bleClient.close()
            null
        }
    }

    private fun saveLastBluetoothDevice(scanDevice: BleScanDevice) {
        preferences().edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, scanDevice.address)
            .putString(KEY_LAST_DEVICE_NAME, scanDevice.name)
            .apply()
    }

    private fun appendLog(message: String) {
        debugLog.append(message)
        updateState { copy(logs = debugLog.snapshot()) }
    }

    @Synchronized
    private fun updateState(update: BridgeRuntimeState.() -> BridgeRuntimeState) {
        state = state.update()
        val snapshot = state
        mainHandler.post {
            listeners.forEach { listener -> listener.onBridgeRuntimeStateChanged(snapshot) }
        }
    }

    private fun preferences() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "racechrono_bridge"
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
    }
}
