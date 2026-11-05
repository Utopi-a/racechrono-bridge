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

    /** [optional] のコマンドは、受け付けられなくても記録だけして先へ進む（無くても通信できる調整用の設定）。 */
    fun configure(commands: List<String>, optional: Set<String> = emptySet()) {
        commands.forEach { command ->
            val raw = client.sendCommand(command, timeoutMillis = 4_000)
            onRawResponse("$command -> ${raw.trim()}")
            // ATZ はバージョン文字列を返す。それ以外の AT は OK が返らなければ失敗とみなす
            // （遅れて届いた SSM2 の応答を AT の応答と取り違えて先へ進まないため）
            val acknowledged = !parser.isElmError(raw) && (command == "ATZ" || "OK" in raw.uppercase())
            when {
                acknowledged -> Unit
                command in optional -> onLog("ELM327 did not accept optional $command (${raw.trim()}); continuing.")
                parser.isElmError(raw) -> error("ELM command failed: $command response=${raw.trim()}")
                else -> error("ELM command was not acknowledged: $command response=${raw.trim()}")
            }
        }
    }

    /**
     * SSM2 over K-line（ISO 14230、4800 bps）に切り替える。CAN に戻すときは [initialize]（ATZ で設定ごと戻す）。
     *
     * 手順は FreeSSM の ELM327 向け実装（ATcommandControlledDiagInterface::connect）に合わせた:
     * - ヘッダー 80 10 F0 の 1 バイト目の下の桁が 0 なので、ELM327 は 4 バイト目に長さを入れる。SSM2 の書式そのもので、
     *   チェックサム（合計の下位 1 バイト）も ELM327 が付ける
     * - SSM2 には初期化も接続維持のメッセージも無いので、ATBI で初期化を飛ばし、ATSW00 で ELM327 の維持用メッセージを止める
     * - ATBI はプロトコルを決めた後に送る
     */
    fun switchToKLine() {
        configure(KLINE_COMMANDS, optional = KLINE_OPTIONAL_COMMANDS)
        onLog("ELM327 switched to SSM2 over K-line (ISO 14230, 4800 baud).")
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
        private val KLINE_COMMANDS = listOf(
            "ATH0", "ATAL", "ATIB48", "ATSH8010F0", "ATAR", "ATSP5", "ATKW0", "ATSW00", "ATSTFA", "ATAT1", "ATBI",
        )

        /**
         * 無くても通信できる設定。長い受信（ATAL）は ELM327 v1.2 以降では受信側は既定で通る。受信アドレス（ATAR）・
         * キーワード確認（ATKW0）・維持用メッセージ（ATSW00）・待ち時間（ATSTFA・ATAT1）は互換品で受け付けないことがある。
         */
        private val KLINE_OPTIONAL_COMMANDS = setOf("ATAL", "ATAR", "ATKW0", "ATSW00", "ATSTFA", "ATAT1")

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
