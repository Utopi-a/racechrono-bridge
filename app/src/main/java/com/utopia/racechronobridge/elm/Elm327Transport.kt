package com.utopia.racechronobridge.elm

interface Elm327Transport {
    fun sendCommand(command: String, timeoutMillis: Long = 2_000): String

    /** Writes without waiting for the `>` prompt, for streaming commands such as ATMA. */
    fun writeRaw(text: String)

    /** Returns text received since the last read, waiting up to [timeoutMillis]; empty on timeout. */
    fun readAvailable(timeoutMillis: Long): String

    fun close()
}
