package com.utopia.racechronobridge.ssm2

import kotlin.math.PI
import kotlin.math.sin

data class SubaruRawTelemetry(
    val coolantRaw: Int,
    val boostRaw: Int,
    val rpmHigh: Int,
    val rpmLow: Int,
) {
    init {
        require(coolantRaw in BYTE_RANGE) { "coolantRaw must be 0..255" }
        require(boostRaw in BYTE_RANGE) { "boostRaw must be 0..255" }
        require(rpmHigh in BYTE_RANGE) { "rpmHigh must be 0..255" }
        require(rpmLow in BYTE_RANGE) { "rpmLow must be 0..255" }
    }

    fun toTelemetry(timestampMillis: Long = System.currentTimeMillis()): SubaruTelemetry {
        return SubaruTelemetry(
            rpm = ((rpmHigh shl 8) + rpmLow) / 4.0,
            boostKpa = (boostRaw - 128.0) * 37.0 / 255.0 * 6.89476,
            coolantC = coolantRaw - 40.0,
            throttlePercent = 0.0,
            acceleratorPercent = 0.0,
            primaryWastegateDutyPercent = 0.0,
            vehicleSpeedKph = 0.0,
            gear = 0,
            intakeAirTempC = 0.0,
            batteryVoltage = 0.0,
            massAirflowGps = 0.0,
            ignitionTimingDeg = 0.0,
            knockCorrectionDeg = 0.0,
            learnedIgnitionTimingDeg = 0.0,
            injectorPulseWidthMs = 0.0,
            fuelPumpDutyPercent = 0.0,
            alternatorDutyPercent = 0.0,
            timestampMillis = timestampMillis,
        )
    }

    private companion object {
        val BYTE_RANGE = 0..255
    }
}

data class SubaruTelemetry(
    val rpm: Double,
    val boostKpa: Double,
    val coolantC: Double,
    val throttlePercent: Double,
    val acceleratorPercent: Double,
    val primaryWastegateDutyPercent: Double,
    val vehicleSpeedKph: Double,
    val gear: Int,
    val intakeAirTempC: Double,
    val batteryVoltage: Double,
    val massAirflowGps: Double,
    val ignitionTimingDeg: Double,
    val knockCorrectionDeg: Double,
    val learnedIgnitionTimingDeg: Double,
    val injectorPulseWidthMs: Double,
    val fuelPumpDutyPercent: Double,
    val alternatorDutyPercent: Double,
    val timestampMillis: Long,
) {
    companion object {
        val EMPTY = SubaruTelemetry(
            rpm = 0.0,
            boostKpa = 0.0,
            coolantC = 0.0,
            throttlePercent = 0.0,
            acceleratorPercent = 0.0,
            primaryWastegateDutyPercent = 0.0,
            vehicleSpeedKph = 0.0,
            gear = 0,
            intakeAirTempC = 0.0,
            batteryVoltage = 0.0,
            massAirflowGps = 0.0,
            ignitionTimingDeg = 0.0,
            knockCorrectionDeg = 0.0,
            learnedIgnitionTimingDeg = 0.0,
            injectorPulseWidthMs = 0.0,
            fuelPumpDutyPercent = 0.0,
            alternatorDutyPercent = 0.0,
            timestampMillis = 0L,
        )
    }
}

class FakeSubaruTelemetrySource {
    private var step = 0

    fun next(): SubaruTelemetry {
        val wave = sin(step * PI / 45.0)
        val rpm = 2200.0 + wave * 900.0
        val boostKpa = -18.0 + wave * 34.0
        val coolantC = 87.0 + sin(step * PI / 120.0) * 2.0
        val throttle = 22.0 + wave * 18.0
        val accelerator = 28.0 + wave * 25.0
        val speed = 80.0 + wave * 18.0
        step += 1
        return SubaruTelemetry(
            rpm = rpm.coerceAtLeast(0.0),
            boostKpa = boostKpa,
            coolantC = coolantC,
            throttlePercent = throttle.coerceIn(0.0, 100.0),
            acceleratorPercent = accelerator.coerceIn(0.0, 100.0),
            primaryWastegateDutyPercent = (35.0 + wave * 20.0).coerceIn(0.0, 100.0),
            vehicleSpeedKph = speed.coerceAtLeast(0.0),
            gear = 4,
            intakeAirTempC = 32.0 + sin(step * PI / 90.0) * 4.0,
            batteryVoltage = 13.9,
            massAirflowGps = 90.0 + wave * 35.0,
            ignitionTimingDeg = 22.0 + wave * 4.0,
            knockCorrectionDeg = -1.0 + wave,
            learnedIgnitionTimingDeg = 2.0,
            injectorPulseWidthMs = 5.5 + wave * 1.5,
            fuelPumpDutyPercent = 45.0 + wave * 20.0,
            alternatorDutyPercent = 60.0,
            timestampMillis = System.currentTimeMillis(),
        )
    }
}
