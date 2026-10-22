package com.utopia.racechronobridge.canbus

import com.utopia.racechronobridge.elm.Elm327Session
import com.utopia.racechronobridge.elm.Elm327Transport
import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannel
import com.utopia.racechronobridge.ssm2.Ssm2Reader
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import com.utopia.racechronobridge.ssm2.modeFor

/**
 * Reads telemetry mainly from the passive CAN broadcast and fills the remaining channels with SSM2.
 *
 * One ELM327 cannot monitor and poll at the same time, so ATMA runs in windows and SSM2 is read
 * in short bursts between them. When every SSM2 channel is off, ATMA is never interrupted.
 */
class CanTelemetrySession(
    transport: Elm327Transport,
    private val elmSession: Elm327Session,
    private val ssm2Reader: Ssm2Reader,
    private val channelModes: () -> Map<TelemetryChannel, ChannelMode>,
    private val customChannels: () -> List<CustomTelemetryChannel>,
    private val onTelemetry: (SubaruTelemetry) -> Unit,
    private val onLog: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var stopRequested = false

    private val monitor = CanMonitor(transport, nowMillis)
    private val decoder = Gh8BroadcastDecoder()
    private var telemetry = SubaruTelemetry.EMPTY
    private var lastPublishedAt = 0L

    fun requestStop() {
        stopRequested = true
    }

    fun run() {
        elmSession.configure(CanMonitor.MONITOR_COMMANDS)
        onLog("CAN telemetry started (ATMA with SSM2 bursts).")
        while (!stopRequested) {
            val ssm2Modes = ssm2ChannelModes(channelModes())
            val ssm2Customs = customChannels().filter { it.canSignal == null }
            val needsSsm2 = ssm2Modes.values.any { it != ChannelMode.OFF } || ssm2Customs.any { it.mode != ChannelMode.OFF }
            val windowEnd = nowMillis() + CAN_WINDOW_MILLIS
            monitor.run(
                shouldStop = { stopRequested || (needsSsm2 && nowMillis() >= windowEnd) },
                onLine = { line -> if (line is MonitorLine.Frame) decoder.accept(line) },
                onIdle = ::publishIfDue,
            )
            if (stopRequested || !needsSsm2) {
                continue
            }
            readSsm2Burst(ssm2Modes, ssm2Customs)
        }
        onLog("CAN telemetry stopped. ATMA restarts=${monitor.restarts}")
    }

    private fun readSsm2Burst(
        ssm2Modes: Map<TelemetryChannel, ChannelMode>,
        ssm2Customs: List<CustomTelemetryChannel>,
    ) {
        try {
            elmSession.configure(CanMonitor.SSM2_COMMANDS)
            telemetry = ssm2Reader.readTelemetry(telemetry, ssm2Modes, ssm2Customs)
        } catch (error: IllegalStateException) {
            onLog("SSM2 burst failed: ${error.message}")
        } finally {
            elmSession.configure(CanMonitor.MONITOR_COMMANDS)
        }
    }

    private fun publishIfDue() {
        val now = nowMillis()
        if (now - lastPublishedAt < PUBLISH_INTERVAL_MILLIS) {
            return
        }
        lastPublishedAt = now
        telemetry = telemetry.withCanSignals(decoder.snapshot(), customChannels(), now)
        onTelemetry(telemetry)
    }

    companion object {
        private const val CAN_WINDOW_MILLIS = 1_500L
        private const val PUBLISH_INTERVAL_MILLIS = 50L

        /** Built-in channels that the GH8 broadcast carries, so SSM2 no longer polls them. */
        val CAN_SOURCED_CHANNELS = mapOf(
            TelemetryChannel.RPM to CanSignal.RPM,
            TelemetryChannel.GEAR to CanSignal.GEAR,
            TelemetryChannel.VEHICLE_SPEED to CanSignal.VEHICLE_SPEED,
            TelemetryChannel.ACCELERATOR to CanSignal.ACCELERATOR,
            TelemetryChannel.COOLANT to CanSignal.COOLANT,
        )

        fun ssm2ChannelModes(modes: Map<TelemetryChannel, ChannelMode>): Map<TelemetryChannel, ChannelMode> =
            TelemetryChannel.entries.associateWith { channel ->
                if (channel in CAN_SOURCED_CHANNELS) ChannelMode.OFF else modes.modeFor(channel)
            }
    }
}

/** Copies decoded CAN values into the telemetry fields and CAN-backed custom channels. */
fun SubaruTelemetry.withCanSignals(
    values: Map<CanSignal, Double>,
    customChannels: List<CustomTelemetryChannel>,
    timestampMillis: Long,
): SubaruTelemetry {
    val customValues = customValues + customChannels.mapNotNull { channel ->
        val signal = channel.canSignal ?: return@mapNotNull null
        val value = values[signal] ?: return@mapNotNull null
        channel.id to channel.convert(value)
    }
    return copy(
        rpm = values[CanSignal.RPM] ?: rpm,
        gear = values[CanSignal.GEAR]?.toInt() ?: gear,
        vehicleSpeedKph = values[CanSignal.VEHICLE_SPEED] ?: vehicleSpeedKph,
        acceleratorPercent = values[CanSignal.ACCELERATOR] ?: acceleratorPercent,
        coolantC = values[CanSignal.COOLANT] ?: coolantC,
        customValues = customValues,
        timestampMillis = timestampMillis,
    )
}
