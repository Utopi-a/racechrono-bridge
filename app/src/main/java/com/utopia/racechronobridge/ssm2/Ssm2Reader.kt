package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Session

class Ssm2Reader(
    private val elmSession: Elm327Session,
) {
    private var nextSlowIndex = 0

    fun readTelemetry(previous: SubaruTelemetry): SubaruTelemetry {
        val rpmHigh = elmSession.readByte(Ssm2Parameter.RPM_HIGH)
        val rpmLow = elmSession.readByte(Ssm2Parameter.RPM_LOW)
        val boost = elmSession.readByte(Ssm2Parameter.BOOST)
        val throttle = elmSession.readByte(Ssm2Parameter.THROTTLE)
        val accelerator = elmSession.readByte(Ssm2Parameter.ACCELERATOR)
        val speed = elmSession.readByte(Ssm2Parameter.VEHICLE_SPEED)

        var telemetry = previous.copy(
            rpm = ((rpmHigh shl 8) + rpmLow) / 4.0,
            boostKpa = boost.toRelativePressureKpa(),
            throttlePercent = throttle.toPercent(),
            acceleratorPercent = accelerator.toPercent(),
            vehicleSpeedKph = speed.toDouble(),
            timestampMillis = System.currentTimeMillis(),
        )

        repeat(SLOW_READS_PER_CYCLE) {
            val slowChannel = SLOW_CHANNELS[nextSlowIndex]
            telemetry = readSlowChannel(slowChannel, telemetry)
            nextSlowIndex = (nextSlowIndex + 1) % SLOW_CHANNELS.size
        }

        return telemetry
    }

    fun readMvpTelemetry(): SubaruTelemetry = readTelemetry(SubaruTelemetry.EMPTY)

    private fun readSlowChannel(channel: SlowChannel, telemetry: SubaruTelemetry): SubaruTelemetry {
        return when (channel) {
            SlowChannel.COOLANT -> telemetry.copy(
                coolantC = elmSession.readByte(Ssm2Parameter.COOLANT).toTemperatureC(),
            )
            SlowChannel.PRIMARY_WGDC -> telemetry.copy(
                primaryWastegateDutyPercent = elmSession.readByte(Ssm2Parameter.PRIMARY_WGDC).toPercent(),
            )
            SlowChannel.GEAR -> telemetry.copy(
                gear = elmSession.readByte(Ssm2Parameter.GEAR_POSITION) + 1,
            )
            SlowChannel.INTAKE_AIR_TEMP -> telemetry.copy(
                intakeAirTempC = elmSession.readByte(Ssm2Parameter.INTAKE_AIR_TEMP).toTemperatureC(),
            )
            SlowChannel.BATTERY -> telemetry.copy(
                batteryVoltage = elmSession.readByte(Ssm2Parameter.BATTERY_VOLTAGE) * 8.0 / 100.0,
            )
            SlowChannel.MASS_AIRFLOW -> telemetry.copy(
                massAirflowGps = readU16(
                    high = Ssm2Parameter.MASS_AIRFLOW_HIGH,
                    low = Ssm2Parameter.MASS_AIRFLOW_LOW,
                ) / 100.0,
            )
            SlowChannel.IGNITION_TIMING -> telemetry.copy(
                ignitionTimingDeg = elmSession.readByte(Ssm2Parameter.IGNITION_TIMING).toSignedHalfDegree(),
            )
            SlowChannel.KNOCK_CORRECTION -> telemetry.copy(
                knockCorrectionDeg = elmSession.readByte(Ssm2Parameter.KNOCK_CORRECTION).toSignedHalfDegree(),
            )
            SlowChannel.LEARNED_IGNITION -> telemetry.copy(
                learnedIgnitionTimingDeg = elmSession.readByte(Ssm2Parameter.LEARNED_IGNITION_TIMING)
                    .toSignedHalfDegree(),
            )
            SlowChannel.INJECTOR_PULSE_WIDTH -> telemetry.copy(
                injectorPulseWidthMs = elmSession.readByte(Ssm2Parameter.INJECTOR_PULSE_WIDTH) * 256.0 / 1000.0,
            )
            SlowChannel.FUEL_PUMP_DUTY -> telemetry.copy(
                fuelPumpDutyPercent = elmSession.readByte(Ssm2Parameter.FUEL_PUMP_DUTY).toPercent(),
            )
            SlowChannel.ALTERNATOR_DUTY -> telemetry.copy(
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

    private enum class SlowChannel {
        COOLANT,
        PRIMARY_WGDC,
        GEAR,
        INTAKE_AIR_TEMP,
        BATTERY,
        MASS_AIRFLOW,
        IGNITION_TIMING,
        KNOCK_CORRECTION,
        LEARNED_IGNITION,
        INJECTOR_PULSE_WIDTH,
        FUEL_PUMP_DUTY,
        ALTERNATOR_DUTY,
    }

    companion object {
        private const val SLOW_READS_PER_CYCLE = 2
        private val SLOW_CHANNELS = SlowChannel.entries.toList()
    }
}
