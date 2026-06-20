package com.utopia.racechronobridge.racechrono

import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import com.utopia.racechronobridge.ssm2.modeFor
import java.util.Locale

class Rc3Sentence {
    fun format(
        count: Int,
        telemetry: SubaruTelemetry,
        channelModes: Map<TelemetryChannel, ChannelMode> = TelemetryChannel.defaultModes(),
    ): String {
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
            channelModes.field(TelemetryChannel.RPM, telemetry.rpm.asRc3Number()),
            channelModes.field(TelemetryChannel.GEAR, telemetry.gear.takeIf { it > 0 }?.toString().orEmpty()),
            channelModes.field(TelemetryChannel.BOOST, telemetry.boostKpa.asRc3Number()),
            channelModes.field(TelemetryChannel.COOLANT, telemetry.coolantC.asRc3Number()),
            channelModes.field(TelemetryChannel.THROTTLE, telemetry.throttlePercent.asRc3Number()),
            channelModes.field(TelemetryChannel.ACCELERATOR, telemetry.acceleratorPercent.asRc3Number()),
            channelModes.field(TelemetryChannel.PRIMARY_WGDC, telemetry.primaryWastegateDutyPercent.asRc3Number()),
            channelModes.field(TelemetryChannel.VEHICLE_SPEED, telemetry.vehicleSpeedKph.asRc3Number()),
            channelModes.field(TelemetryChannel.INTAKE_AIR_TEMP, telemetry.intakeAirTempC.asRc3Number()),
            channelModes.field(TelemetryChannel.BATTERY_VOLTAGE, telemetry.batteryVoltage.asRc3Number()),
            channelModes.field(TelemetryChannel.MASS_AIRFLOW, telemetry.massAirflowGps.asRc3Number()),
            channelModes.field(TelemetryChannel.IGNITION_TIMING, telemetry.ignitionTimingDeg.asRc3Number()),
            channelModes.field(TelemetryChannel.KNOCK_CORRECTION, telemetry.knockCorrectionDeg.asRc3Number()),
            channelModes.field(TelemetryChannel.LEARNED_IGNITION, telemetry.learnedIgnitionTimingDeg.asRc3Number()),
            channelModes.field(TelemetryChannel.INJECTOR_PULSE_WIDTH, telemetry.injectorPulseWidthMs.asRc3Number()),
            channelModes.field(TelemetryChannel.FUEL_PUMP_DUTY, telemetry.fuelPumpDutyPercent.asRc3Number()),
            channelModes.field(TelemetryChannel.ALTERNATOR_DUTY, telemetry.alternatorDutyPercent.asRc3Number()),
        )
        val body = fields.joinToString(separator = ",")
        return "${'$'}$body*${checksum(body)}\r\n"
    }

    fun checksum(body: String): String {
        val checksum = body.fold(0) { current, character -> current xor character.code }
        return checksum.toString(16).uppercase(Locale.US).padStart(length = 2, padChar = '0')
    }

    private fun Double.asRc3Number(): String = String.format(Locale.US, "%.3f", this)

    private fun Map<TelemetryChannel, ChannelMode>.field(channel: TelemetryChannel, value: String): String {
        return if (modeFor(channel) == ChannelMode.OFF) "" else value
    }
}
