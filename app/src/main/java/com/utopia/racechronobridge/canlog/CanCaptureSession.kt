package com.utopia.racechronobridge.canlog

import com.utopia.racechronobridge.elm.ElmResponseParser
import com.utopia.racechronobridge.elm.Elm327Transport

/**
 * Passively records CAN broadcast traffic with ELM327 ATMA.
 *
 * Cheap ELM327 adapters stop ATMA with BUFFER FULL when Bluetooth cannot keep up,
 * so the monitor is restarted automatically until [requestStop] is called.
 */
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
    private var restarts = 0

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

        var consecutiveEmptyRuns = 0
        while (!stopRequested) {
            val framesBefore = writer.frameCount
            monitorUntilPromptOrStop()
            if (stopRequested) {
                break
            }
            consecutiveEmptyRuns = if (writer.frameCount == framesBefore) consecutiveEmptyRuns + 1 else 0
            if (consecutiveEmptyRuns >= MAX_CONSECUTIVE_EMPTY_RUNS) {
                writer.status("aborted: no frames after $MAX_CONSECUTIVE_EMPTY_RUNS ATMA runs")
                error("No CAN frames received. Check ignition and that the adapter supports ATMA.")
            }
            restarts++
            writer.status("ATMA restarted")
        }
        onLog("CAN capture stopped. frames=${writer.frameCount} restarts=$restarts")
    }

    private fun monitorUntilPromptOrStop() {
        transport.writeRaw("ATMA\r")
        val pending = StringBuilder()
        var lastProgressAt = nowMillis()
        while (true) {
            if (stopRequested) {
                stopMonitor()
                return
            }
            val chunk = transport.readAvailable(timeoutMillis = 200)
            pending.append(chunk)
            consumeCompleteLines(pending)
            if (chunk.contains('>')) {
                // The adapter left monitor mode by itself (BUFFER FULL, STOPPED, CAN ERROR...).
                return
            }
            if (nowMillis() - lastProgressAt >= PROGRESS_INTERVAL_MILLIS) {
                lastProgressAt = nowMillis()
                writer.flush()
                onProgress(progress())
            }
        }
    }

    private fun consumeCompleteLines(pending: StringBuilder) {
        while (true) {
            val end = pending.indexOfFirst { it == '\r' || it == '\n' || it == '>' }
            if (end < 0) {
                return
            }
            val line = pending.substring(0, end)
            pending.delete(0, end + 1)
            when (val parsed = MonitorLine.parse(line)) {
                is MonitorLine.Frame -> writer.frame(parsed)
                is MonitorLine.Status -> writer.status(parsed.text)
                null -> Unit
            }
        }
    }

    private fun stopMonitor() {
        // Any byte sent to the ELM327 ends ATMA; wait for the prompt so the next command is clean.
        transport.writeRaw("\r")
        val deadline = nowMillis() + 1_000
        val pending = StringBuilder()
        while (nowMillis() < deadline) {
            val chunk = transport.readAvailable(timeoutMillis = 100)
            pending.append(chunk)
            consumeCompleteLines(pending)
            if (chunk.contains('>')) {
                break
            }
        }
        writer.flush()
        onProgress(progress())
    }

    private fun progress() = CanCaptureProgress(
        frames = writer.frameCount,
        restarts = restarts,
        ids = writer.seenIds(),
    )

    companion object {
        private const val PROGRESS_INTERVAL_MILLIS = 1_000L
        private const val MAX_CONSECUTIVE_EMPTY_RUNS = 5

        // ATS0 drops spaces to save Bluetooth bandwidth; ATH1 + ATCAF0 print the raw ID and all 8 bytes.
        private val INITIAL_COMMANDS = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH1",
            "ATSP6",
            "ATCAF0",
        )
    }
}

data class CanCaptureProgress(
    val frames: Long,
    val restarts: Int,
    val ids: List<String>,
)
