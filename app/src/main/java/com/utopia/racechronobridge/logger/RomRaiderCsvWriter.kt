package com.utopia.racechronobridge.logger

import java.io.Writer

/**
 * RomRaider Logger と同じ形の CSV を書く。1 行目が `Time (msec),<名前> (<単位>),...`。
 * gh8-ecu-tuning の解析ツールは列名の前方一致で列を探すので、列名は RomRaider の表記に合わせる。
 * 値が無い列（まだ一度も読めていない項目）は空欄にする。
 */
class RomRaiderCsvWriter(
    private val out: Writer,
    private val layout: LogLayout,
) {
    fun writeHeader() {
        out.write((listOf(TIME_HEADER) + layout.headers).joinToString(",") { it.toCsvCell() })
        out.write("\n")
    }

    fun write(record: LogRecord) {
        val cells = ArrayList<String>(layout.headers.size + 1)
        cells += record.elapsedMillis.toString()
        layout.columns.forEach { c -> cells += record.values[c.id]?.let(c.conversion::formatValue).orEmpty() }
        layout.derived.forEach { d -> cells += record.derived[d]?.let(d::format).orEmpty() }
        out.write(cells.joinToString(","))
        out.write("\n")
    }

    fun flush() = out.flush()

    fun close() = out.close()

    companion object {
        const val TIME_HEADER = "Time (msec)"
    }
}

private fun String.toCsvCell(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${replace("\"", "\"\"")}\"" else this
