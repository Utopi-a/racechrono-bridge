package com.utopia.racechronobridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.utopia.racechronobridge.ble.BleDeviceScanner
import com.utopia.racechronobridge.ble.BleElmClient
import com.utopia.racechronobridge.ble.BleScanDevice
import com.utopia.racechronobridge.bluetooth.ClassicBluetoothElmClient
import com.utopia.racechronobridge.elm.Elm327Session
import com.utopia.racechronobridge.elm.Elm327Transport
import com.utopia.racechronobridge.logging.DebugLog
import com.utopia.racechronobridge.racechrono.RaceChronoTcpServer
import com.utopia.racechronobridge.racechrono.Rc3Sentence
import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.FakeSubaruTelemetrySource
import com.utopia.racechronobridge.ssm2.Ssm2Reader
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import com.utopia.racechronobridge.ssm2.modeFor
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debugLog = DebugLog()
    private val fakeTelemetrySource = FakeSubaruTelemetrySource()
    private val pollExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val rc3Sentence = Rc3Sentence()
    private val scanDevices = linkedMapOf<String, BleScanDevice>()
    private val channelModes = TelemetryChannel.defaultModes()
    private val preferences: SharedPreferences by lazy {
        getSharedPreferences("racechrono_bridge", Context.MODE_PRIVATE)
    }

    private var pendingBlePermissionAction: (() -> Unit)? = null
    private var bleScanner: BleDeviceScanner? = null
    private var elmTransport: Elm327Transport? = null
    private var elmSession: Elm327Session? = null
    private var ssm2Reader: Ssm2Reader? = null
    private var fakePollingTask: ScheduledFuture<*>? = null
    private var realPollingTask: ScheduledFuture<*>? = null
    private var lastTelemetry: SubaruTelemetry = SubaruTelemetry.EMPTY
    private var rc3Count = 0

    private lateinit var tcpServer: RaceChronoTcpServer
    private lateinit var telemetryView: TextView
    private lateinit var tcpStatusView: TextView
    private lateinit var bleStatusView: TextView
    private lateinit var elmStatusView: TextView
    private lateinit var pollingStatusView: TextView
    private lateinit var channelSettingsLayout: LinearLayout
    private lateinit var devicesLayout: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadChannelModes()

        tcpServer = RaceChronoTcpServer(
            onStatusChanged = { status ->
                mainHandler.post {
                    tcpStatusView.text = "TCP: ${status.host}:${status.port} / " +
                        "server=${if (status.running) "running" else "stopped"} / " +
                        "client=${if (status.clientConnected) "connected" else "not connected"}"
                }
            },
            onLog = ::appendLog,
        )

        setContentView(buildContentView())
        tcpStatusView.text = "TCP: ${RaceChronoTcpServer.HOST}:${RaceChronoTcpServer.PORT} / " +
            "server=stopped / client=not connected"
        renderTelemetry(SubaruTelemetry.EMPTY)
        refreshLog()
        appendLog("App ready. In RaceChrono, enable RC2/RC3 only. Do not enable NMEA 0183.")
        tcpServer.start()
        attemptAutoConnectLastDevice()
    }

    override fun onDestroy() {
        stopTelemetry()
        bleScanner?.stop()
        elmTransport?.close()
        tcpServer.stop()
        pollExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BLE_PERMISSION_REQUEST) {
            return
        }

        val action = pendingBlePermissionAction
        pendingBlePermissionAction = null
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            action?.invoke()
        } else {
            appendLog("BLE permissions were denied.")
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(COLOR_BACKGROUND)
        }

        val title = TextView(this).apply {
            text = "RaceChrono Bridge"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        }
        root.addView(title)

        tcpStatusView = statusText()
        root.addView(tcpStatusView)

        bleStatusView = statusText("Bluetooth: disconnected")
        root.addView(bleStatusView)

        elmStatusView = statusText("ELM327: not initialized")
        root.addView(elmStatusView)

        pollingStatusView = statusText("Polling: stopped")
        root.addView(pollingStatusView)

        telemetryView = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, 24, 0, 24)
        }
        root.addView(telemetryView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(controls)

        controls.addView(button("Start TCP server") { tcpServer.start() })
        controls.addView(button("Stop TCP server") { tcpServer.stop() })
        controls.addView(button("Start fake telemetry") { startFakeTelemetry() })
        controls.addView(button("Scan Bluetooth devices") { startBleScan() })
        controls.addView(button("Stop Bluetooth scan") { bleScanner?.stop() })
        controls.addView(button("Initialize ELM327") { initializeElm327() })
        controls.addView(button("Start SSM2 polling") { startRealTelemetry() })
        controls.addView(button("Stop telemetry") { stopTelemetry() })
        controls.addView(button("Copy RaceChrono mapping") { copyRaceChronoMapping() })
        controls.addView(button("Copy debug log") { copyDebugLog() })

        root.addView(
            TextView(this).apply {
                text = "RaceChrono: DIY > TCP/IP > RC2/RC3 ON, NMEA 0183 OFF, 127.0.0.1:9876"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_ACCENT)
                setPadding(0, 16, 0, 8)
            },
        )

        root.addView(sectionTitle("Channels"))
        root.addView(
            TextView(this).apply {
                text = "RaceChrono RC3 field names are fixed; use this mapping for Analog/Digital labels."
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, 0, 0, 8)
            },
        )
        channelSettingsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(channelSettingsLayout)
        renderChannelSettings()

        root.addView(
            sectionTitle("Bluetooth devices"),
        )

        devicesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(devicesLayout)

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 24, 0, 0)
        }
        root.addView(logView)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun statusText(initialText: String = ""): TextView {
        return TextView(this).apply {
            text = initialText
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 8, 0, 0)
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedRect(COLOR_BUTTON, radius = 18f)
            setOnClickListener { onClick() }
        }
    }

    private fun compactButton(label: String, active: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(12, 4, 12, 4)
            setTextColor(if (active) Color.BLACK else COLOR_TEXT_SECONDARY)
            background = roundedRect(if (active) COLOR_ACCENT else COLOR_SURFACE_ALT, radius = 18f)
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, 24, 0, 8)
        }
    }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun startBleScan() {
        ensureBlePermissions {
            scanDevices.clear()
            devicesLayout.removeAllViews()
            scanner().start()
        }
    }

    private fun scanner(): BleDeviceScanner {
        val current = bleScanner
        if (current != null) {
            return current
        }

        val scanner = BleDeviceScanner(
            context = this,
            onDeviceFound = { device ->
                mainHandler.post {
                    scanDevices[device.address] = device
                    renderScanDevices()
                }
            },
            onScanStateChanged = { scanning ->
                mainHandler.post {
                    bleStatusView.text = if (scanning) {
                        "Bluetooth: scanning"
                    } else {
                        "Bluetooth: scan stopped"
                    }
                }
            },
            onLog = ::appendLog,
        )
        bleScanner = scanner
        return scanner
    }

    private fun renderScanDevices() {
        devicesLayout.removeAllViews()
        scanDevices.values
            .sortedWith(
                compareByDescending<BleScanDevice> { it.bonded }
                    .thenByDescending { it.likelyElmAdapter }
                    .thenByDescending { it.rssi },
            )
            .forEach { device ->
                val likelyLabel = if (device.likelyElmAdapter) "likely ELM" else "BLE"
                val bondedLabel = if (device.bonded) "paired" else "scan"
                val rssiLabel = if (device.rssi == BleDeviceScanner.RSSI_UNKNOWN) "-" else "${device.rssi} dBm"
                devicesLayout.addView(
                    button("${device.name} / ${device.address} / $rssiLabel / $likelyLabel / $bondedLabel") {
                        connectBleDevice(device)
                    },
                )
            }
    }

    private fun renderChannelSettings() {
        if (!::channelSettingsLayout.isInitialized) {
            return
        }
        channelSettingsLayout.removeAllViews()
        TelemetryChannel.entries.forEach { channel ->
            val mode = channelModes.modeFor(channel)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(18, 12, 18, 12)
                background = roundedRect(COLOR_SURFACE, radius = 16f)
            }

            val label = TextView(this).apply {
                text = "${channel.rc3Field}\n${channel.mappingLabel}"
                textSize = 14f
                setTextColor(COLOR_TEXT_PRIMARY)
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )

            ChannelMode.entries.forEach { candidate ->
                row.addView(
                    compactButton(candidate.label, active = mode == candidate) {
                        setChannelMode(channel, candidate)
                    },
                )
            }

            channelSettingsLayout.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, 0, 0, 10)
                },
            )
        }
    }

    private fun setChannelMode(channel: TelemetryChannel, mode: ChannelMode) {
        channelModes[channel] = mode
        preferences.edit().putString(channel.preferenceKey, mode.name).apply()
        renderChannelSettings()
        appendLog("${channel.label} mode changed to ${mode.label}.")
    }

    private fun loadChannelModes() {
        TelemetryChannel.entries.forEach { channel ->
            val value = preferences.getString(channel.preferenceKey, null)
            val mode = value?.let { runCatching { ChannelMode.valueOf(it) }.getOrNull() }
            if (mode != null) {
                channelModes[channel] = mode
            }
        }
    }

    private fun connectBleDevice(scanDevice: BleScanDevice) {
        ensureBlePermissions {
            bleScanner?.stop()
            bleStatusView.text = "Bluetooth: connecting to ${scanDevice.name}"
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
                        mainHandler.post {
                            bleStatusView.text = "Bluetooth: connected to ${scanDevice.name}"
                            showUserMessage("Bluetooth connected: ${scanDevice.name}")
                            elmStatusView.text = "ELM327: not initialized"
                            initializeElm327(startPollingOnSuccess = true)
                        }
                    } else {
                        mainHandler.post {
                            bleStatusView.text = "Bluetooth: connection failed"
                        }
                    }
                } catch (error: Exception) {
                    appendLog("Bluetooth connection failed: ${error.message}")
                    mainHandler.post {
                        bleStatusView.text = "Bluetooth: connection failed"
                    }
                }
            }
        }
    }

    private fun initializeElm327(startPollingOnSuccess: Boolean = false) {
        val client = elmTransport
        if (client == null) {
            appendLog("Select and connect a Bluetooth device before ELM327 initialization.")
            return
        }

        elmStatusView.text = "ELM327: initializing"
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
                mainHandler.post {
                    elmStatusView.text = "ELM327: initialized for SSM2"
                    showUserMessage("ELM327 initialized")
                    if (startPollingOnSuccess) {
                        startRealTelemetry()
                    }
                }
            } catch (error: Exception) {
                appendLog("ELM327 initialization failed: ${error.message}")
                mainHandler.post {
                    elmStatusView.text = "ELM327: initialization failed"
                }
            }
        }
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
            context = this,
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

    private fun startFakeTelemetry() {
        if (fakePollingTask != null) {
            appendLog("Fake telemetry is already running.")
            return
        }

        stopRealTelemetry()
        appendLog("Starting fake telemetry at 5 Hz.")
        pollingStatusView.text = "Polling: fake telemetry at 5 Hz"
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

    private fun startRealTelemetry() {
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
        pollingStatusView.text = "Polling: SSM2 over CAN"
        showUserMessage("SSM2 polling started")
        realPollingTask = pollExecutor.scheduleWithFixedDelay(
            {
                try {
                    publishTelemetry(reader.readTelemetry(lastTelemetry, channelModes))
                } catch (error: Exception) {
                    appendLog("SSM2 polling failed: ${error.message}")
                }
            },
            0,
            250,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun publishTelemetry(telemetry: SubaruTelemetry) {
        lastTelemetry = telemetry
        val sentence = rc3Sentence.format(count = rc3Count, telemetry = telemetry, channelModes = channelModes)
        rc3Count = (rc3Count + 1) and 0xFFFF
        tcpServer.send(sentence)
        mainHandler.post { renderTelemetry(telemetry) }
    }

    private fun stopTelemetry() {
        stopFakeTelemetry()
        stopRealTelemetry()
    }

    private fun stopFakeTelemetry() {
        val task = fakePollingTask ?: return
        task.cancel(true)
        fakePollingTask = null
        if (::pollingStatusView.isInitialized) {
            pollingStatusView.text = "Polling: stopped"
        }
        appendLog("Fake telemetry stopped.")
    }

    private fun stopRealTelemetry() {
        val task = realPollingTask ?: return
        task.cancel(true)
        realPollingTask = null
        if (::pollingStatusView.isInitialized) {
            pollingStatusView.text = "Polling: stopped"
        }
        appendLog("SSM2 polling stopped.")
    }

    private fun renderTelemetry(telemetry: SubaruTelemetry) {
        telemetryView.text = listOf(
            telemetryLine(TelemetryChannel.RPM, "%.0f rpm", telemetry.rpm),
            telemetryLine(TelemetryChannel.BOOST, "%.1f kPa", telemetry.boostKpa),
            telemetryLine(TelemetryChannel.COOLANT, "%.1f C", telemetry.coolantC),
            telemetryLine(TelemetryChannel.THROTTLE, "%.1f %%", telemetry.throttlePercent),
            telemetryLine(TelemetryChannel.ACCELERATOR, "%.1f %%", telemetry.acceleratorPercent),
            telemetryLine(TelemetryChannel.PRIMARY_WGDC, "%.1f %%", telemetry.primaryWastegateDutyPercent),
            telemetryLine(TelemetryChannel.VEHICLE_SPEED, "%.1f km/h", telemetry.vehicleSpeedKph),
            telemetryLine(TelemetryChannel.GEAR, "%d", telemetry.gear),
            telemetryLine(TelemetryChannel.INTAKE_AIR_TEMP, "%.1f C", telemetry.intakeAirTempC),
            telemetryLine(TelemetryChannel.BATTERY_VOLTAGE, "%.2f V", telemetry.batteryVoltage),
            telemetryLine(TelemetryChannel.MASS_AIRFLOW, "%.1f g/s", telemetry.massAirflowGps),
            telemetryLine(TelemetryChannel.IGNITION_TIMING, "%.1f deg", telemetry.ignitionTimingDeg),
            telemetryLine(TelemetryChannel.KNOCK_CORRECTION, "%.1f deg", telemetry.knockCorrectionDeg),
            telemetryLine(TelemetryChannel.LEARNED_IGNITION, "%.1f deg", telemetry.learnedIgnitionTimingDeg),
            telemetryLine(TelemetryChannel.INJECTOR_PULSE_WIDTH, "%.2f ms", telemetry.injectorPulseWidthMs),
            telemetryLine(TelemetryChannel.FUEL_PUMP_DUTY, "%.1f %%", telemetry.fuelPumpDutyPercent),
            telemetryLine(TelemetryChannel.ALTERNATOR_DUTY, "%.1f %%", telemetry.alternatorDutyPercent),
        ).joinToString("\n")
    }

    private fun telemetryLine(channel: TelemetryChannel, format: String, value: Number): String {
        val renderedValue = if (channelModes.modeFor(channel) == ChannelMode.OFF) {
            "OFF"
        } else {
            String.format(Locale.US, format, value)
        }
        return "${channel.rc3Field.padEnd(13)} ${channel.label.padEnd(16)} $renderedValue"
    }

    private fun ensureBlePermissions(action: () -> Unit) {
        val missingPermissions = blePermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            action()
            return
        }

        pendingBlePermissionAction = action
        requestPermissions(missingPermissions.toTypedArray(), BLE_PERMISSION_REQUEST)
        appendLog("Requesting BLE permissions: ${missingPermissions.joinToString()}")
    }

    private fun blePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun attemptAutoConnectLastDevice() {
        val address = preferences.getString(KEY_LAST_DEVICE_ADDRESS, null) ?: return
        val savedName = preferences.getString(KEY_LAST_DEVICE_NAME, "(last device)") ?: "(last device)"
        appendLog("Auto-selecting last Bluetooth device: $savedName $address")
        ensureBlePermissions {
            try {
                val bluetoothAdapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (bluetoothAdapter == null) {
                    appendLog("Bluetooth adapter is unavailable.")
                    return@ensureBlePermissions
                }
                val device = bluetoothAdapter.getRemoteDevice(address)
                val name = device.name ?: savedName
                val scanDevice = BleScanDevice(
                    device = device,
                    name = name,
                    address = address,
                    rssi = BleDeviceScanner.RSSI_UNKNOWN,
                    likelyElmAdapter = BleDeviceScanner.isLikelyElmAdapter(name),
                    bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                )
                scanDevices[address] = scanDevice
                renderScanDevices()
                connectBleDevice(scanDevice)
            } catch (error: Exception) {
                appendLog("Auto Bluetooth connection failed before connect: ${error.message}")
            }
        }
    }

    private fun saveLastBluetoothDevice(scanDevice: BleScanDevice) {
        preferences.edit()
            .putString(KEY_LAST_DEVICE_ADDRESS, scanDevice.address)
            .putString(KEY_LAST_DEVICE_NAME, scanDevice.name)
            .apply()
    }

    private fun showUserMessage(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun appendLog(message: String) {
        debugLog.append(message)
        mainHandler.post { refreshLog() }
    }

    private fun refreshLog() {
        if (::logView.isInitialized) {
            logView.text = debugLog.snapshot().joinToString(separator = "\n")
        }
    }

    private fun copyDebugLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("RaceChrono Bridge debug log", debugLog.snapshot().joinToString("\n")),
        )
        appendLog("Debug log copied to clipboard.")
    }

    private fun copyRaceChronoMapping() {
        val mapping = TelemetryChannel.entries.joinToString(separator = "\n") { channel ->
            "${channel.rc3Field}: ${channel.mappingLabel}"
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RaceChrono Bridge channel mapping", mapping))
        appendLog("RaceChrono channel mapping copied to clipboard.")
    }

    companion object {
        private const val BLE_PERMISSION_REQUEST = 1001
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private val COLOR_BACKGROUND = Color.rgb(7, 12, 17)
        private val COLOR_SURFACE = Color.rgb(18, 29, 38)
        private val COLOR_SURFACE_ALT = Color.rgb(31, 45, 56)
        private val COLOR_BUTTON = Color.rgb(36, 57, 72)
        private val COLOR_ACCENT = Color.rgb(109, 255, 191)
        private val COLOR_TEXT_PRIMARY = Color.rgb(237, 246, 244)
        private val COLOR_TEXT_SECONDARY = Color.rgb(156, 174, 181)
    }
}
