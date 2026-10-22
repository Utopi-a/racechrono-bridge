package com.utopia.racechronobridge.canbus

import kotlin.test.Test
import kotlin.test.assertEquals

/** Frames are taken verbatim from a GH8 drive capture (can-20261022-185734.csv). */
class Gh8BroadcastDecoderTest {
    private fun decode(vararg lines: String): Map<CanSignal, Double> {
        val decoder = Gh8BroadcastDecoder()
        lines.forEach { line -> decoder.accept(MonitorLine.parse(line) as MonitorLine.Frame) }
        return decoder.snapshot()
    }

    @Test
    fun decodesEngineSpeedAndAcceleratorFrom410() {
        val coldIdle = decode("41024003F12004A06E1")
        assertEquals(1610.0, coldIdle.getValue(CanSignal.RPM))
        assertEquals(0.0, coldIdle.getValue(CanSignal.ACCELERATOR))

        val cruising = decode("410536F7A123F780621")
        assertEquals(1656.0, cruising.getValue(CanSignal.RPM))
        assertEquals(50.4, cruising.getValue(CanSignal.ACCELERATOR), 0.001)
    }

    @Test
    fun decodesGearClutchAndBrakeFrom411() {
        val neutral = decode("41105FF7F0006000004")
        assertEquals(0.0, neutral.getValue(CanSignal.GEAR))
        assertEquals(0.0, neutral.getValue(CanSignal.CLUTCH))
        assertEquals(0.0, neutral.getValue(CanSignal.BRAKE))

        val clutchIn = decode("41145FF3F0003000000")
        assertEquals(3.0, clutchIn.getValue(CanSignal.GEAR))
        assertEquals(1.0, clutchIn.getValue(CanSignal.CLUTCH))

        val braking = decode("41105FF7F0001001004")
        assertEquals(1.0, braking.getValue(CanSignal.GEAR))
        assertEquals(1.0, braking.getValue(CanSignal.BRAKE))
    }

    @Test
    fun decodesVehicleAndWheelSpeeds() {
        val values = decode("51200E070040016FFBF", "5137004700472047204")
        assertEquals(63.9, values.getValue(CanSignal.VEHICLE_SPEED), 0.001)
        assertEquals(63.9, values.getValue(CanSignal.WHEEL_1), 0.001)
        assertEquals(63.9, values.getValue(CanSignal.WHEEL_2), 0.001)
        assertEquals(64.0125, values.getValue(CanSignal.WHEEL_3), 0.001)
        assertEquals(64.0125, values.getValue(CanSignal.WHEEL_4), 0.001)
    }

    @Test
    fun decodesHandbrakeAndCoolant() {
        assertEquals(0.0, decode("51402007700280B0780").getValue(CanSignal.HANDBRAKE))
        assertEquals(1.0, decode("51406007706280B0D00").getValue(CanSignal.HANDBRAKE))
        assertEquals(33.0, decode("600009B0549BD2202FF").getValue(CanSignal.COOLANT))
        assertEquals(79.0, decode("60000130177E32202FF").getValue(CanSignal.COOLANT))
    }

    @Test
    fun ignoresShortFramesAndUnknownIds() {
        assertEquals(emptyMap(), decode("4100102", "7E80300"))
    }
}
