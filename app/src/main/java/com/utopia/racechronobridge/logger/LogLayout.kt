package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.ssm2.catalog.SsmConversion
import com.utopia.racechronobridge.ssm2.catalog.SsmParameter
import com.utopia.racechronobridge.ssm2.catalog.SsmParameterKind
import java.util.Locale

data class LogColumn(
    val parameter: SsmParameter,
    val conversion: SsmConversion,
    val slow: Boolean,
) {
    val id: String get() = parameter.id
    val header: String = parameter.columnName(conversion)
}

/** 記録した値から計算する列。入力が揃っているプリセットにだけ付く。 */
enum class DerivedColumn(
    val header: String,
    private val decimals: Int,
    /** 必要な入力。内側のリストは代わりが効く候補で、どれか 1 つあればよい。 */
    val inputs: List<List<String>>,
) {
    /** 開弁率 = パルス幅 (ms, 無効噴射時間込み) × rpm ÷ 1200。2 回転で 1 噴射。 */
    INJECTOR_DUTY("Injector Duty Cycle (Calculated) (%)", 1, listOf(listOf("P21"), listOf("P8"))) {
        override fun compute(v: Map<String, Double>): Double? {
            val rpm = v.getValue("P8")
            return if (rpm > 0) v.getValue("P21") * rpm / 1200.0 else null
        }
    },

    /**
     * 過給（大気圧基準）= MAP 絶対圧 − 大気圧 P24。RomRaider の列名と同じ前方一致で解析ツールが読める。
     * MAP は E88（2 バイト、RAM）、読めなければ標準の P7（1 バイト、255 kPa 絶対まで）。
     * どちらも P25 と違い +127 kPa で頭打ちにならない。
     */
    MANIFOLD_RELATIVE("Manifold Relative Pressure (Calculated) (kPa)", 1, listOf(listOf("E88", "P7"), listOf("P24"))) {
        override fun compute(v: Map<String, Double>): Double? {
            val map = v["E88"] ?: v["P7"] ?: return null
            return map - v.getValue("P24")
        }
    },

    /** 目標ブースト（大気圧基準）= 目標 E90（絶対圧）− 大気圧 P24。実測の過給と同じ基準で比べられる。 */
    TARGET_BOOST_RELATIVE("Target Boost Relative (Calculated) (kPa relative)", 1, listOf(listOf("E90"), listOf("P24"))) {
        override fun compute(v: Map<String, Double>): Double = v.getValue("E90") - v.getValue("P24")
    },

    /** RomRaider の P200 と同じ式: MAF (g/s) × 60 ÷ rpm。 */
    ENGINE_LOAD("Engine Load (Calculated) (g/rev)", 2, listOf(listOf("P12"), listOf("P8"))) {
        override fun compute(v: Map<String, Double>): Double? {
            val rpm = v.getValue("P8")
            return if (rpm > 300) v.getValue("P12") * 60.0 / rpm else null
        }
    },
    ;

    abstract fun compute(v: Map<String, Double>): Double?

    fun format(value: Double): String = String.format(Locale.US, "%.${decimals}f", value)
}

data class LogRecord(
    /** 記録開始からの経過時間。RomRaider の `Time (msec)` 列と同じ意味。 */
    val elapsedMillis: Long,
    /** パラメータ ID → 物理値（プリセットで選んだ換算）。 */
    val values: Map<String, Double>,
    val derived: Map<DerivedColumn, Double>,
)

/** プリセットを、この ECU で読める列の並びに解決したもの。 */
class LogLayout private constructor(
    val preset: LogPreset,
    val columns: List<LogColumn>,
    val derived: List<DerivedColumn>,
    /** この ECU のカタログに無かったパラメータ ID。 */
    val missing: List<String>,
    /** RAM が読めないため、代わりの標準パラメータにしたもの（元の ID → 代わりの ID）。 */
    val substituted: Map<String, String> = emptyMap(),
) {
    val fastAddresses: Set<Int> = columns.filterNot { it.slow }.flatMap { it.parameter.byteAddresses }.toSet()
    val slowAddresses: Set<Int> = columns.filter { it.slow }.flatMap { it.parameter.byteAddresses }.toSet()

    val headers: List<String> = columns.map { it.header } + derived.map { it.header }

    /** [bytes] に揃っている列だけを値にする。揃っていない列は記録しない（前回値を使うのは呼び出し側の判断）。 */
    fun decode(bytes: Map<Int, Int>, elapsedMillis: Long): LogRecord {
        val values = buildMap {
            columns.forEach { c ->
                val raw = c.parameter.byteAddresses.map { bytes[it] ?: return@forEach }
                put(c.id, c.parameter.decode(raw, c.conversion))
            }
        }
        val derivedValues = buildMap {
            derived.forEach { d ->
                if (d.inputs.all { group -> group.any { it in values } }) d.compute(values)?.let { put(d, it) }
            }
        }
        return LogRecord(elapsedMillis, values, derivedValues)
    }

    companion object {
        /**
         * [ramReadable] が false（ECU が RAM の読み出しを拒否する）なら、拡張パラメータは [LogParameterRef.fallback] の
         * 標準パラメータに置き換える。代わりが無いものは [missing] に入る。
         */
        fun resolve(preset: LogPreset, parameters: Map<String, SsmParameter>, ramReadable: Boolean = true): LogLayout {
            val missing = mutableListOf<String>()
            val substituted = linkedMapOf<String, String>()
            val columns = preset.parameters.mapNotNull { requested ->
                var ref = requested
                var p = parameters[ref.id]
                if (p != null && !ramReadable && p.kind == SsmParameterKind.EXTENDED) {
                    val fallback = ref.fallback?.let { f -> parameters[f.id]?.let { f to it } }
                    if (fallback == null) {
                        missing += ref.id
                        return@mapNotNull null
                    }
                    substituted[ref.id] = fallback.first.id
                    ref = fallback.first
                    p = fallback.second
                }
                if (p == null) {
                    missing += ref.id
                    return@mapNotNull null
                }
                val conversion = ref.units?.let { u ->
                    p.conversions.firstOrNull { it.units.equals(u, ignoreCase = true) }
                        ?: throw IllegalArgumentException("${ref.id} has no '$u' conversion: ${p.conversions.map { it.units }}")
                } ?: p.conversion()
                LogColumn(p, conversion, ref.slow)
            }
            // 計算列は既定の換算（P21 = ms、P8 = rpm、P12 = g/s、P7/E88 = kPa 絶対）を前提にしている
            val defaultUnits = columns.filter { it.conversion == it.parameter.conversion() }.map { it.id }.toSet()
            val derived = DerivedColumn.entries.filter { d -> d.inputs.all { group -> group.any { it in defaultUnits } } }
            return LogLayout(preset, columns, derived, missing, substituted)
        }
    }
}
