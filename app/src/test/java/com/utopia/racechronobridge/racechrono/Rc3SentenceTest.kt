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
    fun formatMapsTelemetryToRaceChronoChannels() {
        val telemetry = SubaruTelemetry.EMPTY.copy(
            rpm = 3200.0,
            boostKpa = -12.345,
            coolantC = 87.0,
            throttlePercent = 44.0,
            acceleratorPercent = 55.0,
            primaryWastegateDutyPercent = 66.0,
            vehicleSpeedKph = 88.0,
            gear = 3,
            intakeAirTempC = 31.0,
            batteryVoltage = 13.8,
            massAirflowGps = 120.0,
            ignitionTimingDeg = 22.5,
            knockCorrectionDeg = -1.0,
            learnedIgnitionTimingDeg = 2.0,
            injectorPulseWidthMs = 5.5,
            fuelPumpDutyPercent = 70.0,
            alternatorDutyPercent = 60.0,
            timestampMillis = 1234L,
        )

        val sentence = rc3Sentence.format(count = 7, telemetry = telemetry)

        assertEquals(
            "${'$'}RC3,,7,,,,,,,3200.000,3,-12.345,87.000,44.000,55.000,66.000,88.000," +
                "31.000,13.800,120.000,22.500,-1.000,2.000,5.500,70.000,60.000*09\r\n",
            sentence,
        )
    }
}
