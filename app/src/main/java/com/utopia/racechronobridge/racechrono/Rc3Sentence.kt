package com.utopia.racechronobridge.racechrono

import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannel
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import com.utopia.racechronobridge.ssm2.modeFor
import java.util.Locale

class Rc3Sentence {
    fun format(
        count: Int,
        telemetry: SubaruTelemetry,
        channelModes: Map<TelemetryChannel, ChannelMode> = TelemetryChannel.defaultModes(),
        customChannels: List<CustomTelemetryChannel> = emptyList(),
    ): String {
        val customByField = customChannels.associateBy { it.rc3Field }
        val fields = listOf(
            "RC3",
            "",
            (count and 0xFFFF).toString(),
            "",
            "",
            "",
            "",
            "",
            "",
        ) + TelemetryChannel.entries.map { channel ->
            field(
                channel = channel,
                value = builtInValue(channel, telemetry),
                telemetry = telemetry,
                channelModes = channelModes,
                customByField = customByField,
            )
        }
        val body = fields.joinToString(separator = ",")
        return "${'$'}$body*${checksum(body)}\r\n"
    }

    fun checksum(body: String): String {
        val checksum = body.fold(0) { current, character -> current xor character.code }
        return checksum.toString(16).uppercase(Locale.US).padStart(length = 2, padChar = '0')
    }

    private fun Double.asRc3Number(): String = String.format(Locale.US, "%.3f", this)

    private fun builtInValue(channel: TelemetryChannel, telemetry: SubaruTelemetry): String {
        return when (channel) {
            TelemetryChannel.RPM -> telemetry.rpm.asRc3Number()
            TelemetryChannel.GEAR -> telemetry.gear.takeIf { it > 0 }?.toString().orEmpty()
            TelemetryChannel.BOOST -> telemetry.boostKpa.asRc3Number()
            TelemetryChannel.COOLANT -> telemetry.coolantC.asRc3Number()
            TelemetryChannel.THROTTLE -> telemetry.throttlePercent.asRc3Number()
            TelemetryChannel.ACCELERATOR -> telemetry.acceleratorPercent.asRc3Number()
            TelemetryChannel.PRIMARY_WGDC -> telemetry.primaryWastegateDutyPercent.asRc3Number()
            TelemetryChannel.VEHICLE_SPEED -> telemetry.vehicleSpeedKph.asRc3Number()
            TelemetryChannel.INTAKE_AIR_TEMP -> telemetry.intakeAirTempC.asRc3Number()
            TelemetryChannel.BATTERY_VOLTAGE -> telemetry.batteryVoltage.asRc3Number()
            TelemetryChannel.MASS_AIRFLOW -> telemetry.massAirflowGps.asRc3Number()
            TelemetryChannel.IGNITION_TIMING -> telemetry.ignitionTimingDeg.asRc3Number()
            TelemetryChannel.KNOCK_CORRECTION -> telemetry.knockCorrectionDeg.asRc3Number()
            TelemetryChannel.LEARNED_IGNITION -> telemetry.learnedIgnitionTimingDeg.asRc3Number()
            TelemetryChannel.INJECTOR_PULSE_WIDTH -> telemetry.injectorPulseWidthMs.asRc3Number()
            TelemetryChannel.FUEL_PUMP_DUTY -> telemetry.fuelPumpDutyPercent.asRc3Number()
            TelemetryChannel.ALTERNATOR_DUTY -> telemetry.alternatorDutyPercent.asRc3Number()
        }
    }

    private fun field(
        channel: TelemetryChannel,
        value: String,
        telemetry: SubaruTelemetry,
        channelModes: Map<TelemetryChannel, ChannelMode>,
        customByField: Map<String, CustomTelemetryChannel>,
    ): String {
        val customChannel = customByField[channel.rc3Field]
        if (customChannel != null) {
            if (customChannel.mode == ChannelMode.OFF) {
                return ""
            }
            return telemetry.customValues[customChannel.id]?.asRc3Number().orEmpty()
        }

        return if (channelModes.modeFor(channel) == ChannelMode.OFF) "" else value
    }
}
