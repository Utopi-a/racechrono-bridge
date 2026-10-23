package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.ssm2.catalog.SsmConversion
import com.utopia.racechronobridge.ssm2.catalog.SsmParameter
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
    val inputs: List<String>,
) {
    /** 開弁率 = パルス幅 (ms, 無効噴射時間込み) × rpm ÷ 1200。2 回転で 1 噴射。 */
    INJECTOR_DUTY("Injector Duty Cycle (Calculated) (%)", 1, listOf("P21", "P8")) {
        override fun compute(v: Map<String, Double>): Double? {
            val rpm = v.getValue("P8")
            return if (rpm > 0) v.getValue("P21") * rpm / 1200.0 else null
        }
    },

    /** RomRaider の P200 と同じ式: MAF (g/s) × 60 ÷ rpm。 */
    ENGINE_LOAD("Engine Load (Calculated) (g/rev)", 2, listOf("P12", "P8")) {
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
                if (d.inputs.all { it in values }) d.compute(values)?.let { put(d, it) }
            }
        }
        return LogRecord(elapsedMillis, values, derivedValues)
    }

    companion object {
        fun resolve(preset: LogPreset, parameters: Map<String, SsmParameter>): LogLayout {
            val missing = mutableListOf<String>()
            val columns = preset.parameters.mapNotNull { ref ->
                val p = parameters[ref.id] ?: run { missing += ref.id; return@mapNotNull null }
                val conversion = ref.units?.let { u ->
                    p.conversions.firstOrNull { it.units.equals(u, ignoreCase = true) }
                        ?: throw IllegalArgumentException("${ref.id} has no '$u' conversion: ${p.conversions.map { it.units }}")
                } ?: p.conversion()
                LogColumn(p, conversion, ref.slow)
            }
            val ids = columns.map { it.id }.toSet()
            // 計算列は既定の換算（P21 = ms、P8 = rpm、P12 = g/s）を前提にしている
            val derived = DerivedColumn.entries.filter { d ->
                d.inputs.all { id -> id in ids && columns.first { it.id == id }.conversion == columns.first { it.id == id }.parameter.conversion() }
            }
            return LogLayout(preset, columns, derived, missing)
        }
    }
}
