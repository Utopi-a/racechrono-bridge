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
import com.utopia.racechronobridge.logger.LogLayout
import com.utopia.racechronobridge.logger.LogPreset
import com.utopia.racechronobridge.logger.LogPresets
import com.utopia.racechronobridge.logger.LogRecord
import com.utopia.racechronobridge.logger.LoggerSession
import com.utopia.racechronobridge.logger.LoggerStats
import com.utopia.racechronobridge.logger.RomRaiderCsvWriter
import com.utopia.racechronobridge.logger.updatedWith
import com.utopia.racechronobridge.logging.DebugLog
import com.utopia.racechronobridge.racechrono.RaceChronoTcpServer
import com.utopia.racechronobridge.racechrono.RaceChronoTcpStatus
import com.utopia.racechronobridge.racechrono.Rc3Sentence
import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannel
import com.utopia.racechronobridge.ssm2.FakeSubaruTelemetrySource
import com.utopia.racechronobridge.ssm2.Ssm2Client
import com.utopia.racechronobridge.ssm2.Ssm2Identification
import com.utopia.racechronobridge.ssm2.Ssm2Reader
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import com.utopia.racechronobridge.ssm2.catalog.SsmCatalog
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class StepState { IDLE, WORKING, OK, FAILED }

/** Where live telemetry comes from. CAN reads the GH8 broadcast and fills the rest with SSM2. */
enum class AcquisitionMode { CAN, SSM2 }

data class StepStatus(
    val state: StepState,
    val detail: String,
)

/** ロガー画面に出す 1 列分の最新値。 */
data class LoggerValue(
    val header: String,
    val value: String,
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
    val logger: StepStatus = StepStatus(StepState.IDLE, "停止中"),
    val loggerFile: File? = null,
    val loggerPresetKey: String = LogPresets.FUEL.key,
    val loggerRomTag: String = "",
    val loggerValues: List<LoggerValue> = emptyList(),
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

    @Volatile
    private var loggerSession: LoggerSession? = null

    /** 記録の準備中から終了までの停止の合図。準備中は loggerSession がまだ無いので、こちらで止める。 */
    @Volatile
    private var loggerStop: AtomicBoolean? = null

    /** RomRaider ロガー定義から作った SSM パラメータ表（assets）。最初に記録を始めたときに読む。 */
    private val catalog: SsmCatalog by lazy {
        appContext.assets.open(CATALOG_ASSET).bufferedReader().useLines { SsmCatalog.parse(it) }
    }

    private var rc3Count = 0
    private var autoConnectAttempted = false

    init {
        val savedMode = preferences().getString(KEY_ACQUISITION_MODE, null)
            ?.let { runCatching { AcquisitionMode.valueOf(it) }.getOrNull() }
        if (savedMode != null) {
            state = state.copy(acquisitionMode = savedMode)
        }
        val savedPreset = preferences().getString(KEY_LOGGER_PRESET, null)?.takeIf { LogPresets.byKey(it) != null }
        state = state.copy(
            loggerPresetKey = savedPreset ?: state.loggerPresetKey,
            loggerRomTag = preferences().getString(KEY_LOGGER_ROM_TAG, "").orEmpty(),
        )
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
        stopLogging()
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
        if (isLogging()) {
            appendLog("Stop logging before starting CAN capture.")
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

    fun setLoggerPreset(key: String) {
        if (LogPresets.byKey(key) == null || state.loggerPresetKey == key) return
        preferences().edit().putString(KEY_LOGGER_PRESET, key).apply()
        updateState { copy(loggerPresetKey = key) }
    }

    /** ファイル名に入れる ROM の版名（例: mod3、revA）。どの ROM で取ったログかを後から区別するため。 */
    fun setLoggerRomTag(tag: String) {
        val cleaned = tag.trim()
        if (state.loggerRomTag == cleaned) return
        preferences().edit().putString(KEY_LOGGER_ROM_TAG, cleaned).apply()
        updateState { copy(loggerRomTag = cleaned) }
    }

    /**
     * 選んだプリセットで SSM2 を連続で読み、RomRaider 形式の CSV に記録する。
     * 記録中は通常の取得と CAN 記録を止め、RaceChrono には記録した値を送り続ける。止めたら通常の取得に戻す。
     */
    fun startLogging() {
        val transport = elmTransport
        val session = elmSession
        if (transport == null || session == null) {
            appendLog("Connect the adapter and initialize ELM327 before logging.")
            return
        }
        if (isLogging()) {
            appendLog("Logger is already running.")
            return
        }
        if (canCaptureSession != null) {
            appendLog("Stop CAN capture before logging.")
            return
        }
        val preset = LogPresets.byKey(state.loggerPresetKey) ?: LogPresets.FUEL
        val romTag = state.loggerRomTag
        // 準備（再同期・ECU 識別）の途中でも止められるよう、停止の合図は先に作る
        val stop = AtomicBoolean(false)
        loggerStop = stop
        stopTelemetry()
        updateState {
            copy(
                logger = StepStatus(StepState.WORKING, "準備中"),
                loggerValues = emptyList(),
                ecu = StepStatus(StepState.IDLE, "記録中は通常の取得を止めています"),
            )
        }
        pollExecutor.execute {
            var writer: RomRaiderCsvWriter? = null
            try {
                val client = Ssm2Client(transport)
                // 止めた取得の応答が遅れて届いていても、ここで読み捨ててから始める
                client.resynchronize()
                // CAN 受信モードの後はヘッダー表示・自動整形が切り替わっているので、SSM2 用に戻す
                session.configure(SSM2_COMMANDS)
                if (stop.get()) throw LoggingCancelled()
                val identification = identifyEcu(client)
                if (stop.get()) throw LoggingCancelled()
                val ecuId = identification?.ecuId ?: DEFAULT_ECU_ID
                if (ecuId !in catalog.ecuIds()) {
                    appendLog("ECU ID $ecuId has no extended parameters in the catalog; only standard parameters are logged.")
                }
                val available = catalog.forEcu(ecuId)
                // ECU が対応していない標準パラメータは、返ってきたバイトに意味が無いので記録しない
                val unsupported = identification?.let { id ->
                    available.values.filter { p -> p.capability?.isSupportedBy(id.bytes) == false }.map { it.id }.toSet()
                }.orEmpty()
                val layout = LogLayout.resolve(preset, available - unsupported)
                val notSupported = layout.missing.filter { it in unsupported }
                val notInCatalog = layout.missing.filterNot { it in unsupported }
                if (notSupported.isNotEmpty()) appendLog("Not supported by ECU $ecuId: ${notSupported.joinToString()}")
                if (notInCatalog.isNotEmpty()) appendLog("Not in catalog for ECU $ecuId: ${notInCatalog.joinToString()}")
                val file = newLoggerFile(preset, romTag, ecuVerified = identification != null)
                writer = RomRaiderCsvWriter(file.bufferedWriter(), layout)
                val logger = LoggerSession(
                    client = client,
                    layout = layout,
                    writer = writer,
                    clock = SystemClock::elapsedRealtime,
                    onRecord = { record, stats -> onLoggerRecord(layout, record, stats, ecuVerified = identification != null) },
                    onLog = ::appendLog,
                )
                loggerSession = logger
                if (stop.get()) logger.requestStop()
                appendLog("Logging ECU $ecuId${if (identification == null) " (unverified)" else ""} preset ${preset.key} to ${file.absolutePath}")
                updateState { copy(logger = StepStatus(StepState.WORKING, "記録中"), loggerFile = file) }
                logger.run()
                updateState { copy(logger = StepStatus(StepState.OK, "保存済み ${file.name}")) }
            } catch (cancelled: LoggingCancelled) {
                updateState { copy(logger = StepStatus(StepState.IDLE, "開始前に止めました")) }
                appendLog("Logging cancelled before recording started.")
            } catch (error: Exception) {
                updateState { copy(logger = StepStatus(StepState.FAILED, "失敗: ${error.message}")) }
                appendLog("Logging failed: ${error.message}")
            } finally {
                writer?.close()
                loggerSession = null
                if (loggerStop === stop) loggerStop = null
            }
            if (elmTransport === transport) {
                appendLog("Restoring telemetry after logging.")
                initializeElm327(startPollingOnSuccess = true)
            }
        }
    }

    /** ECU ID と対応フラグを読む。失敗したら通信を立て直して 1 回だけやり直し、それでも駄目なら null。 */
    private fun identifyEcu(client: Ssm2Client): Ssm2Identification? {
        repeat(2) { attempt ->
            try {
                return client.identify()
            } catch (error: Exception) {
                appendLog("ECU identification failed (attempt ${attempt + 1}): ${error.message}")
                runCatching { client.resynchronize() }
            }
        }
        appendLog("Using ECU ID $DEFAULT_ECU_ID without verification; file name is tagged ecu-unverified.")
        return null
    }

    fun stopLogging() {
        loggerStop?.set(true)
        loggerSession?.requestStop()
    }

    private class LoggingCancelled : Exception("cancelled")

    private fun isLogging(): Boolean = loggerSession != null || loggerStop != null || state.logger.state == StepState.WORKING

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
        if (isLogging()) {
            appendLog("Stop logging before starting fake telemetry.")
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
        if (isLogging()) {
            // 記録はアダプターを専有する。記録が終われば通常の取得は自動で戻る
            appendLog("Telemetry stays paused while logging.")
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
        stopLogging()
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
        // 送信中の SSM2 要求に割り込むと、その応答が次の要求（記録の準備など）の応答として届いて対応がずれる。
        // 単一スレッドなので、今の 1 周を終えてから次の作業が始まる
        task.cancel(false)
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

    private fun newLoggerFile(preset: LogPreset, romTag: String, ecuVerified: Boolean): File {
        val directory = appContext.getExternalFilesDir(LOGGER_DIRECTORY) ?: File(appContext.filesDir, LOGGER_DIRECTORY)
        directory.mkdirs()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val tag = loggerFileTag(romTag)
        val suffix = if (ecuVerified) "" else "_ecu-unverified"
        return File(directory, "romraiderlog_${timestamp}_${preset.key}_$tag$suffix.csv")
    }

    private fun onLoggerRecord(layout: LogLayout, record: LogRecord, stats: LoggerStats, ecuVerified: Boolean) {
        publishTelemetry(lastTelemetry.updatedWith(record, System.currentTimeMillis()))
        val values = layout.columns.map { c ->
            LoggerValue(c.header, record.values[c.id]?.let(c.conversion::formatValue) ?: "-")
        } + layout.derived.map { d -> LoggerValue(d.header, record.derived[d]?.let(d::format) ?: "-") }
        val detail = "記録中  ${"%,d".format(stats.samples)} 件 / ${"%.1f".format(stats.samplesPerSecond)} 回/秒 / " +
            "要求 ${stats.requestsPerSample} 回" + (if (stats.failures > 0) " / 失敗 ${stats.failures}" else "") +
            (if (stats.degraded) " / ブロック読み出し停止中" else "") + (if (!ecuVerified) " / ECU 未確認" else "")
        updateState { copy(logger = StepStatus(StepState.WORKING, detail), loggerValues = values) }
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
        private const val KEY_LOGGER_PRESET = "logger_preset"
        private const val KEY_LOGGER_ROM_TAG = "logger_rom_tag"
        private const val LOGGER_DIRECTORY = "logs"
        private const val CATALOG_ASSET = "ssm_catalog.tsv"

        /** ファイル名に入れる版名。文字（日本語を含む）・数字・_・- 以外は除く。空なら rom。 */
        fun loggerFileTag(romTag: String): String = romTag.replace(Regex("[^\\p{L}\\p{N}_-]"), "").ifBlank { "rom" }

        /** ECU 識別に失敗したときに使う ECU ID（GH8 の A2TB100U）。 */
        private const val DEFAULT_ECU_ID = "5104584007"
        private val SSM2_COMMANDS = listOf("ATH0", "ATCAF1", "ATSH7E0")
    }
}
