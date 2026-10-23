package com.utopia.racechronobridge.logger

/**
 * 記録する SSM パラメータ。[units] を指定すると RomRaider の既定以外の換算で記録する。
 * [slow] は温度など変化の遅い項目で、[LoggerSession.SLOW_EVERY] 回に 1 回だけ読む。
 */
data class LogParameterRef(
    val id: String,
    val units: String? = null,
    val slow: Boolean = false,
)

data class LogPreset(
    val key: String,
    val title: String,
    val purpose: String,
    val parameters: List<LogParameterRef>,
)

/**
 * 計測計画（gh8-ecu-tuning の analysis/step3_logging_plan.md）のセッション A〜F。
 *
 * GH8（ECU ID 5104584007）の 1〜2 バイト版拡張パラメータは RAM 0xFF708C〜0xFF70CB にまとまっていて、
 * ブロック読み出し 1 回で取れる。標準パラメータは 1 バイトずつ読むので、同じ値が拡張側にあれば拡張側を使う
 * （スロットル E93、A/F E92 など）。
 */
object LogPresets {
    private val rpm = LogParameterRef("P8")
    private val iat = LogParameterRef("P11", slow = true)
    private val coolant = LogParameterRef("P2", slow = true)
    private val throttle = LogParameterRef("E93")
    private val boost = LogParameterRef("P25")
    private val load = LogParameterRef("E87")
    private val timing = LogParameterRef("P10")
    private val fbkc = LogParameterRef("E94")
    private val flkc = LogParameterRef("E95")
    private val iam = LogParameterRef("E96")

    val FUEL = LogPreset(
        key = "A",
        title = "燃料",
        purpose = "開弁率と全開時の燃料。純正 A/F センサーは、目標より薄くなったかの確認に使う",
        parameters = listOf(
            rpm, LogParameterRef("P12"), LogParameterRef("P21"), load, LogParameterRef("E124", units = "estimated AFR"),
            boost, throttle, LogParameterRef("E92", units = "estimated AFR"), iat, coolant,
        ),
    )

    val KNOCK = LogPreset(
        key = "B",
        title = "ノック",
        purpose = "点火時期とノック補正。踏み直しやシフト直後の動きを見る",
        parameters = listOf(rpm, load, timing, fbkc, flkc, iam, boost, throttle, LogParameterRef("P60"), iat),
    )

    val BOOST = LogPreset(
        key = "C",
        title = "過給",
        purpose = "過給の跳ね（オーバーシュート）と WGDC・ターボダイナミクス",
        parameters = listOf(
            rpm, boost, LogParameterRef("E90", units = "bar relative sea level"), LogParameterRef("P36"),
            LogParameterRef("E78"), LogParameterRef("E79"), LogParameterRef("E80"), throttle, iat,
        ),
    )

    val CRUISE = LogPreset(
        key = "D",
        title = "巡航",
        purpose = "閉ループの燃料補正と学習（MAF のずれ）、巡航時の点火",
        parameters = listOf(
            rpm, LogParameterRef("P9"), load, LogParameterRef("P18"), LogParameterRef("E82"), LogParameterRef("E83"),
            LogParameterRef("E61"), LogParameterRef("E33"), timing, coolant,
        ),
    )

    val CIRCUIT = LogPreset(
        key = "E",
        title = "サーキット",
        purpose = "周回中の温度の上がり方と、そのときの点火・ノック・過給",
        parameters = listOf(rpm, iat, coolant, boost, timing, fbkc, flkc, iam, throttle, LogParameterRef("P36")),
    )

    val SHIFT = LogPreset(
        key = "F",
        title = "シフト",
        purpose = "シフトポイント用。回転・車速・スロットルだけに絞って速く取る",
        parameters = listOf(rpm, LogParameterRef("P9"), throttle, iat),
    )

    val all: List<LogPreset> = listOf(FUEL, KNOCK, BOOST, CRUISE, CIRCUIT, SHIFT)

    fun byKey(key: String): LogPreset? = all.firstOrNull { it.key == key }
}
