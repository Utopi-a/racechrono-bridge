package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.ElmResponseParser

/**
 * ECU と SSM2 をやり取りする経路。読み出しの要求（A8・A0）は同じ書式で、
 * 包み方（CAN の PCI、K-line のヘッダー・長さ・チェックサム）は ELM327 が付ける。違うのは ECU 識別の要求だけ。
 *
 * GH8（ECU ID 5104584007）は 2026-10 の実車で、CAN では標準パラメータに応答し、RAM の読み出しは `7F A8 12` で拒否した。
 * 同じ車で RomRaider（既定は K-line）は拡張パラメータを記録できていたので、RAM は K-line で読む。
 */
enum class Ssm2Link(
    val label: String,
    val identifyCommand: String,
    val identifyMarker: Int,
) {
    CAN("CAN", identifyCommand = "AA", identifyMarker = ElmResponseParser.SSM2_IDENTIFY_MARKER),

    /** SSM2 over ISO 14230、4800 bps。ELM327 の設定は [com.utopia.racechronobridge.elm.Elm327Session.switchToKLine]。 */
    KLINE("K-line", identifyCommand = "BF", identifyMarker = ElmResponseParser.SSM2_KLINE_INIT_MARKER),
}
