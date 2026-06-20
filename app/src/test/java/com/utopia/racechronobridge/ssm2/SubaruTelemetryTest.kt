package com.utopia.racechronobridge.ssm2

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubaruTelemetryTest {
    @Test
    fun convertsObservedRawValues() {
        val telemetry = SubaruRawTelemetry(
            coolantRaw = 0x7F,
            boostRaw = 0x3D,
            rpmHigh = 0x1F,
            rpmLow = 0x40,
        ).toTelemetry(timestampMillis = 99L)

        assertEquals(87.0, telemetry.coolantC)
        assertClose(-67.02788250980392, telemetry.boostKpa)
        assertEquals(2000.0, telemetry.rpm)
        assertEquals(99L, telemetry.timestampMillis)
    }

    @Test
    fun rejectsValuesOutsideByteRange() {
        assertFailsWith<IllegalArgumentException> {
            SubaruRawTelemetry(coolantRaw = 256, boostRaw = 0, rpmHigh = 0, rpmLow = 0)
        }
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 0.001, "expected=$expected actual=$actual")
    }
}
