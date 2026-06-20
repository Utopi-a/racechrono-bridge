package com.utopia.racechronobridge.elm

import com.utopia.racechronobridge.ssm2.Ssm2Parameter

class Elm327Session(
    private val client: Elm327Transport,
    private val onRawResponse: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val parser = ElmResponseParser()

    fun initialize() {
        INITIAL_COMMANDS.forEach { command ->
            val raw = client.sendCommand(command, timeoutMillis = 4_000)
            onRawResponse("$command -> ${raw.trim()}")
            if (parser.isElmError(raw)) {
                error("ELM command failed: $command response=${raw.trim()}")
            }
        }
        onLog("ELM327 initialized for SSM2 over CAN.")
    }

    fun readByte(parameter: Ssm2Parameter): Int {
        val raw = client.sendCommand(parameter.command)
        onRawResponse("${parameter.command} -> ${raw.trim()}")
        return when (val response = parser.parseSsm2Data(raw = raw, echoCommand = parameter.command)) {
            is ElmSsm2Response.Data -> response.dataBytes.firstOrNull()
                ?: error("SSM2 response has no data byte: ${raw.trim()}")

            is ElmSsm2Response.Error -> error("SSM2 ${parameter.name} failed: ${response.reason}")
        }
    }

    companion object {
        private val INITIAL_COMMANDS = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH0",
            "ATSP6",
            "ATCAF1",
            "ATSH7E0",
        )
    }
}
