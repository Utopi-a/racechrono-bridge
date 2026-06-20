package com.utopia.racechronobridge.ssm2

import kotlin.test.Test
import kotlin.test.assertEquals

class Ssm2ReaderTest {
    @Test
    fun decodesGearPositionAndTreats255AsUnknown() {
        assertEquals(1, 0.toGearPosition())
        assertEquals(4, 3.toGearPosition())
        assertEquals(8, 7.toGearPosition())
        assertEquals(0, 0xFF.toGearPosition())
    }
}
