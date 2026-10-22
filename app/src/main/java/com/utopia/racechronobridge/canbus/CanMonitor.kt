package com.utopia.racechronobridge.canbus

import com.utopia.racechronobridge.elm.Elm327Transport

/**
 * Runs ELM327 ATMA and hands every printed line to the caller.
 *
 * Cheap ELM327 adapters leave monitor mode on their own (BUFFER FULL, STOPPED, CAN ERROR),
 * so ATMA is restarted until [shouldStop] returns true. The adapter must already be configured
 * with ATH1 and ATCAF0 so frames carry their ID and all data bytes.
 */
class CanMonitor(
    private val transport: Elm327Transport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    var restarts = 0
        private set

    fun run(
        shouldStop: () -> Boolean,
        onLine: (MonitorLine) -> Unit,
        onRestart: () -> Unit = {},
        onIdle: () -> Unit = {},
    ) {
        var consecutiveEmptyRuns = 0
        while (true) {
            var framesThisRun = 0
            val stopped = monitorOnce(shouldStop, onIdle) { line ->
                if (line is MonitorLine.Frame) framesThisRun++
                onLine(line)
            }
            if (stopped) {
                return
            }
            consecutiveEmptyRuns = if (framesThisRun == 0) consecutiveEmptyRuns + 1 else 0
            if (consecutiveEmptyRuns >= MAX_CONSECUTIVE_EMPTY_RUNS) {
                error("No CAN frames after $MAX_CONSECUTIVE_EMPTY_RUNS ATMA runs. Check ignition and ATMA support.")
            }
            restarts++
            onRestart()
        }
    }

    /** Returns true when stopped on request, false when the adapter left monitor mode by itself. */
    private fun monitorOnce(
        shouldStop: () -> Boolean,
        onIdle: () -> Unit,
        onLine: (MonitorLine) -> Unit,
    ): Boolean {
        transport.writeRaw("ATMA\r")
        val pending = StringBuilder()
        while (true) {
            if (shouldStop()) {
                stopMonitor(pending, onLine)
                return true
            }
            val chunk = transport.readAvailable(timeoutMillis = READ_TIMEOUT_MILLIS)
            pending.append(chunk)
            consumeCompleteLines(pending, onLine)
            if (chunk.contains('>')) {
                return false
            }
            onIdle()
        }
    }

    private fun stopMonitor(pending: StringBuilder, onLine: (MonitorLine) -> Unit) {
        // Any byte sent to the ELM327 ends ATMA; wait for the prompt so the next command is clean.
        transport.writeRaw("\r")
        val deadline = nowMillis() + STOP_TIMEOUT_MILLIS
        while (nowMillis() < deadline) {
            val chunk = transport.readAvailable(timeoutMillis = READ_TIMEOUT_MILLIS)
            pending.append(chunk)
            consumeCompleteLines(pending, onLine)
            if (chunk.contains('>')) {
                return
            }
        }
    }

    private fun consumeCompleteLines(pending: StringBuilder, onLine: (MonitorLine) -> Unit) {
        while (true) {
            val end = pending.indexOfFirst { it == '\r' || it == '\n' || it == '>' }
            if (end < 0) {
                return
            }
            val line = pending.substring(0, end)
            pending.delete(0, end + 1)
            MonitorLine.parse(line)?.let(onLine)
        }
    }

    companion object {
        const val READ_TIMEOUT_MILLIS = 50L
        private const val STOP_TIMEOUT_MILLIS = 1_000L
        private const val MAX_CONSECUTIVE_EMPTY_RUNS = 5

        /** Commands that switch an SSM2-configured ELM327 to raw monitor output and back. */
        val MONITOR_COMMANDS = listOf("ATH1", "ATCAF0")
        val SSM2_COMMANDS = listOf("ATH0", "ATCAF1")
    }
}
