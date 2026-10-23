package com.utopia.racechronobridge.elm

sealed class ElmSsm2Response {
    data class Data(
        val allBytes: List<Int>,
        val dataBytes: List<Int>,
    ) : ElmSsm2Response()

    data class Error(
        val reason: String,
        /** ECU が要求を拒否した（応答コード 7F）。アダプターや通信の失敗とは区別する。 */
        val rejected: Boolean = false,
    ) : ElmSsm2Response()
}

class ElmResponseParser {
    /** A8 アドレス読み出しの応答（`E8` + データ）。既存の 1 バイト読み出しが使う。応答コードの位置は問わない（従来どおり）。 */
    fun parseSsm2Data(raw: String, echoCommand: String? = null): ElmSsm2Response =
        parseSsm2Response(raw = raw, echoCommand = echoCommand, marker = SSM2_ADDRESS_READ_MARKER, strictMarker = false)

    /**
     * SSM2 の応答から、応答コード [marker]（A8 → E8、A0 → E0、AA → EA）より後ろのデータを取り出す。
     *
     * ELM327 の出力は次のどれでも受け付ける:
     * - 単一フレーム: `E8 7F`、先頭に長さバイトが付いた `04 E8 3D`、ヘッダー付きの `7E8 04 E8 7F`
     * - 複数フレーム（ATCAF1）: 1 行目にバイト数（16 進 3 桁）、以降 `0:` `1:` … の行
     *
     * [strictMarker] が true なら、応答コードは先頭か、先頭 1 バイト（長さ）の直後にしか認めない。
     * データの途中に同じ値があっても応答コードとみなさないためで、遅れて届いた別の要求の応答を取り違えないようにする。
     * エラー表示の行（`<DATA ERROR`、`BUS BUSY` など）が 1 行でもあれば、データがあっても全体を失敗にする。
     */
    fun parseSsm2Response(raw: String, echoCommand: String?, marker: Int, strictMarker: Boolean = true): ElmSsm2Response {
        errorReason(raw)?.let { return ElmSsm2Response.Error(it) }
        val bytes = payloadBytes(raw, echoCommand)
        val shortLengthPrefixed = bytes.size <= SINGLE_FRAME_MAX_BYTES && bytes.firstOrNull() == bytes.size - 1
        if (bytes.firstOrNull() == SSM2_REJECTED || (shortLengthPrefixed && bytes.getOrNull(1) == SSM2_REJECTED)) {
            return ElmSsm2Response.Error("ECU rejected request (7F) in ${raw.trim()}", rejected = true)
        }
        val markerIndex = if (strictMarker) {
            when {
                bytes.firstOrNull() == marker -> 0
                bytes.getOrNull(1) == marker -> 1
                else -> -1
            }
        } else {
            bytes.indexOf(marker)
        }
        if (markerIndex < 0) {
            return ElmSsm2Response.Error("missing ${"%02X".format(marker)} response marker in ${raw.trim()}")
        }
        if (markerIndex == bytes.lastIndex) {
            return ElmSsm2Response.Error("missing SSM2 data byte after ${"%02X".format(marker)} in ${raw.trim()}")
        }
        return ElmSsm2Response.Data(
            allBytes = bytes,
            dataBytes = bytes.drop(markerIndex + 1),
        )
    }

    fun isElmError(raw: String): Boolean = errorReason(raw) != null

    private fun errorReason(raw: String): String? {
        val lines = raw.uppercase().split('\r', '\n').map { it.replace(">", "").trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            ERROR_KEYWORDS.firstOrNull { it in line }?.let { return it }
            if (line.startsWith("<") || "ERROR" in line || "ALERT" in line || ERR_CODE.containsMatchIn(line)) return line
            if ("?" in line) return "?"
        }
        return null
    }

    private fun payloadBytes(raw: String, echoCommand: String?): List<Int> {
        val normalizedEcho = echoCommand?.filter { it.isLetterOrDigit() }?.uppercase()
        val lines = raw.uppercase()
            .split('\r', '\n')
            .map { it.replace(">", "").trim() }
            .filter { it.isNotEmpty() && it.filter(Char::isLetterOrDigit) != normalizedEcho }
        val indexed = lines.filter { MULTI_FRAME_LINE.matches(it) }
        if (indexed.isNotEmpty()) {
            // 複数フレーム: ELM327 が ISO-TP を組み立てた結果。行番号は 0-F で一周するので出現順に連結する
            val count = lines.firstOrNull { BYTE_COUNT_LINE.matches(it) }?.toInt(radix = 16)
            val bytes = indexed.flatMap { line -> hexBytes(line.substringAfter(':')) }
            return if (count != null) bytes.take(count) else bytes
        }
        // 16 進と空白だけの行からだけバイトを取る（英単語の DA・FB などをバイトと取り違えない）
        return lines.filter { HEX_LINE.matches(it) }
            .flatMap { line -> HEX_TOKEN_REGEX.findAll(line).map { it.value }.toList() }
            .filterNot { token -> token == normalizedEcho }
            .filterNot { token -> token.length == 3 && token.startsWith("7E") }
            .flatMap { token -> if (token.length % 2 == 0) hexBytes(token) else emptyList() }
    }

    private fun hexBytes(text: String): List<Int> =
        text.filter { it.isLetterOrDigit() }.chunked(2).mapNotNull { if (it.length == 2) it.toIntOrNull(radix = 16) else null }

    companion object {
        const val SSM2_ADDRESS_READ_MARKER = 0xE8
        const val SSM2_BLOCK_READ_MARKER = 0xE0
        const val SSM2_IDENTIFY_MARKER = 0xEA
        private const val SSM2_REJECTED = 0x7F

        /** CAN の単一フレームのデータは最大 7 バイト。長さバイト付きでも 8 バイトまで。 */
        private const val SINGLE_FRAME_MAX_BYTES = 8
        private val ERROR_KEYWORDS = listOf(
            "NO DATA", "STOPPED", "SEARCHING", "BUFFER FULL", "CAN ERROR", "BUS BUSY", "BUS ERROR",
            "UNABLE TO CONNECT", "DATA ERROR", "RX ERROR", "FB ERROR", "LV RESET",
        )
        private val ERR_CODE = Regex("^ERR\\d+")
        private val HEX_TOKEN_REGEX = Regex("[0-9A-F]+")
        private val HEX_LINE = Regex("^[0-9A-F ]+$")
        private val MULTI_FRAME_LINE = Regex("^[0-9A-F]:[0-9A-F ]*$")
        private val BYTE_COUNT_LINE = Regex("^[0-9A-F]{3}$")
    }
}
