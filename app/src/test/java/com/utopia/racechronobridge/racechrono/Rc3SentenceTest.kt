package com.utopia.racechronobridge.racechrono

import com.utopia.racechronobridge.ssm2.ChannelMode
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannel
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
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

    @Test
    fun formatLeavesOffChannelsBlank() {
        val modes = TelemetryChannel.defaultModes().apply {
            this[TelemetryChannel.GEAR] = ChannelMode.OFF
            this[TelemetryChannel.BOOST] = ChannelMode.OFF
        }

        val sentence = rc3Sentence.format(
            count = 1,
            telemetry = SubaruTelemetry.EMPTY.copy(
                rpm = 2500.0,
                boostKpa = 12.0,
                coolantC = 90.0,
                gear = 4,
            ),
            channelModes = modes,
        )

        val fields = sentence.substringAfter("${'$'}").substringBefore("*").split(",")
        assertEquals("2500.000", fields[9])
        assertEquals("", fields[10])
        assertEquals("", fields[11])
        assertEquals("90.000", fields[12])
    }

    @Test
    fun telemetryChannelsExposeRaceChronoFieldMapping() {
        assertEquals("Digital 1/RPM", TelemetryChannel.RPM.rc3Field)
        assertEquals("Digital 2", TelemetryChannel.GEAR.rc3Field)
        assertEquals("Analog 1", TelemetryChannel.BOOST.rc3Field)
        assertEquals("Analog 15", TelemetryChannel.ALTERNATOR_DUTY.rc3Field)
    }

    @Test
    fun formatUsesCustomChannelForAssignedRaceChronoField() {
        val customChannel = CustomTelemetryChannel(
            id = "oil_temp",
            rc3Field = "Analog 15",
            label = "Oil temp",
            unit = "C",
            address = 0x000108,
            bytes = 1,
            scale = 1.0,
            offset = -40.0,
            mode = ChannelMode.SLOW,
            signed = false,
        )
        val telemetry = SubaruTelemetry.EMPTY.copy(
            alternatorDutyPercent = 60.0,
            customValues = mapOf("oil_temp" to 112.0),
        )

        val sentence = rc3Sentence.format(
            count = 2,
            telemetry = telemetry,
            customChannels = listOf(customChannel),
        )

        val fields = sentence.substringAfter("${'$'}").substringBefore("*").split(",")
        assertEquals("112.000", fields[25])
    }
}
