package com.utopia.racechronobridge.ssm2

import java.util.Locale

data class CustomTelemetryChannel(
    val id: String,
    val rc3Field: String,
    val label: String,
    val unit: String,
    val address: Int,
    val bytes: Int,
    val scale: Double,
    val offset: Double,
    val mode: ChannelMode,
    val signed: Boolean,
) {
    init {
        require(rc3Field in SUPPORTED_RC3_FIELDS) { "Unsupported RC3 field: $rc3Field" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(address in ADDRESS_RANGE) { "address must be 0x000000..0xFFFFFF" }
        require(bytes in 1..2) { "bytes must be 1 or 2" }
        require(address + bytes - 1 in ADDRESS_RANGE) { "channel byte range must stay inside 0x000000..0xFFFFFF" }
    }

    val mappingLabel: String = listOf(label, unit.takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" ")

    val addressLabel: String = "0x${address.toString(16).uppercase(Locale.US).padStart(6, '0')}"

    fun commandForByte(byteOffset: Int): String {
        require(byteOffset in 0 until bytes) { "byteOffset must be inside channel bytes" }
        val byteAddress = address + byteOffset
        return "A8${byteAddress.toString(16).uppercase(Locale.US).padStart(8, '0')}"
    }

    fun convert(rawValue: Int): Double = rawValue * scale + offset

    fun toConfigLine(): String {
        return listOf(
            rc3Field,
            label,
            unit,
            addressLabel,
            bytes.toString(),
            scale.toPlainString(),
            offset.toPlainString(),
            mode.name,
            signed.toString(),
        ).joinToString(separator = ",") { it.toCsvCell() }
    }

    companion object {
        val SUPPORTED_RC3_FIELDS = TelemetryChannel.entries.map { it.rc3Field }.toSet()
        private val ADDRESS_RANGE = 0..0xFFFFFF
    }
}

private fun Double.toPlainString(): String {
    return if (this % 1.0 == 0.0) {
        this.toLong().toString()
    } else {
        toString()
    }
}

private fun String.toCsvCell(): String {
    return if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${replace("\"", "\"\"")}\""
    } else {
        this
    }
}
