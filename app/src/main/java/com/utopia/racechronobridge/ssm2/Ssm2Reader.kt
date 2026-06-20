package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Session

class Ssm2Reader(
    private val elmSession: Elm327Session,
) {
    private var nextSlowIndex = 0

    fun readTelemetry(
        previous: SubaruTelemetry,
        channelModes: Map<TelemetryChannel, ChannelMode> = TelemetryChannel.defaultModes(),
        customChannels: List<CustomTelemetryChannel> = emptyList(),
    ): SubaruTelemetry {
        var telemetry = previous.copy(timestampMillis = System.currentTimeMillis())

        FAST_CHANNEL_ORDER
            .filter { channel -> channelModes.modeFor(channel) == ChannelMode.FAST }
            .forEach { channel -> telemetry = readChannel(channel, telemetry) }

        customChannels
            .filter { channel -> channel.mode == ChannelMode.FAST }
            .forEach { channel -> telemetry = readCustomChannel(channel, telemetry) }

        repeat(SLOW_READS_PER_CYCLE) {
            val slowTarget = nextSlowTarget(channelModes, customChannels) ?: return@repeat
            telemetry = when (slowTarget) {
                is ReadTarget.BuiltIn -> readChannel(slowTarget.channel, telemetry)
                is ReadTarget.Custom -> readCustomChannel(slowTarget.channel, telemetry)
            }
        }

        return telemetry
    }

    fun readMvpTelemetry(): SubaruTelemetry = readTelemetry(SubaruTelemetry.EMPTY)

    private fun readChannel(channel: TelemetryChannel, telemetry: SubaruTelemetry): SubaruTelemetry {
        return when (channel) {
            TelemetryChannel.RPM -> telemetry.copy(
                rpm = Ssm2ValueDecoders.unsigned16(
                    high = elmSession.readByte(Ssm2Parameter.RPM_HIGH),
                    low = elmSession.readByte(Ssm2Parameter.RPM_LOW),
                ) / 4.0,
            )
            TelemetryChannel.BOOST -> telemetry.copy(
                boostKpa = Ssm2ValueDecoders.relativePressureKpa(elmSession.readByte(Ssm2Parameter.BOOST)),
            )
            TelemetryChannel.COOLANT -> telemetry.copy(
                coolantC = Ssm2ValueDecoders.temperatureC(elmSession.readByte(Ssm2Parameter.COOLANT)),
            )
            TelemetryChannel.THROTTLE -> telemetry.copy(
                throttlePercent = Ssm2ValueDecoders.percent(elmSession.readByte(Ssm2Parameter.THROTTLE)),
            )
            TelemetryChannel.ACCELERATOR -> telemetry.copy(
                acceleratorPercent = Ssm2ValueDecoders.percent(elmSession.readByte(Ssm2Parameter.ACCELERATOR)),
            )
            TelemetryChannel.PRIMARY_WGDC -> telemetry.copy(
                primaryWastegateDutyPercent = Ssm2ValueDecoders.percent(
                    elmSession.readByte(Ssm2Parameter.PRIMARY_WGDC),
                ),
            )
            TelemetryChannel.VEHICLE_SPEED -> telemetry.copy(
                vehicleSpeedKph = elmSession.readByte(Ssm2Parameter.VEHICLE_SPEED).toDouble(),
            )
            TelemetryChannel.GEAR -> telemetry.copy(
                gear = Ssm2ValueDecoders.gearPosition(elmSession.readByte(Ssm2Parameter.GEAR_POSITION)),
            )
            TelemetryChannel.INTAKE_AIR_TEMP -> telemetry.copy(
                intakeAirTempC = Ssm2ValueDecoders.temperatureC(elmSession.readByte(Ssm2Parameter.INTAKE_AIR_TEMP)),
            )
            TelemetryChannel.BATTERY_VOLTAGE -> telemetry.copy(
                batteryVoltage = elmSession.readByte(Ssm2Parameter.BATTERY_VOLTAGE) * 8.0 / 100.0,
            )
            TelemetryChannel.MASS_AIRFLOW -> telemetry.copy(
                massAirflowGps = Ssm2ValueDecoders.unsigned16(
                    high = elmSession.readByte(Ssm2Parameter.MASS_AIRFLOW_HIGH),
                    low = elmSession.readByte(Ssm2Parameter.MASS_AIRFLOW_LOW),
                ) / 100.0,
            )
            TelemetryChannel.IGNITION_TIMING -> telemetry.copy(
                ignitionTimingDeg = Ssm2ValueDecoders.signedHalfDegree(
                    elmSession.readByte(Ssm2Parameter.IGNITION_TIMING),
                ),
            )
            TelemetryChannel.KNOCK_CORRECTION -> telemetry.copy(
                knockCorrectionDeg = Ssm2ValueDecoders.signedHalfDegree(
                    elmSession.readByte(Ssm2Parameter.KNOCK_CORRECTION),
                ),
            )
            TelemetryChannel.LEARNED_IGNITION -> telemetry.copy(
                learnedIgnitionTimingDeg = Ssm2ValueDecoders.signedHalfDegree(
                    elmSession.readByte(Ssm2Parameter.LEARNED_IGNITION_TIMING),
                ),
            )
            TelemetryChannel.INJECTOR_PULSE_WIDTH -> telemetry.copy(
                injectorPulseWidthMs = elmSession.readByte(Ssm2Parameter.INJECTOR_PULSE_WIDTH) * 256.0 / 1000.0,
            )
            TelemetryChannel.FUEL_PUMP_DUTY -> telemetry.copy(
                fuelPumpDutyPercent = Ssm2ValueDecoders.percent(
                    elmSession.readByte(Ssm2Parameter.FUEL_PUMP_DUTY),
                ),
            )
            TelemetryChannel.ALTERNATOR_DUTY -> telemetry.copy(
                alternatorDutyPercent = elmSession.readByte(Ssm2Parameter.ALTERNATOR_DUTY).toDouble(),
            )
        }
    }

    private fun readCustomChannel(
        channel: CustomTelemetryChannel,
        telemetry: SubaruTelemetry,
    ): SubaruTelemetry {
        val rawValue = readCustomRawValue(channel)
        val value = channel.convert(rawValue)
        return telemetry.copy(customValues = telemetry.customValues + (channel.id to value))
    }

    private fun readCustomRawValue(channel: CustomTelemetryChannel): Int {
        var rawValue = 0
        repeat(channel.bytes) { byteOffset ->
            rawValue = (rawValue shl 8) + elmSession.readByte(
                command = channel.commandForByte(byteOffset),
                label = "${channel.label}[$byteOffset]",
            )
        }
        return if (channel.signed) {
            Ssm2ValueDecoders.signedValue(rawValue, bytes = channel.bytes)
        } else {
            rawValue
        }
    }

    private fun nextSlowTarget(
        channelModes: Map<TelemetryChannel, ChannelMode>,
        customChannels: List<CustomTelemetryChannel>,
    ): ReadTarget? {
        val slowTargets = SLOW_CHANNEL_ORDER
            .filter { channel -> channelModes.modeFor(channel) == ChannelMode.SLOW }
            .map { channel -> ReadTarget.BuiltIn(channel) } +
            customChannels
                .filter { channel -> channel.mode == ChannelMode.SLOW }
                .map { channel -> ReadTarget.Custom(channel) }
        if (slowTargets.isEmpty()) {
            return null
        }
        val target = slowTargets[nextSlowIndex % slowTargets.size]
        nextSlowIndex = (nextSlowIndex + 1) % slowTargets.size
        return target
    }

    private sealed class ReadTarget {
        data class BuiltIn(val channel: TelemetryChannel) : ReadTarget()
        data class Custom(val channel: CustomTelemetryChannel) : ReadTarget()
    }

    companion object {
        private const val SLOW_READS_PER_CYCLE = 2
        private val FAST_CHANNEL_ORDER = TelemetryChannel.entries.toList()
        private val SLOW_CHANNEL_ORDER = TelemetryChannel.entries.toList()
    }
}
