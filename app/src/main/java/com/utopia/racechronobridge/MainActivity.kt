package com.utopia.racechronobridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.utopia.racechronobridge.logging.DebugLog
import com.utopia.racechronobridge.racechrono.RaceChronoTcpServer
import com.utopia.racechronobridge.racechrono.Rc3Sentence
import com.utopia.racechronobridge.ssm2.FakeSubaruTelemetrySource
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
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

    private var fakePollingTask: ScheduledFuture<*>? = null
    private var rc3Count = 0

    private lateinit var tcpServer: RaceChronoTcpServer
    private lateinit var telemetryView: TextView
    private lateinit var tcpStatusView: TextView
    private lateinit var pollingStatusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        renderTelemetry(SubaruTelemetry.EMPTY)
        refreshLog()
        appendLog("App ready. Start TCP server, then fake telemetry.")
    }

    override fun onDestroy() {
        stopFakeTelemetry()
        tcpServer.stop()
        pollExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "RaceChrono Bridge"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        tcpStatusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        root.addView(tcpStatusView)

        pollingStatusView = TextView(this).apply {
            text = "Polling: stopped"
            textSize = 14f
            setPadding(0, 8, 0, 0)
        }
        root.addView(pollingStatusView)

        telemetryView = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.MONOSPACE
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
        controls.addView(button("Stop fake telemetry") { stopFakeTelemetry() })
        controls.addView(button("Copy debug log") { copyDebugLog() })

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 24, 0, 0)
        }
        root.addView(logView)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
    }

    private fun startFakeTelemetry() {
        if (fakePollingTask != null) {
            appendLog("Fake telemetry is already running.")
            return
        }

        appendLog("Starting fake telemetry at 5 Hz.")
        pollingStatusView.text = "Polling: fake telemetry at 5 Hz"
        fakePollingTask = pollExecutor.scheduleAtFixedRate(
            {
                val telemetry = fakeTelemetrySource.next()
                val sentence = rc3Sentence.format(count = rc3Count, telemetry = telemetry)
                rc3Count = (rc3Count + 1) and 0xFFFF
                tcpServer.send(sentence)
                mainHandler.post { renderTelemetry(telemetry) }
            },
            0,
            200,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopFakeTelemetry() {
        fakePollingTask?.cancel(true)
        fakePollingTask = null
        if (::pollingStatusView.isInitialized) {
            pollingStatusView.text = "Polling: stopped"
        }
        appendLog("Fake telemetry stopped.")
    }

    private fun renderTelemetry(telemetry: SubaruTelemetry) {
        telemetryView.text = String.format(
            Locale.US,
            "RPM      %7.0f\nBoost    %7.1f kPa\nCoolant  %7.1f C",
            telemetry.rpm,
            telemetry.boostKpa,
            telemetry.coolantC,
        )
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
}
