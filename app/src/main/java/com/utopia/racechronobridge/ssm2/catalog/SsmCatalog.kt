package com.utopia.racechronobridge.ssm2.catalog

import java.util.Locale

/** RomRaider の storagetype。属性が無い場合はアドレス長から符号なし整数とみなす（RomRaider と同じ扱い）。 */
enum class SsmStorage(val bytes: Int) {
    UINT8(1), INT8(1), UINT16(2), INT16(2), UINT32(4), INT32(4), FLOAT(4);

    /** ECU のデータは big endian。 */
    fun decode(bytes: List<Int>): Double {
        require(bytes.size == this.bytes) { "$name needs ${this.bytes} bytes, got ${bytes.size}" }
        val raw = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return when (this) {
            UINT8, UINT16, UINT32 -> raw.toDouble()
            INT8 -> raw.toInt().toByte().toDouble()
            INT16 -> raw.toInt().toShort().toDouble()
            INT32 -> raw.toInt().toDouble()
            FLOAT -> java.lang.Float.intBitsToFloat(raw.toInt()).toDouble()
        }
    }

    companion object {
        fun of(storagetype: String, totalBytes: Int): SsmStorage = when {
            storagetype.isNotBlank() -> valueOf(storagetype.uppercase(Locale.US))
            totalBytes == 1 -> UINT8
            totalBytes == 2 -> UINT16
            totalBytes == 4 -> UINT32
            else -> throw IllegalArgumentException("no storage type for $totalBytes bytes")
        }
    }
}

data class SsmConversion(
    val units: String,
    val expression: SsmExpression,
    val storage: SsmStorage,
    /** RomRaider の format（例: `0.00`）。小数点以下の桁数だけを使う。 */
    val format: String,
) {
    val decimals: Int = format.substringAfter('.', "").length

    fun formatValue(value: Double): String = String.format(Locale.US, "%.${decimals}f", value)
}

/** 標準パラメータが ECU で使えるかを、ECU 識別応答のフラグで判定するための位置。 */
data class SsmCapability(val byteIndex: Int, val bit: Int) {
    /**
     * [identification] は ECU 識別応答の `EA`（K-line では `FF`）の後ろ全体
     * （システム ID 3 バイト + ROM ID 5 バイト + 対応フラグ）。RomRaider の ecubyteindex はこの先頭からの位置。
     */
    fun isSupportedBy(identification: List<Int>): Boolean {
        val b = identification.getOrNull(byteIndex) ?: return false
        return b and (1 shl bit) != 0
    }
}

enum class SsmParameterKind { STANDARD, EXTENDED }

data class SsmParameter(
    val id: String,
    val name: String,
    val kind: SsmParameterKind,
    /** 拡張パラメータのときだけ。アドレスは ECU ごとに違う。 */
    val ecuId: String?,
    val addresses: List<Int>,
    val lengthPerAddress: Int,
    val capability: SsmCapability?,
    val conversions: List<SsmConversion>,
) {
    /** 読み出すバイトのアドレス（この順に連結して big endian で解釈する）。 */
    val byteAddresses: List<Int> = addresses.flatMap { start -> (0 until lengthPerAddress).map { start + it } }

    init {
        require(conversions.isNotEmpty()) { "$id has no conversion" }
        conversions.forEach { c ->
            require(c.storage.bytes == byteAddresses.size) { "$id ${c.units}: ${c.storage} does not fit ${byteAddresses.size} bytes" }
        }
    }

    /** [units] が一致する換算。無ければ RomRaider の既定（先頭）。 */
    fun conversion(units: String? = null): SsmConversion =
        units?.let { u -> conversions.firstOrNull { it.units.equals(u, ignoreCase = true) } } ?: conversions.first()

    fun decode(bytes: List<Int>, conversion: SsmConversion = conversions.first()): Double =
        conversion.expression.evaluate(conversion.storage.decode(bytes))

    /** RomRaider Logger の CSV と同じ列名。解析ツールはこの名前で列を探す。 */
    fun columnName(conversion: SsmConversion = conversions.first()): String = "$name (${conversion.units})"
}

class SsmCatalog(
    val source: String,
    private val parameters: List<SsmParameter>,
) {
    val standard: List<SsmParameter> = parameters.filter { it.kind == SsmParameterKind.STANDARD }

    fun ecuIds(): Set<String> = parameters.mapNotNull { it.ecuId }.toSet()

    /** その ECU で使えるパラメータ（標準 + その ECU ID の拡張）。 */
    fun forEcu(ecuId: String): Map<String, SsmParameter> =
        (standard + parameters.filter { it.ecuId == ecuId }).associateBy { it.id }

    companion object {
        private val REQUIRED_COLUMNS = listOf(
            "kind", "id", "name", "ecu_id", "addresses", "length", "capability_index", "capability_bit",
            "conv_index", "units", "storagetype", "expr", "format",
        )

        /** scripts/generate_ssm_catalog.py が出力する TSV を読む。 */
        fun parse(lines: Sequence<String>): SsmCatalog {
            var source = "unknown"
            var header: Map<String, Int>? = null
            val grouped = linkedMapOf<Triple<String, String, String>, MutableList<Map<String, String>>>()
            for (line in lines) {
                if (line.isBlank()) continue
                if (line.startsWith("#")) {
                    if (line.startsWith("# source:")) source = line.removePrefix("# source:").trim()
                    continue
                }
                val cells = line.split('\t')
                val h = header
                if (h == null) {
                    val names = cells.withIndex().associate { (i, n) -> n to i }
                    val missing = REQUIRED_COLUMNS.filterNot { it in names }
                    require(missing.isEmpty()) { "catalog header is missing $missing" }
                    header = names
                    continue
                }
                val row = h.mapValues { (_, i) -> cells.getOrElse(i) { "" } }
                grouped.getOrPut(Triple(row.getValue("kind"), row.getValue("id"), row.getValue("ecu_id"))) { mutableListOf() }.add(row)
            }
            require(header != null) { "catalog has no header" }
            return SsmCatalog(source, grouped.values.map(::toParameter))
        }

        private fun toParameter(rows: List<Map<String, String>>): SsmParameter {
            val first = rows.first()
            val addresses = first.getValue("addresses").split(';').map { it.removePrefix("0x").toInt(16) }
            val length = first.getValue("length").toInt()
            val totalBytes = addresses.size * length
            val capability = first.getValue("capability_index").toIntOrNull()?.let { index ->
                SsmCapability(index, first.getValue("capability_bit").toInt())
            }
            val conversions = rows.sortedBy { it.getValue("conv_index").toInt() }.map { r ->
                SsmConversion(
                    units = r.getValue("units"),
                    expression = SsmExpression.parse(r.getValue("expr")),
                    storage = SsmStorage.of(r.getValue("storagetype"), totalBytes),
                    format = r.getValue("format"),
                )
            }
            return SsmParameter(
                id = first.getValue("id"),
                name = first.getValue("name"),
                kind = if (first.getValue("kind") == "ext") SsmParameterKind.EXTENDED else SsmParameterKind.STANDARD,
                ecuId = first.getValue("ecu_id").ifBlank { null },
                addresses = addresses,
                lengthPerAddress = length,
                capability = capability,
                conversions = conversions,
            )
        }
    }
}
