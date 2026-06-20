package com.utopia.racechronobridge.racechrono

import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals

class Rc3SentenceTest {
    private val rc3Sentence = Rc3Sentence()

    @Test
    fun checksumMatchesRaceChronoExample() {
        val body = "RC3,,2,0.240,-0.560,-0.290,7.938,-0.125,0.063,,,7.063,-40.438,2.625,70.875,9.875,-48.250,-6.360,-0.340,7.450,-6.100,-0.940,7.170,0,1,10"

        assertEquals("2F", rc3Sentence.checksum(body))
    }

    @Test
    fun formatMapsTelemetryToRpmBoostAndCoolant() {
        val telemetry = SubaruTelemetry(
            rpm = 3200.0,
            boostKpa = -12.345,
            coolantC = 87.0,
            timestampMillis = 1234L,
        )

        val sentence = rc3Sentence.format(count = 7, telemetry = telemetry)

        assertEquals("${'$'}RC3,,7,,,,,,,3200.000,,-12.345,87.000,,,,,,,,,,,,,*05\r\n", sentence)
    }
}
