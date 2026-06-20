package com.utopia.racechronobridge.elm

sealed class ElmSsm2Response {
    data class Data(
        val allBytes: List<Int>,
        val dataBytes: List<Int>,
    ) : ElmSsm2Response()

    data class Error(
        val reason: String,
    ) : ElmSsm2Response()
}

class ElmResponseParser {
    fun parseSsm2Data(raw: String, echoCommand: String? = null): ElmSsm2Response {
        val errorReason = errorReason(raw)
        if (errorReason != null) {
            return ElmSsm2Response.Error(errorReason)
        }

        val bytes = parseHexBytes(raw, echoCommand)
        val markerIndex = bytes.indexOf(SSM2_RESPONSE_MARKER)
        if (markerIndex < 0) {
            return ElmSsm2Response.Error("missing E8 response marker in ${raw.trim()}")
        }
        if (markerIndex == bytes.lastIndex) {
            return ElmSsm2Response.Error("missing SSM2 data byte after E8 in ${raw.trim()}")
        }
        return ElmSsm2Response.Data(
            allBytes = bytes,
            dataBytes = bytes.drop(markerIndex + 1),
        )
    }

    fun isElmError(raw: String): Boolean = errorReason(raw) != null

    private fun errorReason(raw: String): String? {
        val normalized = raw.uppercase()
        return when {
            "NO DATA" in normalized -> "NO DATA"
            "STOPPED" in normalized -> "STOPPED"
            "SEARCHING" in normalized -> "SEARCHING"
            "?" in normalized -> "?"
            else -> null
        }
    }

    private fun parseHexBytes(raw: String, echoCommand: String?): List<Int> {
        val normalizedEcho = echoCommand?.filter { it.isLetterOrDigit() }?.uppercase()
        return HEX_TOKEN_REGEX.findAll(raw.uppercase())
            .map { it.value }
            .filterNot { token -> token == normalizedEcho }
            .filterNot { token -> token.length == 3 && token.startsWith("7E") }
            .flatMap { token ->
                if (token.length % 2 == 0) {
                    token.chunked(2).mapNotNull { it.toIntOrNull(radix = 16) }
                } else {
                    emptyList()
                }
            }
            .toList()
    }

    companion object {
        private const val SSM2_RESPONSE_MARKER = 0xE8
        private val HEX_TOKEN_REGEX = Regex("[0-9A-F]+")
    }
}
