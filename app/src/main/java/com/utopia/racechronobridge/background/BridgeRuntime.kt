package com.utopia.racechronobridge.background

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.utopia.racechronobridge.ble.BleDeviceScanner
import com.utopia.racechronobridge.ble.BleElmClient
import com.utopia.racechronobridge.ble.BleScanDevice
import com.utopia.racechronobridge.bluetooth.ClassicBluetoothElmClient
import com.utopia.racechronobridge.canbus.CanTelemetrySession
import com.utopia.racechronobridge.canlog.CanCaptureProgress
import com.utopia.racechronobridge.canlog.CanCaptureSession
import com.utopia.racechronobridge.canlog.CanCaptureWriter
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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class StepState { IDLE, WORKING, OK, FAILED }

/** Where live telemetry comes from. CAN reads the GH8 broadcast and fills the rest with SSM2. */
enum class AcquisitionMode { CAN, SSM2 }

data class StepStatus(
    val state: StepState,
    val detail: String,
)

data class BridgeRuntimeState(
    val tcpStatus: RaceChronoTcpStatus = RaceChronoTcpStatus(running = false, clientConnected = false),
    val adapter: StepStatus = StepStatus(StepState.IDLE, "未接続"),
    val scanning: Boolean = false,
    val ecu: StepStatus = StepStatus(StepState.IDLE, "アダプタの接続待ち"),
    val scanDevices: List<BleScanDevice> = emptyList(),
    val telemetry: SubaruTelemetry = SubaruTelemetry.EMPTY,
    val logs: List<String> = emptyList(),
    val capture: StepStatus = StepStatus(StepState.IDLE, "停止中"),
    val canCaptureFile: File? = null,
    val acquisitionMode: AcquisitionMode = AcquisitionMode.CAN,
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
    private var canTelemetrySession: CanTelemetrySession? = null

    @Volatile
    private var canCaptureSession: CanCaptureSession? = null

    @Volatile
    private var canCaptureWriter: CanCaptureWriter? = null

    @Volatile
    private var lastTelemetry: SubaruTelemetry = SubaruTelemetry.EMPTY

    private var rc3Count = 0
    private var autoConnectAttempted = false

    init {
        val savedMode = preferences().getString(KEY_ACQUISITION_MODE, null)
            ?.let { runCatching { AcquisitionMode.valueOf(it) }.getOrNull() }
        if (savedMode != null) {
            state = state.copy(acquisitionMode = savedMode)
        }
    }

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
                updateState { copy(scanning = scanning) }
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
        stopCanCapture()
        stopTelemetry()
        updateState { copy(adapter = StepStatus(StepState.WORKING, "${scanDevice.name} に接続中")) }
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
                    updateState { copy(adapter = StepStatus(StepState.OK, scanDevice.name)) }
                    appendLog("Bluetooth connected: ${scanDevice.name}")
                    initializeElm327(startPollingOnSuccess = true)
                } else {
                    updateState { copy(adapter = StepStatus(StepState.FAILED, "接続できませんでした")) }
                    appendLog("Bluetooth connection failed.")
                }
            } catch (error: Exception) {
                updateState { copy(adapter = StepStatus(StepState.FAILED, "接続できませんでした")) }
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

        updateState { copy(ecu = StepStatus(StepState.WORKING, "ELM327 を初期化中")) }
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
                updateState { copy(ecu = StepStatus(StepState.WORKING, "初期化済み・受信待ち")) }
                if (startPollingOnSuccess) {
                    startRealTelemetry()
                }
            } catch (error: Exception) {
                updateState { copy(ecu = StepStatus(StepState.FAILED, "ELM327 の初期化に失敗")) }
                appendLog("ELM327 initialization failed: ${error.message}")
            }
        }
    }

    fun startCanCapture() {
        val transport = elmTransport
        if (transport == null) {
            appendLog("Connect the Bluetooth adapter before starting CAN capture.")
            return
        }
        if (canCaptureSession != null) {
            appendLog("CAN capture is already running.")
            return
        }

        // ATMA and SSM2 polling share one adapter, so polling pauses during capture.
        stopTelemetry()
        val file = newCaptureFile()
        val startedAt = SystemClock.elapsedRealtime()
        val writer = CanCaptureWriter(
            writer = file.bufferedWriter(),
            elapsedMillis = { SystemClock.elapsedRealtime() - startedAt },
        )
        writer.writeHeader("RaceChrono Bridge CAN capture ${LocalDateTime.now()}")
        val session = CanCaptureSession(
            transport = transport,
            writer = writer,
            onLog = ::appendLog,
            onProgress = { progress -> updateState { copy(capture = StepStatus(StepState.WORKING, progress.summary())) } },
        )
        canCaptureWriter = writer
        canCaptureSession = session
        updateState {
            copy(
                capture = StepStatus(StepState.WORKING, "開始中"),
                canCaptureFile = file,
                ecu = StepStatus(StepState.IDLE, "CAN 記録中は SSM2 を止めています"),
            )
        }
        appendLog("CAN capture file: ${file.absolutePath}")

        pollExecutor.execute {
            try {
                session.run()
                updateState { copy(capture = StepStatus(StepState.OK, "保存済み ${file.name}")) }
            } catch (error: Exception) {
                updateState { copy(capture = StepStatus(StepState.FAILED, "失敗: ${error.message}")) }
                appendLog("CAN capture failed: ${error.message}")
            } finally {
                writer.close()
                canCaptureWriter = null
                canCaptureSession = null
            }
            if (elmTransport === transport) {
                appendLog("Restoring SSM2 polling after CAN capture.")
                initializeElm327(startPollingOnSuccess = true)
            }
        }
    }

    fun stopCanCapture() {
        canCaptureSession?.requestStop()
    }

    fun markCanCapture(label: String) {
        val writer = canCaptureWriter
        if (writer == null) {
            appendLog("Start CAN capture before adding markers.")
            return
        }
        writer.marker(label)
        appendLog("CAN capture marker: $label")
    }

    fun startFakeTelemetry() {
        if (fakePollingTask != null) {
            appendLog("Fake telemetry is already running.")
            return
        }

        stopRealTelemetry()
        appendLog("Starting fake telemetry at 5 Hz.")
        updateState { copy(ecu = StepStatus(StepState.OK, "テストデータを送信中 (5 Hz)")) }
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

    fun setAcquisitionMode(mode: AcquisitionMode) {
        if (state.acquisitionMode == mode) {
            return
        }
        preferences().edit().putString(KEY_ACQUISITION_MODE, mode.name).apply()
        val wasRunning = realPollingTask != null || canTelemetrySession != null
        stopRealTelemetry()
        updateState { copy(acquisitionMode = mode) }
        appendLog("Acquisition mode changed to $mode.")
        if (wasRunning) {
            startRealTelemetry()
        }
    }

    fun startRealTelemetry() {
        val reader = ssm2Reader
        val session = elmSession
        val transport = elmTransport
        if (reader == null || session == null || transport == null) {
            appendLog("Initialize ELM327 before starting telemetry.")
            return
        }
        if (realPollingTask != null || canTelemetrySession != null) {
            appendLog("Telemetry is already running.")
            return
        }

        stopFakeTelemetry()
        if (state.acquisitionMode == AcquisitionMode.CAN) {
            startCanTelemetry(transport, session, reader)
            return
        }
        appendLog("Starting SSM2 polling.")
        updateState { copy(ecu = StepStatus(StepState.OK, "SSM2 で受信中")) }
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
        stopCanCapture()
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
        updateState { copy(ecu = StepStatus(StepState.IDLE, "停止中")) }
        appendLog("Fake telemetry stopped.")
    }

    private fun startCanTelemetry(transport: Elm327Transport, session: Elm327Session, reader: Ssm2Reader) {
        val canSession = CanTelemetrySession(
            transport = transport,
            elmSession = session,
            ssm2Reader = reader,
            channelModes = { channelModes },
            customChannels = { customChannels },
            onTelemetry = ::publishTelemetry,
            onLog = ::appendLog,
        )
        canTelemetrySession = canSession
        updateState { copy(ecu = StepStatus(StepState.OK, "CAN 受信中（SSM2 は合間に取得）")) }
        pollExecutor.execute {
            try {
                canSession.run()
            } catch (error: Exception) {
                appendLog("CAN telemetry failed: ${error.message}")
                updateState { copy(ecu = StepStatus(StepState.FAILED, "CAN 受信が止まりました")) }
            } finally {
                if (canTelemetrySession === canSession) {
                    canTelemetrySession = null
                }
            }
        }
    }

    private fun stopRealTelemetry() {
        val canSession = canTelemetrySession
        if (canSession != null) {
            canSession.requestStop()
            canTelemetrySession = null
            updateState { copy(ecu = StepStatus(StepState.IDLE, "停止中")) }
            appendLog("CAN telemetry stop requested.")
        }
        val task = realPollingTask ?: return
        task.cancel(true)
        realPollingTask = null
        updateState { copy(ecu = StepStatus(StepState.IDLE, "停止中")) }
        appendLog("SSM2 polling stopped.")
    }

    private fun newCaptureFile(): File {
        val directory = appContext.getExternalFilesDir(CAN_CAPTURE_DIRECTORY)
            ?: File(appContext.filesDir, CAN_CAPTURE_DIRECTORY)
        directory.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return File(directory, "can-$timestamp.csv")
    }

    private fun CanCaptureProgress.summary(): String =
        "記録中  ${"%,d".format(frames)} フレーム / ${ids.size} ID / 再開 $restarts 回"

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
        private const val CAN_CAPTURE_DIRECTORY = "can-captures"
        private const val KEY_ACQUISITION_MODE = "acquisition_mode"
    }
}
