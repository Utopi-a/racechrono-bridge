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
    val timestampMillis: Long,
) {
    companion object {
        val EMPTY = SubaruTelemetry(
            rpm = 0.0,
            boostKpa = 0.0,
            coolantC = 0.0,
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
        step += 1
        return SubaruTelemetry(
            rpm = rpm.coerceAtLeast(0.0),
            boostKpa = boostKpa,
            coolantC = coolantC,
            timestampMillis = System.currentTimeMillis(),
        )
    }
}
