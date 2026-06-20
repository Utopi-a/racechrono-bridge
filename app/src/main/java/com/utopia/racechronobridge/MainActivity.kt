package com.utopia.racechronobridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannel
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannelParser
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
    private val customChannels = mutableListOf<CustomTelemetryChannel>()
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
    private lateinit var eventView: TextView
    private lateinit var tcpStatusView: TextView
    private lateinit var bleStatusView: TextView
    private lateinit var elmStatusView: TextView
    private lateinit var pollingStatusView: TextView
    private lateinit var channelSettingsLayout: LinearLayout
    private lateinit var customChannelInput: EditText
    private lateinit var customChannelsLayout: LinearLayout
    private lateinit var devicesLayout: LinearLayout
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadChannelModes()
        loadCustomChannels()

        tcpServer = RaceChronoTcpServer(
            onStatusChanged = { status ->
                mainHandler.post {
                    tcpStatusView.text = "${status.host}:${status.port}\n" +
                        "${if (status.running) "server running" else "server stopped"} / " +
                        if (status.clientConnected) "client connected" else "no client"
                }
            },
            onLog = ::appendLog,
        )

        setContentView(buildContentView())
        tcpStatusView.text = "${RaceChronoTcpServer.HOST}:${RaceChronoTcpServer.PORT}\nserver stopped / no client"
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
            showUserMessage("Bluetooth権限が拒否されました")
        }
    }

    @Deprecated("Used for simple platform document picker integration without extra dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CUSTOM_CHANNEL_FILE_REQUEST || resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data
        if (uri == null) {
            appendLog("Custom channel file picker returned no file.")
            return
        }

        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader().use { reader ->
                reader?.readText().orEmpty()
            }
        }.onSuccess { text ->
            if (text.isBlank()) {
                appendLog("Selected custom channel file was empty.")
                showUserMessage("Selected file was empty")
            } else {
                customChannelInput.setText(text)
                customChannelInput.setSelection(customChannelInput.text.length)
                appendLog("Loaded custom channel text from selected file.")
            }
        }.onFailure { error ->
            appendLog("Custom channel file read failed: ${error.message}")
            showUserMessage("Failed to read selected file")
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
            setBackgroundColor(COLOR_BACKGROUND)
        }

        root.addView(heroPanel())
        eventView = eventBanner("Ready. Start Bluetooth scan or wait for auto-connect.")
        root.addView(eventView)

        root.addView(sectionTitle("Status"))
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val tcpCard = statusCard(
                    "TCP",
                    "${RaceChronoTcpServer.HOST}:${RaceChronoTcpServer.PORT}\nserver stopped",
                )
                tcpStatusView = tcpCard.valueView
                addView(tcpCard.view, weightedCellParams(left = true))

                val bleCard = statusCard("Bluetooth", "disconnected")
                bleStatusView = bleCard.valueView
                addView(bleCard.view, weightedCellParams(left = false))
            },
        )
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val elmCard = statusCard("ELM327", "not initialized")
                elmStatusView = elmCard.valueView
                addView(elmCard.view, weightedCellParams(left = true))

                val pollingCard = statusCard("Polling", "stopped")
                pollingStatusView = pollingCard.valueView
                addView(pollingCard.view, weightedCellParams(left = false))
            },
        )

        root.addView(sectionTitle("Actions"))
        root.addView(
            actionGrid(
                listOf(
                    ActionSpec("Scan Bluetooth") { startBleScan() },
                    ActionSpec("Initialize ELM327") { initializeElm327() },
                    ActionSpec("Start SSM2") { startRealTelemetry() },
                    ActionSpec("Stop telemetry") { stopTelemetry() },
                    ActionSpec("Fake 5 Hz") { startFakeTelemetry() },
                    ActionSpec("Stop scan") { bleScanner?.stop() },
                    ActionSpec("Start TCP") { tcpServer.start() },
                    ActionSpec("Stop TCP") { tcpServer.stop() },
                    ActionSpec("Copy mapping") { copyRaceChronoMapping() },
                    ActionSpec("Copy log") { copyDebugLog() },
                ),
            ),
        )

        root.addView(
            infoStrip("RaceChrono DIY TCP/IP  ${RaceChronoTcpServer.HOST}:${RaceChronoTcpServer.PORT}   RC2/RC3 ON   NMEA OFF"),
        )

        telemetryView = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT_PRIMARY)
            setLineSpacing(0f, 1.08f)
            setPadding(18, 16, 18, 16)
        }
        root.addView(sectionTitle("Live telemetry"))
        root.addView(telemetryPanel())

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

        root.addView(sectionTitle("Custom SSM2 channels"))
        root.addView(
            TextView(this).apply {
                text = "Paste CSV or key=value text from an LLM. Custom rows replace their selected RC3 field."
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, 0, 0, 8)
            },
        )
        customChannelInput = EditText(this).apply {
            hint = CUSTOM_CHANNEL_SAMPLE
            minLines = 4
            maxLines = 8
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(COLOR_TEXT_PRIMARY)
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setPadding(18, 14, 18, 14)
            background = roundedRect(COLOR_SURFACE, radius = 16f)
        }
        root.addView(customChannelInput)
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    compactButton("Paste", active = false) { pasteCustomChannelConfig() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    compactButton("Open file", active = false) { openCustomChannelFile() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    compactButton("Import", active = true) { importCustomChannelsFromInput() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    compactButton("Clear", active = false) { clearCustomChannels() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
        )
        customChannelsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 0)
        }
        root.addView(customChannelsLayout)
        renderCustomChannels()

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

    private fun heroPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 18.dp(), 18.dp(), 18.dp())
            background = roundedRect(COLOR_SURFACE, radius = 18f.dpFloat(), strokeColor = COLOR_SURFACE_BORDER)
            addView(
                TextView(context).apply {
                    text = "RaceChrono Bridge"
                    textSize = 27f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT_PRIMARY)
                },
            )
            addView(
                TextView(context).apply {
                    text = "Subaru SSM2 over CAN -> RC3 TCP"
                    textSize = 14f
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setPadding(0, 4, 0, 0)
                },
            )
        }
    }

    private fun eventBanner(initialText: String): TextView {
        return TextView(this).apply {
            text = initialText
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = roundedRect(COLOR_EVENT_SURFACE, radius = 16f.dpFloat(), strokeColor = COLOR_ACCENT_DIM)
        }
    }

    private fun statusCard(label: String, initialValue: String): StatusCard {
        val valueView = TextView(this).apply {
            text = initialValue
            textSize = 13f
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, 5.dp(), 0, 0)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = roundedRect(COLOR_SURFACE, radius = 16f.dpFloat(), strokeColor = COLOR_SURFACE_BORDER)
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_ACCENT)
                },
            )
            addView(valueView)
        }
        return StatusCard(view = card, valueView = valueView)
    }

    private fun weightedCellParams(left: Boolean): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (left) {
                setMargins(0, 0, 6.dp(), 10.dp())
            } else {
                setMargins(6.dp(), 0, 0, 10.dp())
            }
        }
    }

    private fun actionGrid(actions: List<ActionSpec>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            actions.chunked(2).forEach { rowActions ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        rowActions.forEachIndexed { index, action ->
                            addView(
                                button(action.label, action.onClick),
                                weightedCellParams(left = index == 0),
                            )
                        }
                        if (rowActions.size == 1) {
                            addView(View(context), weightedCellParams(left = false))
                        }
                    },
                )
            }
        }
    }

    private fun infoStrip(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ACCENT)
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = roundedRect(COLOR_ACCENT_WASH, radius = 16f.dpFloat(), strokeColor = COLOR_ACCENT_DIM)
        }
    }

    private fun telemetryPanel(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(COLOR_TELEMETRY_SURFACE, radius = 16f.dpFloat(), strokeColor = COLOR_SURFACE_BORDER)
            addView(telemetryView)
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = 0
            minimumHeight = 0
            setPadding(10.dp(), 11.dp(), 10.dp(), 11.dp())
            background = roundedRect(COLOR_BUTTON, radius = 14f.dpFloat())
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
            setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
            setTextColor(if (active) Color.BLACK else COLOR_TEXT_SECONDARY)
            background = roundedRect(if (active) COLOR_ACCENT else COLOR_SURFACE_ALT, radius = 14f.dpFloat())
            setOnClickListener { onClick() }
        }
    }

    private fun sectionTitle(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, 22.dp(), 0, 8.dp())
        }
    }

    private fun roundedRect(
        color: Int,
        radius: Float,
        strokeColor: Int? = null,
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeColor != null) {
                setStroke(1, strokeColor)
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun Float.dpFloat(): Float = this * resources.displayMetrics.density

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
                        "scanning"
                    } else {
                        "scan stopped"
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
        renderTelemetry(lastTelemetry)
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

    private fun renderCustomChannels() {
        if (!::customChannelsLayout.isInitialized) {
            return
        }
        customChannelsLayout.removeAllViews()
        if (customChannels.isEmpty()) {
            customChannelsLayout.addView(
                TextView(this).apply {
                    text = "No custom channels imported."
                    textSize = 13f
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setPadding(0, 6, 0, 8)
                },
            )
            return
        }

        customChannels.forEach { channel ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 12, 18, 12)
                background = roundedRect(COLOR_SURFACE, radius = 16f)
            }
            row.addView(
                TextView(this).apply {
                    text = "${channel.rc3Field} -> ${channel.mappingLabel}"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT_PRIMARY)
                },
            )
            row.addView(
                TextView(this).apply {
                    text = "${channel.addressLabel} / ${channel.bytes} byte / value = raw * ${channel.scale} + ${channel.offset}"
                    textSize = 12f
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setPadding(0, 3, 0, 6)
                },
            )
            row.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    ChannelMode.entries.forEach { candidate ->
                        addView(
                            compactButton(candidate.label, active = channel.mode == candidate) {
                                setCustomChannelMode(channel.id, candidate)
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                    addView(
                        compactButton("Remove", active = false) { removeCustomChannel(channel.id) },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
            )
            customChannelsLayout.addView(
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

    private fun importCustomChannelsFromInput() {
        val text = customChannelInput.text?.toString().orEmpty()
        val result = CustomTelemetryChannelParser.parse(text)
        if (result.channels.isEmpty()) {
            appendLog("No custom channels imported.")
            result.errors.forEach { error -> appendLog("Custom channel import error: $error") }
            showUserMessage("No custom channels imported")
            return
        }

        val byField = customChannels.associateBy { it.rc3Field }.toMutableMap()
        result.channels.forEach { channel -> byField[channel.rc3Field] = channel }
        customChannels.clear()
        customChannels.addAll(TelemetryChannel.entries.mapNotNull { channel -> byField[channel.rc3Field] })
        saveCustomChannels()
        renderCustomChannels()
        renderTelemetry(lastTelemetry)
        result.errors.forEach { error -> appendLog("Custom channel import error: $error") }
        appendLog("Imported ${result.channels.size} custom SSM2 channel(s).")
        showUserMessage("Imported ${result.channels.size} custom channel(s)")
    }

    private fun pasteCustomChannelConfig() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            appendLog("Clipboard does not contain custom channel text.")
            return
        }
        customChannelInput.setText(text)
        customChannelInput.setSelection(customChannelInput.text.length)
        appendLog("Pasted custom channel text from clipboard.")
    }

    private fun openCustomChannelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/plain",
                    "text/csv",
                    "application/csv",
                    "application/vnd.ms-excel",
                ),
            )
        }
        runCatching {
            startActivityForResult(intent, CUSTOM_CHANNEL_FILE_REQUEST)
        }.onFailure { error ->
            appendLog("Custom channel file picker failed: ${error.message}")
            showUserMessage("Could not open file picker")
        }
    }

    private fun clearCustomChannels() {
        customChannels.clear()
        if (::customChannelInput.isInitialized) {
            customChannelInput.text?.clear()
        }
        saveCustomChannels()
        renderCustomChannels()
        renderTelemetry(lastTelemetry)
        appendLog("Custom SSM2 channels cleared.")
        showUserMessage("Custom channels cleared")
    }

    private fun setCustomChannelMode(channelId: String, mode: ChannelMode) {
        val index = customChannels.indexOfFirst { it.id == channelId }
        if (index < 0) {
            return
        }
        customChannels[index] = customChannels[index].copy(mode = mode)
        saveCustomChannels()
        renderCustomChannels()
        renderTelemetry(lastTelemetry)
        appendLog("${customChannels[index].label} mode changed to ${mode.label}.")
    }

    private fun removeCustomChannel(channelId: String) {
        val removed = customChannels.firstOrNull { it.id == channelId } ?: return
        customChannels.removeAll { it.id == channelId }
        saveCustomChannels()
        renderCustomChannels()
        renderTelemetry(lastTelemetry)
        appendLog("Removed custom channel: ${removed.label}.")
    }

    private fun loadCustomChannels() {
        val text = preferences.getString(KEY_CUSTOM_CHANNELS, null).orEmpty()
        if (text.isBlank()) {
            return
        }
        val result = CustomTelemetryChannelParser.parse(text)
        customChannels.clear()
        customChannels.addAll(result.channels)
    }

    private fun saveCustomChannels() {
        preferences.edit()
            .putString(KEY_CUSTOM_CHANNELS, customChannels.joinToString(separator = "\n") { it.toConfigLine() })
            .apply()
    }

    private fun connectBleDevice(scanDevice: BleScanDevice) {
        ensureBlePermissions {
            bleScanner?.stop()
            bleStatusView.text = "connecting\n${scanDevice.name}"
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
                            bleStatusView.text = "connected\n${scanDevice.name}"
                            showUserMessage("Bluetooth接続に成功しました: ${scanDevice.name}")
                            elmStatusView.text = "not initialized"
                            initializeElm327(startPollingOnSuccess = true)
                        }
                    } else {
                        mainHandler.post {
                            bleStatusView.text = "connection failed"
                            showUserMessage("Bluetooth接続に失敗しました")
                        }
                    }
                } catch (error: Exception) {
                    appendLog("Bluetooth connection failed: ${error.message}")
                    mainHandler.post {
                        bleStatusView.text = "connection failed"
                        showUserMessage("Bluetooth接続に失敗しました")
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

        elmStatusView.text = "initializing"
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
                    elmStatusView.text = "initialized for SSM2"
                    showUserMessage("ELM327初期化に成功しました")
                    if (startPollingOnSuccess) {
                        startRealTelemetry()
                    }
                }
            } catch (error: Exception) {
                appendLog("ELM327 initialization failed: ${error.message}")
                mainHandler.post {
                    elmStatusView.text = "initialization failed"
                    showUserMessage("ELM327初期化に失敗しました")
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
        pollingStatusView.text = "fake telemetry\n5 Hz"
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
        pollingStatusView.text = "SSM2 over CAN"
        showUserMessage("SSM2 pollingを開始しました")
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
            pollingStatusView.text = "stopped"
        }
        appendLog("Fake telemetry stopped.")
    }

    private fun stopRealTelemetry() {
        val task = realPollingTask ?: return
        task.cancel(true)
        realPollingTask = null
        if (::pollingStatusView.isInitialized) {
            pollingStatusView.text = "stopped"
        }
        appendLog("SSM2 polling stopped.")
    }

    private fun renderTelemetry(telemetry: SubaruTelemetry) {
        val customByField = customChannels.associateBy { it.rc3Field }
        telemetryView.text = TelemetryChannel.entries.joinToString("\n") { channel ->
            customByField[channel.rc3Field]?.let { customChannel ->
                customTelemetryLine(customChannel, telemetry)
            } ?: telemetryLine(channel, telemetry)
        }
    }

    private fun telemetryLine(channel: TelemetryChannel, telemetry: SubaruTelemetry): String {
        val formatAndValue = when (channel) {
            TelemetryChannel.RPM -> "%.0f rpm" to telemetry.rpm
            TelemetryChannel.GEAR -> "%d" to telemetry.gear
            TelemetryChannel.BOOST -> "%.1f kPa" to telemetry.boostKpa
            TelemetryChannel.COOLANT -> "%.1f C" to telemetry.coolantC
            TelemetryChannel.THROTTLE -> "%.1f %%" to telemetry.throttlePercent
            TelemetryChannel.ACCELERATOR -> "%.1f %%" to telemetry.acceleratorPercent
            TelemetryChannel.PRIMARY_WGDC -> "%.1f %%" to telemetry.primaryWastegateDutyPercent
            TelemetryChannel.VEHICLE_SPEED -> "%.1f km/h" to telemetry.vehicleSpeedKph
            TelemetryChannel.INTAKE_AIR_TEMP -> "%.1f C" to telemetry.intakeAirTempC
            TelemetryChannel.BATTERY_VOLTAGE -> "%.2f V" to telemetry.batteryVoltage
            TelemetryChannel.MASS_AIRFLOW -> "%.1f g/s" to telemetry.massAirflowGps
            TelemetryChannel.IGNITION_TIMING -> "%.1f deg" to telemetry.ignitionTimingDeg
            TelemetryChannel.KNOCK_CORRECTION -> "%.1f deg" to telemetry.knockCorrectionDeg
            TelemetryChannel.LEARNED_IGNITION -> "%.1f deg" to telemetry.learnedIgnitionTimingDeg
            TelemetryChannel.INJECTOR_PULSE_WIDTH -> "%.2f ms" to telemetry.injectorPulseWidthMs
            TelemetryChannel.FUEL_PUMP_DUTY -> "%.1f %%" to telemetry.fuelPumpDutyPercent
            TelemetryChannel.ALTERNATOR_DUTY -> "%.1f %%" to telemetry.alternatorDutyPercent
        }
        val renderedValue = if (channelModes.modeFor(channel) == ChannelMode.OFF) {
            "OFF"
        } else {
            String.format(Locale.US, formatAndValue.first, formatAndValue.second)
        }
        return "${channel.rc3Field.padEnd(13)} ${channel.label.padEnd(16)} $renderedValue"
    }

    private fun customTelemetryLine(channel: CustomTelemetryChannel, telemetry: SubaruTelemetry): String {
        val renderedValue = if (channel.mode == ChannelMode.OFF) {
            "OFF"
        } else {
            val value = telemetry.customValues[channel.id]
            if (value == null) "-" else String.format(Locale.US, "%.3f %s", value, channel.unit).trim()
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
        val update = {
            if (::eventView.isInitialized) {
                val error = message.contains("failed", ignoreCase = true) ||
                    message.contains("失敗") ||
                    message.contains("拒否")
                eventView.text = message
                eventView.background = roundedRect(
                    if (error) COLOR_ERROR_SURFACE else COLOR_EVENT_SURFACE,
                    radius = 16f.dpFloat(),
                    strokeColor = if (error) COLOR_ERROR_BORDER else COLOR_ACCENT_DIM,
                )
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            update()
        } else {
            mainHandler.post { update() }
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
        val mapping = raceChronoMappingLines().joinToString(separator = "\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RaceChrono Bridge channel mapping", mapping))
        appendLog("RaceChrono channel mapping copied to clipboard.")
    }

    private fun raceChronoMappingLines(): List<String> {
        val customByField = customChannels.associateBy { it.rc3Field }
        return TelemetryChannel.entries.map { channel ->
            val customChannel = customByField[channel.rc3Field]
            if (customChannel == null) {
                "${channel.rc3Field}: ${channel.mappingLabel}"
            } else {
                "${customChannel.rc3Field}: ${customChannel.mappingLabel} (custom ${customChannel.addressLabel})"
            }
        }
    }

    private data class ActionSpec(
        val label: String,
        val onClick: () -> Unit,
    )

    private data class StatusCard(
        val view: View,
        val valueView: TextView,
    )

    companion object {
        private const val BLE_PERMISSION_REQUEST = 1001
        private const val CUSTOM_CHANNEL_FILE_REQUEST = 1002
        private const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
        private const val KEY_CUSTOM_CHANNELS = "custom_channels"
        private const val CUSTOM_CHANNEL_SAMPLE =
            "slot,label,unit,address,bytes,scale,offset,mode,signed\n" +
                "Analog 15,Oil temp,C,0x000108,1,1,-40,Slow,false"
        private val COLOR_BACKGROUND = Color.rgb(7, 12, 17)
        private val COLOR_SURFACE = Color.rgb(18, 29, 38)
        private val COLOR_TELEMETRY_SURFACE = Color.rgb(10, 19, 24)
        private val COLOR_SURFACE_ALT = Color.rgb(31, 45, 56)
        private val COLOR_SURFACE_BORDER = Color.rgb(45, 68, 82)
        private val COLOR_BUTTON = Color.rgb(36, 57, 72)
        private val COLOR_ACCENT = Color.rgb(109, 255, 191)
        private val COLOR_ACCENT_DIM = Color.rgb(48, 122, 94)
        private val COLOR_ACCENT_WASH = Color.rgb(14, 45, 35)
        private val COLOR_EVENT_SURFACE = Color.rgb(15, 37, 35)
        private val COLOR_ERROR_SURFACE = Color.rgb(57, 22, 28)
        private val COLOR_ERROR_BORDER = Color.rgb(169, 61, 72)
        private val COLOR_TEXT_PRIMARY = Color.rgb(237, 246, 244)
        private val COLOR_TEXT_SECONDARY = Color.rgb(156, 174, 181)
    }
}
