package com.utopia.racechronobridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.utopia.racechronobridge.ble.BleDeviceScanner
import com.utopia.racechronobridge.ble.BleScanDevice
import com.utopia.racechronobridge.background.BridgeForegroundService
import com.utopia.racechronobridge.background.BridgeRuntime
import com.utopia.racechronobridge.background.BridgeRuntimeState
import com.utopia.racechronobridge.background.AcquisitionMode
import com.utopia.racechronobridge.background.BridgeRuntimeStore
import com.utopia.racechronobridge.background.StepState
import com.utopia.racechronobridge.background.StepStatus
import com.utopia.racechronobridge.diagnostics.AppExitDiagnostics
import com.utopia.racechronobridge.racechrono.RaceChronoTcpServer
import com.utopia.racechronobridge.racechrono.RaceChronoTcpStatus
import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannel
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannelParser
import com.utopia.racechronobridge.ssm2.StandardSsm2Preset
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import com.utopia.racechronobridge.ssm2.modeFor
import java.util.Locale

class MainActivity : Activity(), BridgeRuntime.Listener {
    private val channelModes = TelemetryChannel.defaultModes()
    private val customChannels = mutableListOf<CustomTelemetryChannel>()
    private val preferences: SharedPreferences by lazy {
        getSharedPreferences("racechrono_bridge", Context.MODE_PRIVATE)
    }

    private var pendingBlePermissionAction: (() -> Unit)? = null
    private var notificationPermissionRequested = false
    private var lastTelemetry: SubaruTelemetry = SubaruTelemetry.EMPTY
    private lateinit var runtime: BridgeRuntime
    private var currentRuntimeState = BridgeRuntimeState()

    private lateinit var adapterStep: StepRow
    private lateinit var ecuStep: StepRow
    private lateinit var raceChronoStep: StepRow
    private lateinit var connectionButton: TextView
    private lateinit var devicesLayout: LinearLayout
    private lateinit var telemetryGrid: LinearLayout
    private val telemetryTiles = mutableMapOf<String, TelemetryTile>()
    private lateinit var captureStep: StepRow
    private lateinit var captureButton: TextView
    private lateinit var exportButton: TextView
    private lateinit var markerButtons: List<TextView>
    private lateinit var channelSettingsLayout: LinearLayout
    private lateinit var customChannelInput: EditText
    private lateinit var customChannelsLayout: LinearLayout
    private lateinit var tcpToggleButton: TextView
    private lateinit var acquisitionLayout: LinearLayout
    private lateinit var logView: TextView
    private var detailsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppExitDiagnostics.install(this)
        val startupDiagnostics = AppExitDiagnostics.startupMessages(this)
        AppExitDiagnostics.recordLifecycle(this, "onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runtime = BridgeRuntimeStore.get(applicationContext)
        loadChannelModes()
        loadCustomChannels()
        runtime.setChannelModes(channelModes)
        runtime.setCustomChannels(customChannels)

        setContentView(buildContentView())
        runtime.addListener(this)
        startupDiagnostics.forEach(runtime::appendUserLog)
        ensureBridgePermissions {
            startBridgeForegroundService()
            runtime.attemptAutoConnectLastDevice()
        }
    }

    override fun onResume() {
        super.onResume()
        AppExitDiagnostics.recordLifecycle(this, "onResume")
    }

    override fun onPause() {
        AppExitDiagnostics.recordLifecycle(this, "onPause")
        super.onPause()
    }

    override fun onStop() {
        AppExitDiagnostics.recordLifecycle(this, "onStop")
        super.onStop()
    }

    override fun onDestroy() {
        AppExitDiagnostics.recordLifecycle(this, "onDestroy")
        runtime.removeListener(this)
        if (isFinishing) {
            BridgeForegroundService.stop(this)
        }
        super.onDestroy()
    }

    override fun onBridgeRuntimeStateChanged(state: BridgeRuntimeState) {
        val previous = currentRuntimeState
        currentRuntimeState = state
        if (!::adapterStep.isInitialized) {
            return
        }
        adapterStep.render(state.adapter)
        ecuStep.render(state.ecu)
        raceChronoStep.render(raceChronoStatus(state.tcpStatus))
        renderConnectionButton(state)
        if (previous.scanDevices != state.scanDevices || previous.adapter != state.adapter) {
            renderScanDevices(state)
        }
        lastTelemetry = state.telemetry
        updateTelemetryValues(state.telemetry)
        renderCapture(state)
        tcpToggleButton.text = if (state.tcpStatus.running) "TCP サーバーを止める" else "TCP サーバーを開始"
        if (previous.acquisitionMode != state.acquisitionMode || acquisitionLayout.childCount == 0) {
            renderAcquisitionMode(state.acquisitionMode)
        }
        if (detailsExpanded) {
            refreshLog()
        }
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
            showUserMessage("Bluetooth の権限がないため接続できません")
        }
    }

    @Deprecated("Used for simple platform document picker integration without extra dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAN_CAPTURE_EXPORT_REQUEST && resultCode == RESULT_OK) {
            writeCanCaptureExport(data?.data)
            return
        }
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
                showUserMessage("ファイルが空でした")
            } else {
                customChannelInput.setText(text)
                customChannelInput.setSelection(customChannelInput.text.length)
                appendLog("Loaded custom channel text from selected file.")
            }
        }.onFailure { error ->
            appendLog("Custom channel file read failed: ${error.message}")
            showUserMessage("ファイルを読み込めませんでした")
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 32.dp())
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                setPadding(0, 8.dp(), 0, 4.dp())
            },
        )

        root.addView(sectionLabel("接続"))
        root.addView(buildConnectionCard())
        connectionButton = primaryButton("") { onConnectionButtonTapped() }
        root.addView(connectionButton, fullWidthParams(top = 10))
        devicesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesLayout)
        root.addView(sectionHint("取得方法"), fullWidthParams(top = 14))
        acquisitionLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(acquisitionLayout, fullWidthParams(top = 6))
        root.addView(
            caption(
                "RaceChrono 側の設定: 他のデバイス → RaceChrono DIY → TCP/IP " +
                    "${RaceChronoTcpServer.HOST}:${RaceChronoTcpServer.PORT}。" +
                    "RC2/RC3 はオン、NMEA 0183 はオフ。",
            ),
            fullWidthParams(top = 8),
        )

        root.addView(sectionLabel("ライブ値"))
        telemetryGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(telemetryGrid)
        rebuildTelemetryTiles()

        root.addView(sectionLabel("CAN 記録"))
        root.addView(buildCaptureSection())

        root.addView(
            collapsibleSection(
                title = "送信チャンネル",
                summary = "RaceChrono に送る項目と更新頻度",
            ) {
                addView(caption("RaceChrono 側の欄名は固定です。ライブ値の右上にある A1・D1 などが、RaceChrono の Analog 1・Digital 1 に対応します。"))
                channelSettingsLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(channelSettingsLayout, fullWidthParams(top = 8))
                renderChannelSettings()
            },
        )
        root.addView(
            collapsibleSection(
                title = "カスタム SSM2 チャンネル",
                summary = "任意のアドレスを読んで、指定した欄に差し替える",
            ) { buildCustomChannelSection(this) },
        )
        root.addView(
            collapsibleSection(
                title = "詳細",
                summary = "手動操作とログ",
                onToggle = { expanded ->
                    detailsExpanded = expanded
                    if (expanded) refreshLog()
                },
            ) { buildDetailsSection(this) },
        )

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFillViewport = true
            clipToPadding = true
            addView(root)
            // Android 15 forces edge-to-edge; older versions already lay out below the system bars.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(0, bars.top, 0, bars.bottom)
                    insets
                }
            }
        }
    }

    private fun buildConnectionCard(): View {
        adapterStep = stepRow("アダプタ")
        ecuStep = stepRow("ECU")
        raceChronoStep = stepRow("RaceChrono")
        return card {
            addView(adapterStep.view)
            addView(divider())
            addView(ecuStep.view)
            addView(divider())
            addView(raceChronoStep.view)
        }
    }

    private fun buildCaptureSection(): View {
        captureStep = stepRow("状態")
        captureButton = primaryButton("記録を開始") {
            if (currentRuntimeState.capture.state == StepState.WORKING) {
                runtime.stopCanCapture()
            } else {
                ensureBridgePermissions {
                    startBridgeForegroundService()
                    runtime.startCanCapture()
                }
            }
        }
        exportButton = secondaryButton("CSV を書き出す") { exportCanCapture() }
        markerButtons = CAN_CAPTURE_MARKERS.map { marker ->
            secondaryButton(marker.label) {
                runtime.markCanCapture(marker.key)
                showUserMessage("目印「${marker.label}」を記録しました")
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption("車内を流れる CAN 信号をそのまま CSV に保存します。記録中は SSM2 の取得を止めます。操作を始めるときに目印を押してください。"))
            addView(card { addView(captureStep.view) }, fullWidthParams(top = 8))
            addView(
                row(
                    captureButton to 1f,
                    exportButton to 1f,
                ),
                fullWidthParams(top = 10),
            )
            addView(sectionHint("目印"), fullWidthParams(top = 14))
            markerButtons.chunked(4).forEach { rowButtons ->
                addView(row(*rowButtons.map { it to 1f }.toTypedArray()), fullWidthParams(top = 6))
            }
        }
    }

    private fun buildCustomChannelSection(container: LinearLayout) {
        container.addView(caption("CSV か key=value 形式の行を貼り付けて取り込むと、指定した欄の値を置き換えます。"))
        customChannelInput = EditText(this).apply {
            hint = CUSTOM_CHANNEL_SAMPLE
            minLines = 3
            maxLines = 8
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_TEXT_MUTED)
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = rounded(COLOR_SURFACE)
        }
        container.addView(customChannelInput, fullWidthParams(top = 8))
        container.addView(
            row(
                secondaryButton("貼り付け") { pasteCustomChannelConfig() } to 1f,
                secondaryButton("ファイル") { openCustomChannelFile() } to 1f,
                primaryButton("取り込む") { importCustomChannelsFromInput() } to 1f,
            ),
            fullWidthParams(top = 8),
        )
        container.addView(
            row(
                secondaryButton("チューニング用") { loadPreset(StandardSsm2Preset.tuningPresetCsv) } to 1f,
                secondaryButton("CAN 信号") { loadPreset(StandardSsm2Preset.canSignalPresetCsv) } to 1f,
                secondaryButton("すべて削除") { clearCustomChannels() } to 1f,
            ),
            fullWidthParams(top = 8),
        )
        customChannelsLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(customChannelsLayout, fullWidthParams(top = 10))
        renderCustomChannels()
    }

    private fun buildDetailsSection(container: LinearLayout) {
        tcpToggleButton = secondaryButton("TCP サーバーを止める") {
            if (currentRuntimeState.tcpStatus.running) {
                runtime.stopTcpServer()
            } else {
                ensureBridgePermissions {
                    startBridgeForegroundService()
                    runtime.startTcpServer()
                }
            }
        }
        container.addView(
            row(
                secondaryButton("ELM327 を初期化") {
                    ensureBridgePermissions {
                        startBridgeForegroundService()
                        runtime.initializeElm327()
                    }
                } to 1f,
                secondaryButton("テストデータを送る") {
                    ensureBridgePermissions {
                        startBridgeForegroundService()
                        runtime.startFakeTelemetry()
                    }
                } to 1f,
            ),
        )
        container.addView(
            row(
                tcpToggleButton to 1f,
                secondaryButton("送信を止める") { runtime.stopTelemetry() } to 1f,
            ),
            fullWidthParams(top = 8),
        )
        container.addView(
            row(
                secondaryButton("欄の対応をコピー") { copyRaceChronoMapping() } to 1f,
                secondaryButton("ログをコピー") { copyDebugLog() } to 1f,
            ),
            fullWidthParams(top = 8),
        )
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT_MUTED)
            setTextIsSelectable(true)
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            background = rounded(COLOR_SURFACE)
        }
        container.addView(logView, fullWidthParams(top = 10))
    }

    private fun onConnectionButtonTapped() {
        val state = currentRuntimeState
        when {
            state.scanning -> runtime.stopBleScan()
            state.adapter.state != StepState.OK -> ensureBridgePermissions {
                startBridgeForegroundService()
                runtime.startBleScan()
            }
            state.ecu.state == StepState.OK -> runtime.stopTelemetry()
            else -> ensureBridgePermissions {
                startBridgeForegroundService()
                runtime.initializeElm327(startPollingOnSuccess = true)
            }
        }
    }

    private fun renderConnectionButton(state: BridgeRuntimeState) {
        connectionButton.text = when {
            state.scanning -> "探すのをやめる"
            state.adapter.state == StepState.WORKING -> "接続中…"
            state.adapter.state != StepState.OK -> "アダプタを探す"
            state.capture.state == StepState.WORKING -> "CAN 記録中"
            state.ecu.state == StepState.OK -> "取得を止める"
            state.ecu.state == StepState.WORKING -> "初期化中…"
            else -> "取得を開始"
        }
        val enabled = state.scanning || (
            state.adapter.state != StepState.WORKING &&
                state.ecu.state != StepState.WORKING &&
                state.capture.state != StepState.WORKING
            )
        connectionButton.isEnabled = enabled
        connectionButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun renderAcquisitionMode(selected: AcquisitionMode) {
        acquisitionLayout.removeAllViews()
        acquisitionLayout.addView(
            segmentedControl(
                options = ACQUISITION_OPTIONS.map { (mode, label) -> label to (mode == selected) },
            ) { index -> runtime.setAcquisitionMode(ACQUISITION_OPTIONS[index].first) },
        )
        acquisitionLayout.addView(
            caption(
                when (selected) {
                    AcquisitionMode.CAN ->
                        "回転数・車速・ギア・アクセル・水温は車内の CAN 信号から 20 回/秒で取得します。" +
                            "ブーストなど残りの項目は、1.5 秒ごとに SSM2 で読みます。"
                    AcquisitionMode.SSM2 -> "すべての項目を SSM2 で 1 つずつ問い合わせます（1 秒に数回）。"
                },
            ),
            fullWidthParams(top = 6),
        )
    }

    private fun raceChronoStatus(status: RaceChronoTcpStatus): StepStatus = when {
        !status.running -> StepStatus(StepState.FAILED, "TCP サーバー停止中")
        status.clientConnected -> StepStatus(StepState.OK, "接続済み")
        else -> StepStatus(StepState.WORKING, "RaceChrono からの接続待ち")
    }

    private fun renderScanDevices(state: BridgeRuntimeState) {
        devicesLayout.removeAllViews()
        if (state.adapter.state == StepState.OK || state.scanDevices.isEmpty()) {
            return
        }
        devicesLayout.addView(sectionHint("見つかったデバイス（タップで接続）"), fullWidthParams(top = 14))
        devicesLayout.addView(
            card {
                state.scanDevices.forEachIndexed { index, device ->
                    if (index > 0) addView(divider())
                    addView(deviceRow(device))
                }
            },
            fullWidthParams(top = 6),
        )
    }

    private fun deviceRow(device: BleScanDevice): View {
        val details = buildList {
            if (device.bonded) add("ペアリング済み")
            if (device.likelyElmAdapter) add("OBD アダプタ")
            if (device.rssi != BleDeviceScanner.RSSI_UNKNOWN) add("${device.rssi} dBm")
            add(device.address)
        }.joinToString(" · ")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = pressable(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener {
                ensureBridgePermissions {
                    startBridgeForegroundService()
                    runtime.connectBleDevice(device)
                }
            }
            addView(
                TextView(context).apply {
                    text = device.name
                    textSize = 15f
                    setTextColor(COLOR_TEXT)
                },
            )
            addView(
                TextView(context).apply {
                    text = details
                    textSize = 12f
                    setTextColor(COLOR_TEXT_MUTED)
                    setPadding(0, 2.dp(), 0, 0)
                },
            )
        }
    }

    private fun renderCapture(state: BridgeRuntimeState) {
        captureStep.render(state.capture)
        val recording = state.capture.state == StepState.WORKING
        captureButton.text = if (recording) "記録を止める" else "記録を開始"
        val canStart = recording || state.adapter.state == StepState.OK
        captureButton.isEnabled = canStart
        captureButton.alpha = if (canStart) 1f else 0.5f
        val canExport = !recording && state.canCaptureFile?.isFile == true
        exportButton.isEnabled = canExport
        exportButton.alpha = if (canExport) 1f else 0.5f
        markerButtons.forEach { button ->
            button.isEnabled = recording
            button.alpha = if (recording) 1f else 0.4f
        }
    }

    private fun rebuildTelemetryTiles() {
        telemetryGrid.removeAllViews()
        telemetryTiles.clear()
        val customByField = customChannels.associateBy { it.rc3Field }
        val tiles = TelemetryChannel.entries.map { channel ->
            val custom = customByField[channel.rc3Field]
            val tile = telemetryTile(
                label = custom?.label ?: displayLabel(channel),
                field = shortField(channel.rc3Field),
            )
            telemetryTiles[channel.rc3Field] = tile
            tile.view
        }
        tiles.chunked(TELEMETRY_COLUMNS).forEachIndexed { index, rowTiles ->
            val rowViews = rowTiles.map { it to 1f }.toMutableList()
            repeat(TELEMETRY_COLUMNS - rowTiles.size) { rowViews += View(this) to 1f }
            telemetryGrid.addView(row(*rowViews.toTypedArray()), fullWidthParams(top = if (index == 0) 0 else 8))
        }
        updateTelemetryValues(lastTelemetry)
    }

    private fun updateTelemetryValues(telemetry: SubaruTelemetry) {
        val customByField = customChannels.associateBy { it.rc3Field }
        TelemetryChannel.entries.forEach { channel ->
            val tile = telemetryTiles[channel.rc3Field] ?: return@forEach
            val custom = customByField[channel.rc3Field]
            val (value, unit) = if (custom != null) {
                customValue(custom, telemetry)
            } else {
                builtInValue(channel, telemetry)
            }
            tile.value.text = value
            tile.unit.text = displayUnit(unit)
            tile.view.alpha = if (value == OFF_LABEL) 0.45f else 1f
        }
    }

    private fun builtInValue(channel: TelemetryChannel, telemetry: SubaruTelemetry): Pair<String, String> {
        if (channelModes.modeFor(channel) == ChannelMode.OFF) {
            return OFF_LABEL to ""
        }
        if (telemetry.timestampMillis == 0L) {
            return NO_VALUE to channel.unit
        }
        val value = when (channel) {
            TelemetryChannel.RPM -> "%.0f".format(Locale.US, telemetry.rpm)
            TelemetryChannel.GEAR -> telemetry.gear.takeIf { it > 0 }?.toString() ?: NO_VALUE
            TelemetryChannel.BOOST -> "%.1f".format(Locale.US, telemetry.boostKpa)
            TelemetryChannel.COOLANT -> "%.0f".format(Locale.US, telemetry.coolantC)
            TelemetryChannel.THROTTLE -> "%.1f".format(Locale.US, telemetry.throttlePercent)
            TelemetryChannel.ACCELERATOR -> "%.1f".format(Locale.US, telemetry.acceleratorPercent)
            TelemetryChannel.PRIMARY_WGDC -> "%.1f".format(Locale.US, telemetry.primaryWastegateDutyPercent)
            TelemetryChannel.VEHICLE_SPEED -> "%.0f".format(Locale.US, telemetry.vehicleSpeedKph)
            TelemetryChannel.INTAKE_AIR_TEMP -> "%.0f".format(Locale.US, telemetry.intakeAirTempC)
            TelemetryChannel.BATTERY_VOLTAGE -> "%.2f".format(Locale.US, telemetry.batteryVoltage)
            TelemetryChannel.MASS_AIRFLOW -> "%.1f".format(Locale.US, telemetry.massAirflowGps)
            TelemetryChannel.IGNITION_TIMING -> "%.1f".format(Locale.US, telemetry.ignitionTimingDeg)
            TelemetryChannel.KNOCK_CORRECTION -> "%.1f".format(Locale.US, telemetry.knockCorrectionDeg)
            TelemetryChannel.LEARNED_IGNITION -> "%.1f".format(Locale.US, telemetry.learnedIgnitionTimingDeg)
            TelemetryChannel.INJECTOR_PULSE_WIDTH -> "%.2f".format(Locale.US, telemetry.injectorPulseWidthMs)
            TelemetryChannel.FUEL_PUMP_DUTY -> "%.1f".format(Locale.US, telemetry.fuelPumpDutyPercent)
            TelemetryChannel.ALTERNATOR_DUTY -> "%.1f".format(Locale.US, telemetry.alternatorDutyPercent)
        }
        return value to channel.unit
    }

    private fun customValue(channel: CustomTelemetryChannel, telemetry: SubaruTelemetry): Pair<String, String> {
        if (channel.mode == ChannelMode.OFF) {
            return OFF_LABEL to ""
        }
        val value = telemetry.customValues[channel.id] ?: return NO_VALUE to channel.unit
        // Switch-like CAN signals are 0/1, so drop the decimals for whole numbers.
        val text = if (value % 1.0 == 0.0) "%.0f".format(Locale.US, value) else "%.2f".format(Locale.US, value)
        return text to channel.unit
    }

    private fun renderChannelSettings() {
        if (!::channelSettingsLayout.isInitialized) {
            return
        }
        channelSettingsLayout.removeAllViews()
        channelSettingsLayout.addView(
            card {
                TelemetryChannel.entries.forEachIndexed { index, channel ->
                    if (index > 0) addView(divider())
                    addView(
                        settingRow(
                            title = displayLabel(channel),
                            subtitle = "${channel.rc3Field} · ${displayUnit(channel.unit).ifBlank { "単位なし" }}",
                            mode = channelModes.modeFor(channel),
                        ) { mode -> setChannelMode(channel, mode) },
                    )
                }
            },
        )
    }

    private fun renderCustomChannels() {
        if (!::customChannelsLayout.isInitialized) {
            return
        }
        customChannelsLayout.removeAllViews()
        if (customChannels.isEmpty()) {
            customChannelsLayout.addView(caption("取り込んだチャンネルはまだありません。"))
            return
        }
        customChannelsLayout.addView(
            card {
                customChannels.forEachIndexed { index, channel ->
                    if (index > 0) addView(divider())
                    addView(
                        settingRow(
                            title = channel.mappingLabel,
                            subtitle = "${channel.rc3Field} · ${channel.addressLabel} · raw × ${channel.scale} + ${channel.offset}",
                            mode = channel.mode,
                            onRemove = { removeCustomChannel(channel.id) },
                        ) { mode -> setCustomChannelMode(channel.id, mode) },
                    )
                }
            },
        )
    }

    private fun settingRow(
        title: String,
        subtitle: String,
        mode: ChannelMode,
        onRemove: (() -> Unit)? = null,
        onModeSelected: (ChannelMode) -> Unit,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 8.dp(), 10.dp(), 8.dp())
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = title
                            textSize = 14f
                            setTextColor(COLOR_TEXT)
                            maxLines = 1
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = subtitle
                            textSize = 11f
                            setTextColor(COLOR_TEXT_MUTED)
                        },
                    )
                    if (onRemove != null) {
                        addView(textAction("削除", onRemove))
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8.dp()
                },
            )
            addView(
                modeSelector(mode, onModeSelected),
                LinearLayout.LayoutParams(MODE_SELECTOR_WIDTH_DP.dp(), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun modeSelector(
        selectedMode: ChannelMode,
        onModeSelected: (ChannelMode) -> Unit,
    ): View = segmentedControl(
        options = MODE_SELECTOR_ORDER.map { mode -> modeLabel(mode) to (mode == selectedMode) },
        textSize = 12f,
    ) { index -> onModeSelected(MODE_SELECTOR_ORDER[index]) }

    private fun segmentedControl(
        options: List<Pair<String, Boolean>>,
        textSize: Float = 13f,
        onSelected: (Int) -> Unit,
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(COLOR_SURFACE_RAISED)
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            options.forEachIndexed { index, (label, active) ->
                addView(
                    TextView(context).apply {
                        text = label
                        this.textSize = textSize
                        gravity = Gravity.CENTER
                        setPadding(0, 7.dp(), 0, 7.dp())
                        setTextColor(if (active) Color.WHITE else COLOR_TEXT_MUTED)
                        background = if (active) rounded(COLOR_ACCENT, radius = 8f) else pressable(Color.TRANSPARENT)
                        isClickable = true
                        setOnClickListener { if (!active) onSelected(index) }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }
    }

    private fun setChannelMode(channel: TelemetryChannel, mode: ChannelMode) {
        channelModes[channel] = mode
        preferences.edit().putString(channel.preferenceKey, mode.name).apply()
        runtime.setChannelModes(channelModes)
        renderChannelSettings()
        updateTelemetryValues(lastTelemetry)
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

    private fun importCustomChannelsFromInput() {
        val text = customChannelInput.text?.toString().orEmpty()
        val result = CustomTelemetryChannelParser.parse(text)
        if (result.channels.isEmpty()) {
            appendLog("No custom channels imported.")
            result.errors.forEach { error -> appendLog("Custom channel import error: $error") }
            showUserMessage("取り込めるチャンネルがありませんでした（詳細はログ）")
            return
        }

        val byField = customChannels.associateBy { it.rc3Field }.toMutableMap()
        result.channels.forEach { channel -> byField[channel.rc3Field] = channel }
        customChannels.clear()
        customChannels.addAll(TelemetryChannel.entries.mapNotNull { channel -> byField[channel.rc3Field] })
        saveCustomChannels()
        onCustomChannelsChanged()
        result.errors.forEach { error -> appendLog("Custom channel import error: $error") }
        appendLog("Imported ${result.channels.size} custom SSM2 channel(s).")
        showUserMessage("${result.channels.size} 件のチャンネルを取り込みました")
    }

    private fun pasteCustomChannelConfig() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            showUserMessage("クリップボードが空です")
            return
        }
        customChannelInput.setText(text)
        customChannelInput.setSelection(customChannelInput.text.length)
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
            showUserMessage("ファイル選択を開けませんでした")
        }
    }

    private fun exportCanCapture() {
        val file = currentRuntimeState.canCaptureFile
        if (file == null || !file.isFile) {
            showUserMessage("まだ記録がありません")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        runCatching {
            startActivityForResult(intent, CAN_CAPTURE_EXPORT_REQUEST)
        }.onFailure { error ->
            appendLog("CAN capture export picker failed: ${error.message}")
            showUserMessage("保存先の選択を開けませんでした")
        }
    }

    private fun writeCanCaptureExport(uri: Uri?) {
        val file = currentRuntimeState.canCaptureFile
        if (uri == null || file == null) {
            appendLog("CAN capture export returned no destination.")
            return
        }
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("destination stream unavailable")
        }.onSuccess {
            appendLog("CAN capture exported: ${file.name}")
            showUserMessage("${file.name} を保存しました")
        }.onFailure { error ->
            appendLog("CAN capture export failed: ${error.message}")
            showUserMessage("CSV の保存に失敗しました")
        }
    }

    private fun loadPreset(csv: String) {
        customChannelInput.setText(csv)
        customChannelInput.setSelection(customChannelInput.text.length)
        importCustomChannelsFromInput()
    }

    private fun clearCustomChannels() {
        customChannels.clear()
        if (::customChannelInput.isInitialized) {
            customChannelInput.text?.clear()
        }
        saveCustomChannels()
        onCustomChannelsChanged()
        appendLog("Custom SSM2 channels cleared.")
        showUserMessage("カスタムチャンネルを削除しました")
    }

    private fun setCustomChannelMode(channelId: String, mode: ChannelMode) {
        val index = customChannels.indexOfFirst { it.id == channelId }
        if (index < 0) {
            return
        }
        customChannels[index] = customChannels[index].copy(mode = mode)
        saveCustomChannels()
        onCustomChannelsChanged()
        appendLog("${customChannels[index].label} mode changed to ${mode.label}.")
    }

    private fun removeCustomChannel(channelId: String) {
        val removed = customChannels.firstOrNull { it.id == channelId } ?: return
        customChannels.removeAll { it.id == channelId }
        saveCustomChannels()
        onCustomChannelsChanged()
        appendLog("Removed custom channel: ${removed.label}.")
    }

    private fun onCustomChannelsChanged() {
        renderCustomChannels()
        rebuildTelemetryTiles()
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
        runtime.setCustomChannels(customChannels)
    }

    private fun startBridgeForegroundService() {
        requestNotificationPermissionIfNeeded()
        runCatching {
            BridgeForegroundService.start(this)
        }.onFailure { error ->
            appendLog("Foreground keep-alive service failed: ${error.message}")
            showUserMessage("バックグラウンドで動かし続ける準備に失敗しました")
        }
    }

    private fun ensureBridgePermissions(action: () -> Unit) {
        val missingPermissions = bridgePermissions().filter { permission ->
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            action()
            return
        }

        pendingBlePermissionAction = action
        requestPermissions(missingPermissions.toTypedArray(), BLE_PERMISSION_REQUEST)
        appendLog("Requesting bridge permissions: ${missingPermissions.joinToString()}")
    }

    private fun bridgePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (notificationPermissionRequested) {
            return
        }
        notificationPermissionRequested = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }

    private fun showUserMessage(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun appendLog(message: String) {
        if (::runtime.isInitialized) {
            runtime.appendUserLog(message)
        }
    }

    private fun refreshLog() {
        if (::logView.isInitialized) {
            logView.text = currentRuntimeState.logs.takeLast(LOG_LINES_SHOWN).joinToString(separator = "\n")
        }
    }

    private fun copyDebugLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("RaceChrono Bridge debug log", currentRuntimeState.logs.joinToString("\n")),
        )
        showUserMessage("ログをコピーしました")
    }

    private fun copyRaceChronoMapping() {
        val mapping = raceChronoMappingLines().joinToString(separator = "\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RaceChrono Bridge channel mapping", mapping))
        showUserMessage("欄の対応をコピーしました")
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

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_MUTED)
        setPadding(2.dp(), 24.dp(), 0, 8.dp())
    }

    private fun sectionHint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_TEXT_MUTED)
        setPadding(2.dp(), 0, 0, 0)
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_TEXT_MUTED)
        setLineSpacing(0f, 1.15f)
        setPadding(2.dp(), 0, 2.dp(), 0)
    }

    private fun card(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_SURFACE)
        clipToOutline = true
        build()
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(COLOR_DIVIDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun stepRow(title: String): StepRow {
        val dot = View(this)
        val detail = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            gravity = Gravity.END
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 13.dp(), 14.dp(), 13.dp())
            addView(dot, LinearLayout.LayoutParams(8.dp(), 8.dp()).apply { marginEnd = 12.dp() })
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 15f
                    setTextColor(COLOR_TEXT)
                },
            )
            addView(
                detail,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp()
                },
            )
        }
        return StepRow(view = view, dot = dot, detail = detail)
    }

    private fun StepRow.render(status: StepStatus) {
        val color = when (status.state) {
            StepState.IDLE -> COLOR_STATE_IDLE
            StepState.WORKING -> COLOR_STATE_WORKING
            StepState.OK -> COLOR_STATE_OK
            StepState.FAILED -> COLOR_STATE_FAILED
        }
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        detail.text = status.detail
        detail.setTextColor(if (status.state == StepState.FAILED) COLOR_STATE_FAILED else COLOR_TEXT_MUTED)
    }

    private fun telemetryTile(label: String, field: String): TelemetryTile {
        val value = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setTextColor(COLOR_TEXT)
            maxLines = 1
        }
        val unit = TextView(this).apply {
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(3.dp(), 0, 0, 0)
            maxLines = 1
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            background = rounded(COLOR_SURFACE)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        TextView(context).apply {
                            text = label
                            textSize = 12f
                            setTextColor(COLOR_TEXT_MUTED)
                            maxLines = 1
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        TextView(context).apply {
                            text = field
                            textSize = 11f
                            setTextColor(COLOR_TEXT_FAINT)
                        },
                    )
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    setPadding(0, 2.dp(), 0, 0)
                    addView(value)
                    addView(
                        unit,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = 3.dp() },
                    )
                },
            )
        }
        return TelemetryTile(view = view, value = value, unit = unit)
    }

    private fun collapsibleSection(
        title: String,
        summary: String,
        onToggle: (Boolean) -> Unit = {},
        build: LinearLayout.() -> Unit,
    ): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 4.dp(), 0, 0)
            build()
        }
        val toggleLabel = TextView(this).apply {
            text = "開く"
            textSize = 13f
            setTextColor(COLOR_ACCENT_TEXT)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            background = pressable(COLOR_SURFACE)
            isClickable = true
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = title
                            textSize = 15f
                            setTextColor(COLOR_TEXT)
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = summary
                            textSize = 12f
                            setTextColor(COLOR_TEXT_MUTED)
                        },
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(toggleLabel)
            setOnClickListener {
                val expand = body.visibility != View.VISIBLE
                body.visibility = if (expand) View.VISIBLE else View.GONE
                toggleLabel.text = if (expand) "閉じる" else "開く"
                onToggle(expand)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12.dp(), 0, 0)
            addView(header)
            addView(body, fullWidthParams(top = 8))
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        actionButton(label, background = COLOR_ACCENT, textColor = Color.WHITE, onClick = onClick)

    private fun secondaryButton(label: String, onClick: () -> Unit): TextView =
        actionButton(label, background = COLOR_SURFACE_RAISED, textColor = COLOR_TEXT, onClick = onClick)

    private fun actionButton(label: String, background: Int, textColor: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = 46.dp()
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            setTextColor(textColor)
            this.background = pressable(background)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun textAction(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(COLOR_STATE_FAILED)
        setPadding(0, 6.dp(), 12.dp(), 2.dp())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun row(vararg children: Pair<View, Float>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEachIndexed { index, (child, weight) ->
            addView(
                child,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                    if (index > 0) marginStart = 8.dp()
                },
            )
        }
    }

    private fun fullWidthParams(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top.dp() }

    private fun rounded(color: Int, radius: Float = 10f): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
    }

    private fun pressable(color: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(COLOR_RIPPLE), rounded(color), rounded(Color.WHITE))

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private class StepRow(
        val view: View,
        val dot: View,
        val detail: TextView,
    )

    private class TelemetryTile(
        val view: View,
        val value: TextView,
        val unit: TextView,
    )

    private class CaptureMarker(
        val label: String,
        val key: String,
    )

    companion object {
        private const val BLE_PERMISSION_REQUEST = 1001
        private const val CUSTOM_CHANNEL_FILE_REQUEST = 1002
        private const val NOTIFICATION_PERMISSION_REQUEST = 1003
        private const val CAN_CAPTURE_EXPORT_REQUEST = 1004
        private const val KEY_CUSTOM_CHANNELS = "custom_channels"
        private const val LOG_LINES_SHOWN = 80
        private const val OFF_LABEL = "オフ"
        private const val NO_VALUE = "—"
        private const val CUSTOM_CHANNEL_SAMPLE =
            "slot,label,unit,address,bytes,scale,offset,mode,signed\n" +
                "Analog 15,Oil temp,C,0x000108,1,1,-40,Slow,false"

        // Keys stay English so capture files can be analyzed regardless of the UI language.
        private val CAN_CAPTURE_MARKERS = listOf(
            CaptureMarker("アイドル", "idle"),
            CaptureMarker("アクセル", "throttle"),
            CaptureMarker("ブレーキ", "brake"),
            CaptureMarker("クラッチ", "clutch"),
            CaptureMarker("ギア", "gear"),
            CaptureMarker("灯火類", "lights"),
            CaptureMarker("走行", "drive"),
            CaptureMarker("その他", "mark"),
        )
        private val MODE_SELECTOR_ORDER = listOf(ChannelMode.OFF, ChannelMode.SLOW, ChannelMode.FAST)
        private val ACQUISITION_OPTIONS = listOf(
            AcquisitionMode.CAN to "CAN 受信 + SSM2",
            AcquisitionMode.SSM2 to "SSM2 のみ",
        )

        private fun modeLabel(mode: ChannelMode): String = when (mode) {
            ChannelMode.OFF -> "送らない"
            ChannelMode.SLOW -> "ゆっくり"
            ChannelMode.FAST -> "毎回"
        }

        private const val TELEMETRY_COLUMNS = 3
        private const val MODE_SELECTOR_WIDTH_DP = 196

        private fun displayLabel(channel: TelemetryChannel): String = when (channel) {
            TelemetryChannel.RPM -> "回転数"
            TelemetryChannel.GEAR -> "ギア"
            TelemetryChannel.BOOST -> "ブースト"
            TelemetryChannel.COOLANT -> "水温"
            TelemetryChannel.THROTTLE -> "スロットル"
            TelemetryChannel.ACCELERATOR -> "アクセル"
            TelemetryChannel.PRIMARY_WGDC -> "WGDC"
            TelemetryChannel.VEHICLE_SPEED -> "車速"
            TelemetryChannel.INTAKE_AIR_TEMP -> "吸気温"
            TelemetryChannel.BATTERY_VOLTAGE -> "電圧"
            TelemetryChannel.MASS_AIRFLOW -> "吸入空気量"
            TelemetryChannel.IGNITION_TIMING -> "点火時期"
            TelemetryChannel.KNOCK_CORRECTION -> "ノック補正"
            TelemetryChannel.LEARNED_IGNITION -> "学習点火"
            TelemetryChannel.INJECTOR_PULSE_WIDTH -> "噴射時間"
            TelemetryChannel.FUEL_PUMP_DUTY -> "燃料ポンプ"
            TelemetryChannel.ALTERNATOR_DUTY -> "オルタネータ"
        }

        private fun displayUnit(unit: String): String = when (unit) {
            "C" -> "℃"
            "deg" -> "°"
            else -> unit
        }

        private fun shortField(rc3Field: String): String {
            val number = rc3Field.substringAfter(' ').substringBefore('/')
            return if (rc3Field.startsWith("Digital")) "D$number" else "A$number"
        }

        private val COLOR_BACKGROUND = Color.rgb(17, 19, 21)
        private val COLOR_SURFACE = Color.rgb(27, 30, 33)
        private val COLOR_SURFACE_RAISED = Color.rgb(40, 44, 48)
        private val COLOR_DIVIDER = Color.rgb(44, 49, 54)
        private val COLOR_TEXT = Color.rgb(232, 234, 237)
        private val COLOR_TEXT_MUTED = Color.rgb(150, 158, 166)
        private val COLOR_TEXT_FAINT = Color.rgb(100, 108, 116)
        private val COLOR_ACCENT = Color.rgb(44, 104, 222)
        private val COLOR_ACCENT_TEXT = Color.rgb(110, 160, 245)
        private val COLOR_RIPPLE = Color.argb(48, 255, 255, 255)
        private val COLOR_STATE_IDLE = Color.rgb(100, 108, 116)
        private val COLOR_STATE_WORKING = Color.rgb(214, 160, 46)
        private val COLOR_STATE_OK = Color.rgb(63, 185, 80)
        private val COLOR_STATE_FAILED = Color.rgb(240, 98, 88)
    }
}
