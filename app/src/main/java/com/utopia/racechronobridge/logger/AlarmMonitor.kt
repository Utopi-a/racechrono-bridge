package com.utopia.racechronobridge.logger

import java.util.Locale

enum class AlarmKind(val label: String) {
    LEAN_AFR("A/F 薄い"),
    KNOCK("ノック補正"),
    OVERBOOST("過給超過"),
    INJECTOR_DUTY("開弁率"),
}

/**
 * 警報のしきい値。走行中に画面を見なくても気づけるように、決め打ちの条件で鳴らす（判断を人や AI に委ねない）。
 *
 * - [leanAfr]: 純正 A/F センサーは濃い側が 11.15 で張り付く。全開・過給中にこれを超えて動いたら、狙いより薄い
 * - [overboostKpa]: ブーストカット（約 137 kPa）の手前
 * - [knockDropDegrees]: FBKC が 1 サンプルでこれ以上下がったら、新しいノックの検知
 */
data class AlarmThresholds(
    val leanAfr: Double = 11.2,
    val wotThrottlePercent: Double = 90.0,
    val boostedKpa: Double = 50.0,
    val overboostKpa: Double = 125.0,
    val injectorDutyPercent: Double = 95.0,
    val knockDropDegrees: Double = 0.7,
)

data class AlarmEvent(
    val kind: AlarmKind,
    val message: String,
    val elapsedMillis: Long,
)

/**
 * 記録 1 件ごとに警報の条件を調べる。同じ種類の警報は [cooldownMillis] の間は繰り返さない。
 * プリセットに必要な列が無い条件は調べない（例: 過給のセッションに A/F は無い）。
 */
class AlarmMonitor(
    private val thresholds: AlarmThresholds = AlarmThresholds(),
    private val cooldownMillis: Long = 3_000,
) {
    private val lastFired = mutableMapOf<AlarmKind, Long>()
    private var previousFbkc: Double? = null
    private var leanStreak = 0

    fun evaluate(record: LogRecord): List<AlarmEvent> {
        val v = record.values
        val found = mutableListOf<Pair<AlarmKind, String>>()

        // 全開の判定は P13（E93 は全開で 100% を示す保証がない）。過給は頭打ちのない計算列（E88 − P24）を優先する
        val throttle = v["P13"] ?: v["E93"]
        val boost = record.derived[DerivedColumn.MANIFOLD_RELATIVE] ?: v["P25"]
        val afr = v["E92"]
        val underLoad = throttle != null && boost != null &&
            throttle >= thresholds.wotThrottlePercent && boost >= thresholds.boostedKpa
        // A/F は閉ループから開ループへの切り替え直後に遅れて濃くなるので、2 件続いたときだけ鳴らす
        leanStreak = if (underLoad && afr != null && afr > thresholds.leanAfr) leanStreak + 1 else 0
        if (leanStreak >= 2) found += AlarmKind.LEAN_AFR to "A/F ${fmt(afr!!, 2)}（全開・過給 ${fmt(boost!!, 0)} kPa）"

        val fbkc = v["E94"] ?: v["E39"]
        val previous = previousFbkc
        if (fbkc != null && previous != null && previous - fbkc >= thresholds.knockDropDegrees) {
            found += AlarmKind.KNOCK to "FBKC ${fmt(previous, 2)} → ${fmt(fbkc, 2)}°" + (v["P8"]?.let { " @ ${fmt(it, 0)} rpm" } ?: "")
        }
        previousFbkc = fbkc

        if (boost != null && boost > thresholds.overboostKpa) {
            found += AlarmKind.OVERBOOST to "過給 ${fmt(boost, 0)} kPa"
        }

        record.derived[DerivedColumn.INJECTOR_DUTY]?.let { duty ->
            if (duty >= thresholds.injectorDutyPercent) found += AlarmKind.INJECTOR_DUTY to "開弁率 ${fmt(duty, 1)}%"
        }

        return found.mapNotNull { (kind, message) ->
            val last = lastFired[kind]
            if (last != null && record.elapsedMillis - last < cooldownMillis) return@mapNotNull null
            lastFired[kind] = record.elapsedMillis
            AlarmEvent(kind, message, record.elapsedMillis)
        }
    }

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)
}
