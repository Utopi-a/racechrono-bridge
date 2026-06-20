package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Session

class Ssm2Reader(
    private val elmSession: Elm327Session,
) {
    fun readMvpTelemetry(): SubaruTelemetry {
        val coolant = elmSession.readByte(Ssm2Parameter.COOLANT)
        val boost = elmSession.readByte(Ssm2Parameter.BOOST)
        val rpmHigh = elmSession.readByte(Ssm2Parameter.RPM_HIGH)
        val rpmLow = elmSession.readByte(Ssm2Parameter.RPM_LOW)
        return SubaruRawTelemetry(
            coolantRaw = coolant,
            boostRaw = boost,
            rpmHigh = rpmHigh,
            rpmLow = rpmLow,
        ).toTelemetry()
    }
}
