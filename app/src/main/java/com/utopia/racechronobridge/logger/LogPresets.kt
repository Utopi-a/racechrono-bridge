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
 * （負荷 E87、MAP E88、A/F E92 など）。
 */
object LogPresets {
    private val rpm = LogParameterRef("P8")
    private val iat = LogParameterRef("P11", slow = true)
    private val coolant = LogParameterRef("P2", slow = true)
    private val atmosphere = LogParameterRef("P24", slow = true)

    /** 全開の判定に使う。E93（スロットル開度の拡張版）は全開で 100% を示す保証がないので、2 月のログで 100% に届いた P13 を使う。 */
    private val throttle = LogParameterRef("P13")

    /**
     * 過給は E88（MAP 絶対圧、ブロック内）から大気圧 P24 を引いて求める（[DerivedColumn.MANIFOLD_RELATIVE]）。
     * P25 は 1 バイトで +127 kPa で頭打ちになり、1.2 bar 前後の跳ねを測れないため使わない。
     */
    private val map = LogParameterRef("E88")
    private val load = LogParameterRef("E87")
    private val timing = LogParameterRef("P10")
    private val fbkc = LogParameterRef("E94")
    private val flkc = LogParameterRef("E95")
    private val iam = LogParameterRef("E96")

    val FUEL = LogPreset(
        key = "A",
        title = "燃料",
        purpose = "開弁率と全開時の燃料。純正 A/F センサーは、目標より薄くなったかの確認に使う（E92 と P58 を並べて張り付き値を確かめる）",
        parameters = listOf(
            rpm, LogParameterRef("P12"), LogParameterRef("P21"), load, LogParameterRef("E124", units = "estimated AFR"),
            map, throttle, LogParameterRef("E92", units = "estimated AFR"), LogParameterRef("P58", units = "AFR"),
            atmosphere, iat, coolant,
        ),
    )

    val KNOCK = LogPreset(
        key = "B",
        title = "ノック",
        purpose = "点火時期とノック補正。踏み直しやシフト直後の動きを見る",
        parameters = listOf(rpm, load, timing, fbkc, flkc, iam, map, throttle, LogParameterRef("P60"), atmosphere, iat),
    )

    val BOOST = LogPreset(
        key = "C",
        title = "過給",
        purpose = "過給の跳ね（オーバーシュート）と WGDC・ターボダイナミクス。実測と目標を同じ大気圧基準で並べる",
        parameters = listOf(
            rpm, map, LogParameterRef("E90"), LogParameterRef("P36"),
            LogParameterRef("E78"), LogParameterRef("E79"), LogParameterRef("E80"), throttle, atmosphere, iat,
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
        parameters = listOf(rpm, iat, coolant, map, timing, fbkc, flkc, iam, throttle, LogParameterRef("P36"), atmosphere),
    )

    val SHIFT = LogPreset(
        key = "F",
        title = "シフト",
        purpose = "シフトポイント用。回転・車速・スロットル・過給に絞って速く取る",
        parameters = listOf(rpm, LogParameterRef("P9"), throttle, map, atmosphere, iat),
    )

    val all: List<LogPreset> = listOf(FUEL, KNOCK, BOOST, CRUISE, CIRCUIT, SHIFT)

    fun byKey(key: String): LogPreset? = all.firstOrNull { it.key == key }
}
