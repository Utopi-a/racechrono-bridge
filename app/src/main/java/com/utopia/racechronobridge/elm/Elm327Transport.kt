package com.utopia.racechronobridge.elm

interface Elm327Transport {
    fun sendCommand(command: String, timeoutMillis: Long = 2_000): String
    fun close()
}
