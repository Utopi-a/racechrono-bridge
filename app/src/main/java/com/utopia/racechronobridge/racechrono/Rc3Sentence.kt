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
            "",
            telemetry.boostKpa.asRc3Number(),
            telemetry.coolantC.asRc3Number(),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
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
