package com.utopia.racechronobridge.canlog

import com.utopia.racechronobridge.canbus.CanMonitor
import com.utopia.racechronobridge.canbus.MonitorLine
import com.utopia.racechronobridge.elm.ElmResponseParser
import com.utopia.racechronobridge.elm.Elm327Transport

/** Passively records every CAN broadcast frame to [writer] until [requestStop] is called. */
class CanCaptureSession(
    private val transport: Elm327Transport,
    private val writer: CanCaptureWriter,
    private val onLog: (String) -> Unit,
    private val onProgress: (CanCaptureProgress) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var stopRequested = false

    private val parser = ElmResponseParser()
    private val monitor = CanMonitor(transport, nowMillis)

    fun requestStop() {
        stopRequested = true
    }

    fun run() {
        INITIAL_COMMANDS.forEach { command ->
            val raw = transport.sendCommand(command, timeoutMillis = 4_000)
            onLog("$command -> ${raw.trim()}")
            if (parser.isElmError(raw)) {
                error("ELM command failed: $command response=${raw.trim()}")
            }
        }
        onLog("CAN capture started (ATMA, passive).")

        var lastProgressAt = nowMillis()
        try {
            monitor.run(
                shouldStop = { stopRequested },
                onLine = { line ->
                    when (line) {
                        is MonitorLine.Frame -> writer.frame(line)
                        is MonitorLine.Status -> writer.status(line.text)
                    }
                },
                onRestart = { writer.status("ATMA restarted") },
                onIdle = {
                    if (nowMillis() - lastProgressAt >= PROGRESS_INTERVAL_MILLIS) {
                        lastProgressAt = nowMillis()
                        writer.flush()
                        onProgress(progress())
                    }
                },
            )
        } catch (error: IllegalStateException) {
            writer.status("aborted: ${error.message}")
            throw error
        }
        writer.flush()
        onProgress(progress())
        onLog("CAN capture stopped. frames=${writer.frameCount} restarts=${monitor.restarts}")
    }

    private fun progress() = CanCaptureProgress(
        frames = writer.frameCount,
        restarts = monitor.restarts,
        ids = writer.seenIds(),
    )

    companion object {
        private const val PROGRESS_INTERVAL_MILLIS = 1_000L

        // ATS0 drops spaces to save Bluetooth bandwidth; ATH1 + ATCAF0 print the raw ID and all 8 bytes.
        private val INITIAL_COMMANDS = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP6") + CanMonitor.MONITOR_COMMANDS
    }
}

data class CanCaptureProgress(
    val frames: Long,
    val restarts: Int,
    val ids: List<String>,
)
