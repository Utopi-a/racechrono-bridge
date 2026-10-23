package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Transport
import com.utopia.racechronobridge.elm.ElmResponseParser
import com.utopia.racechronobridge.elm.ElmSsm2Response
import java.util.Locale

open class Ssm2RequestFailed(message: String) : Exception(message)

/** ECU が要求を拒否した（応答コード 7F）。その ECU はこの読み方に対応していない。 */
class Ssm2RequestRejected(message: String) : Ssm2RequestFailed(message)

/** ECU 識別（CAN では `AA` → `EA`）の結果。 */
data class Ssm2Identification(
    /** `EA` の後ろ全体: システム ID 3 バイト + ROM ID 5 バイト + 対応フラグ。 */
    val bytes: List<Int>,
) {
    /** RomRaider の ECU ID（ROM ID の 5 バイトを 16 進で）。例: `5104584007`。 */
    val ecuId: String = bytes.drop(3).take(5).joinToString("") { "%02X".format(it) }
}

/**
 * SSM2 over CAN の要求を ELM327 で送る。
 *
 * ELM327 は 1 回の送信をデータ 7 バイトまでの単一フレームに限るため、
 * A8 は 1 アドレスずつ（5 バイト）、A0 はブロック 1 つ（6 バイト）で送る。
 * 応答は複数フレームでも ELM327 が組み立てて返す。
 * 書式は FreeSSM の SSMP2communication_core（ReadDataBlock / ReadMultipleDatabytes / GetCUdata）に合わせた。
 */
class Ssm2Client(
    private val transport: Elm327Transport,
    private val parser: ElmResponseParser = ElmResponseParser(),
) {
    fun identify(): Ssm2Identification {
        val data = request(command = "AA", marker = ElmResponseParser.SSM2_IDENTIFY_MARKER, timeoutMillis = 4_000)
        if (data.size < 8) throw Ssm2RequestFailed("identification response too short: ${data.size} bytes")
        return Ssm2Identification(data)
    }

    fun readAddress(address: Int): Int {
        require(address in 0..0xFFFFFF) { "address out of range: $address" }
        val command = "A800${hex(address, 6)}"
        val data = request(command = command, marker = ElmResponseParser.SSM2_ADDRESS_READ_MARKER)
        // 長さが合わない応答は、別の要求の応答が遅れて届いた可能性がある。値として使わない
        if (data.size != 1) throw Ssm2RequestFailed("$command returned ${data.size} bytes")
        return data.first()
    }

    fun readBlock(start: Int, count: Int): List<Int> {
        require(start in 0..0xFFFFFF && count in 1..MAX_BLOCK_BYTES && start + count - 1 <= 0xFFFFFF) {
            "invalid block start=$start count=$count"
        }
        val data = request(
            command = "A000${hex(start, 6)}${hex(count - 1, 2)}",
            marker = ElmResponseParser.SSM2_BLOCK_READ_MARKER,
        )
        if (data.size != count) throw Ssm2RequestFailed("block 0x${hex(start, 6)}+$count returned ${data.size} bytes")
        return data
    }

    /**
     * 失敗やタイムアウトの後に、要求と応答の対応を取り戻す。
     * 遅れて届く応答を読み捨て、AT コマンドに `OK` が返ることを確かめる。確かめられなければ例外。
     */
    fun resynchronize() {
        repeat(RESYNC_ATTEMPTS) { attempt ->
            drain()
            val raw = runCatching { transport.sendCommand("ATE0", 1_000) }.getOrDefault("")
            if ("OK" in raw.uppercase()) return
            if (attempt == RESYNC_ATTEMPTS - 1) throw Ssm2RequestFailed("adapter did not answer OK after resync: ${raw.trim()}")
        }
    }

    private fun drain() {
        val deadline = System.currentTimeMillis() + DRAIN_MAX_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (transport.readAvailable(DRAIN_QUIET_MILLIS).isEmpty()) return
        }
    }

    private fun request(command: String, marker: Int, timeoutMillis: Long = 2_000): List<Int> {
        val raw = transport.sendCommand(command, timeoutMillis)
        return when (val response = parser.parseSsm2Response(raw = raw, echoCommand = command, marker = marker)) {
            is ElmSsm2Response.Data -> response.dataBytes
            is ElmSsm2Response.Error ->
                if (response.rejected) throw Ssm2RequestRejected("$command: ${response.reason}")
                else throw Ssm2RequestFailed("$command: ${response.reason}")
        }
    }

    companion object {
        /** ISO15765 の要求の長さバイトの上限（FreeSSM と同じ）。 */
        const val MAX_BLOCK_BYTES = 256
        private const val RESYNC_ATTEMPTS = 3
        private const val DRAIN_QUIET_MILLIS = 150L
        private const val DRAIN_MAX_MILLIS = 2_000L

        private fun hex(value: Int, digits: Int) = value.toString(16).uppercase(Locale.US).padStart(digits, '0')
    }
}
