package com.utopia.racechronobridge.racechrono

import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import java.util.Locale

class Rc3Sentence {
    fun format(count: Int, telemetry: SubaruTelemetry): String {
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
            telemetry.rpm.asRc3Number(),
            telemetry.gear.takeIf { it > 0 }?.toString().orEmpty(),
            telemetry.boostKpa.asRc3Number(),
            telemetry.coolantC.asRc3Number(),
            telemetry.throttlePercent.asRc3Number(),
            telemetry.acceleratorPercent.asRc3Number(),
            telemetry.primaryWastegateDutyPercent.asRc3Number(),
            telemetry.vehicleSpeedKph.asRc3Number(),
            telemetry.intakeAirTempC.asRc3Number(),
            telemetry.batteryVoltage.asRc3Number(),
            telemetry.massAirflowGps.asRc3Number(),
            telemetry.ignitionTimingDeg.asRc3Number(),
            telemetry.knockCorrectionDeg.asRc3Number(),
            telemetry.learnedIgnitionTimingDeg.asRc3Number(),
            telemetry.injectorPulseWidthMs.asRc3Number(),
            telemetry.fuelPumpDutyPercent.asRc3Number(),
            telemetry.alternatorDutyPercent.asRc3Number(),
        )
        val body = fields.joinToString(separator = ",")
        return "${'$'}$body*${checksum(body)}\r\n"
    }

    fun checksum(body: String): String {
        val checksum = body.fold(0) { current, character -> current xor character.code }
        return checksum.toString(16).uppercase(Locale.US).padStart(length = 2, padChar = '0')
    }

    private fun Double.asRc3Number(): String = String.format(Locale.US, "%.3f", this)
}
