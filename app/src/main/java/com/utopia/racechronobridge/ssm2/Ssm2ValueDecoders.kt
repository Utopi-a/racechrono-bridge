package com.utopia.racechronobridge.ssm2

internal object Ssm2ValueDecoders {
    fun unsigned16(high: Int, low: Int): Int = (high shl 8) + low

    fun percent(raw: Int): Double = raw * 100.0 / 255.0

    fun temperatureC(raw: Int): Double = raw - 40.0

    fun relativePressureKpa(raw: Int): Double = (raw - 128.0) * 37.0 / 255.0 * 6.89476

    fun signedHalfDegree(raw: Int): Double = (raw - 128.0) / 2.0

    fun gearPosition(raw: Int): Int = if (raw in 0..7) raw + 1 else 0

    fun signedValue(raw: Int, bytes: Int): Int {
        val signBit = 1 shl (bytes * 8 - 1)
        val valueRange = 1 shl (bytes * 8)
        return if (raw and signBit != 0) raw - valueRange else raw
    }
}
