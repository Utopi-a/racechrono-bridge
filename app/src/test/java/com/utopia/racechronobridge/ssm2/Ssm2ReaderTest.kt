package com.utopia.racechronobridge.ssm2

import kotlin.test.Test
import kotlin.test.assertEquals

class Ssm2ReaderTest {
    @Test
    fun decodersMatchRomRaiderFormulasForByteChannels() {
        assertEquals(0.0, Ssm2ValueDecoders.percent(0))
        assertEquals(100.0, Ssm2ValueDecoders.percent(0xFF))
        assertEquals(80.0, Ssm2ValueDecoders.temperatureC(120))
        assertEquals(0.0, Ssm2ValueDecoders.relativePressureKpa(128))
        assertEquals(-64.0, Ssm2ValueDecoders.signedHalfDegree(0))
        assertEquals(63.5, Ssm2ValueDecoders.signedHalfDegree(0xFF))
    }

    @Test
    fun decodesGearPositionAndTreatsOutOfRangeAsUnknown() {
        assertEquals(1, Ssm2ValueDecoders.gearPosition(0))
        assertEquals(4, Ssm2ValueDecoders.gearPosition(3))
        assertEquals(8, Ssm2ValueDecoders.gearPosition(7))
        assertEquals(0, Ssm2ValueDecoders.gearPosition(8))
        assertEquals(0, Ssm2ValueDecoders.gearPosition(0xFF))
    }
}
