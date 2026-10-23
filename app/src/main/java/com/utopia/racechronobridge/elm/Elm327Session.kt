package com.utopia.racechronobridge.elm

import com.utopia.racechronobridge.ssm2.Ssm2Parameter

class Elm327Session(
    private val client: Elm327Transport,
    private val onRawResponse: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val parser = ElmResponseParser()

    fun initialize() {
        configure(INITIAL_COMMANDS)
        onLog("ELM327 initialized for SSM2 over CAN.")
    }

    fun configure(commands: List<String>) {
        commands.forEach { command ->
            val raw = client.sendCommand(command, timeoutMillis = 4_000)
            onRawResponse("$command -> ${raw.trim()}")
            if (parser.isElmError(raw)) {
                error("ELM command failed: $command response=${raw.trim()}")
            }
            // ATZ はバージョン文字列を返す。それ以外の AT は OK が返らなければ失敗とみなす
            // （遅れて届いた SSM2 の応答を AT の応答と取り違えて先へ進まないため）
            if (command != "ATZ" && "OK" !in raw.uppercase()) {
                error("ELM command was not acknowledged: $command response=${raw.trim()}")
            }
        }
    }

    fun readByte(parameter: Ssm2Parameter): Int {
        return readByte(command = parameter.command, label = parameter.name)
    }

    fun readByte(command: String, label: String): Int {
        val raw = client.sendCommand(command)
        onRawResponse("$command -> ${raw.trim()}")
        return when (val response = parser.parseSsm2Data(raw = raw, echoCommand = command)) {
            is ElmSsm2Response.Data -> response.dataBytes.firstOrNull()
                ?: error("SSM2 response has no data byte: ${raw.trim()}")

            is ElmSsm2Response.Error -> error("SSM2 $label failed: ${response.reason}")
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
