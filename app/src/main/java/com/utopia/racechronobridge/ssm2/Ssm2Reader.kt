package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Session

class Ssm2Reader(
    private val elmSession: Elm327Session,
) {
    private var nextSlowIndex = 0

    fun readTelemetry(
        previous: SubaruTelemetry,
        channelModes: Map<TelemetryChannel, ChannelMode> = TelemetryChannel.defaultModes(),
    ): SubaruTelemetry {
        var telemetry = previous.copy(timestampMillis = System.currentTimeMillis())

        FAST_CHANNEL_ORDER
            .filter { channel -> channelModes.modeFor(channel) == ChannelMode.FAST }
            .forEach { channel -> telemetry = readChannel(channel, telemetry) }

        repeat(SLOW_READS_PER_CYCLE) {
            val slowChannel = nextSlowChannel(channelModes) ?: return@repeat
            telemetry = readChannel(slowChannel, telemetry)
        }

        return telemetry
    }

    fun readMvpTelemetry(): SubaruTelemetry = readTelemetry(SubaruTelemetry.EMPTY)

    private fun readChannel(channel: TelemetryChannel, telemetry: SubaruTelemetry): SubaruTelemetry {
        return when (channel) {
            TelemetryChannel.RPM -> telemetry.copy(
                rpm = readU16(
                    high = Ssm2Parameter.RPM_HIGH,
                    low = Ssm2Parameter.RPM_LOW,
                ) / 4.0,
            )
            TelemetryChannel.BOOST -> telemetry.copy(
                boostKpa = elmSession.readByte(Ssm2Parameter.BOOST).toRelativePressureKpa(),
            )
            TelemetryChannel.COOLANT -> telemetry.copy(
                coolantC = elmSession.readByte(Ssm2Parameter.COOLANT).toTemperatureC(),
            )
            TelemetryChannel.THROTTLE -> telemetry.copy(
                throttlePercent = elmSession.readByte(Ssm2Parameter.THROTTLE).toPercent(),
            )
            TelemetryChannel.ACCELERATOR -> telemetry.copy(
                acceleratorPercent = elmSession.readByte(Ssm2Parameter.ACCELERATOR).toPercent(),
            )
            TelemetryChannel.PRIMARY_WGDC -> telemetry.copy(
                primaryWastegateDutyPercent = elmSession.readByte(Ssm2Parameter.PRIMARY_WGDC).toPercent(),
            )
            TelemetryChannel.VEHICLE_SPEED -> telemetry.copy(
                vehicleSpeedKph = elmSession.readByte(Ssm2Parameter.VEHICLE_SPEED).toDouble(),
            )
            TelemetryChannel.GEAR -> telemetry.copy(
                gear = elmSession.readByte(Ssm2Parameter.GEAR_POSITION) + 1,
            )
            TelemetryChannel.INTAKE_AIR_TEMP -> telemetry.copy(
                intakeAirTempC = elmSession.readByte(Ssm2Parameter.INTAKE_AIR_TEMP).toTemperatureC(),
            )
            TelemetryChannel.BATTERY_VOLTAGE -> telemetry.copy(
                batteryVoltage = elmSession.readByte(Ssm2Parameter.BATTERY_VOLTAGE) * 8.0 / 100.0,
            )
            TelemetryChannel.MASS_AIRFLOW -> telemetry.copy(
                massAirflowGps = readU16(
                    high = Ssm2Parameter.MASS_AIRFLOW_HIGH,
                    low = Ssm2Parameter.MASS_AIRFLOW_LOW,
                ) / 100.0,
            )
            TelemetryChannel.IGNITION_TIMING -> telemetry.copy(
                ignitionTimingDeg = elmSession.readByte(Ssm2Parameter.IGNITION_TIMING).toSignedHalfDegree(),
            )
            TelemetryChannel.KNOCK_CORRECTION -> telemetry.copy(
                knockCorrectionDeg = elmSession.readByte(Ssm2Parameter.KNOCK_CORRECTION).toSignedHalfDegree(),
            )
            TelemetryChannel.LEARNED_IGNITION -> telemetry.copy(
                learnedIgnitionTimingDeg = elmSession.readByte(Ssm2Parameter.LEARNED_IGNITION_TIMING)
                    .toSignedHalfDegree(),
            )
            TelemetryChannel.INJECTOR_PULSE_WIDTH -> telemetry.copy(
                injectorPulseWidthMs = elmSession.readByte(Ssm2Parameter.INJECTOR_PULSE_WIDTH) * 256.0 / 1000.0,
            )
            TelemetryChannel.FUEL_PUMP_DUTY -> telemetry.copy(
                fuelPumpDutyPercent = elmSession.readByte(Ssm2Parameter.FUEL_PUMP_DUTY).toPercent(),
            )
            TelemetryChannel.ALTERNATOR_DUTY -> telemetry.copy(
                alternatorDutyPercent = elmSession.readByte(Ssm2Parameter.ALTERNATOR_DUTY).toDouble(),
            )
        }
    }

    private fun readU16(high: Ssm2Parameter, low: Ssm2Parameter): Int {
        return (elmSession.readByte(high) shl 8) + elmSession.readByte(low)
    }

    private fun Int.toPercent(): Double = this * 100.0 / 255.0

    private fun Int.toTemperatureC(): Double = this - 40.0

    private fun Int.toRelativePressureKpa(): Double = (this - 128.0) * 37.0 / 255.0 * 6.89476

    private fun Int.toSignedHalfDegree(): Double = (this - 128.0) / 2.0

    private fun nextSlowChannel(channelModes: Map<TelemetryChannel, ChannelMode>): TelemetryChannel? {
        val slowChannels = SLOW_CHANNEL_ORDER.filter { channel ->
            channelModes.modeFor(channel) == ChannelMode.SLOW
        }
        if (slowChannels.isEmpty()) {
            return null
        }
        val channel = slowChannels[nextSlowIndex % slowChannels.size]
        nextSlowIndex = (nextSlowIndex + 1) % slowChannels.size
        return channel
    }

    companion object {
        private const val SLOW_READS_PER_CYCLE = 2
        private val FAST_CHANNEL_ORDER = TelemetryChannel.entries.toList()
        private val SLOW_CHANNEL_ORDER = TelemetryChannel.entries.toList()
    }
}
