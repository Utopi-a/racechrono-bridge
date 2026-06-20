package com.utopia.racechronobridge.ssm2

import java.util.Locale

data class CustomTelemetryImportResult(
    val channels: List<CustomTelemetryChannel>,
    val errors: List<String>,
)

object CustomTelemetryChannelParser {
    fun parse(text: String): CustomTelemetryImportResult {
        val channels = mutableListOf<CustomTelemetryChannel>()
        val errors = mutableListOf<String>()
        var header: List<String>? = null

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEachIndexed { index, line ->
                val cells = parseCsvLine(line)
                if (cells.isEmpty()) {
                    return@forEachIndexed
                }

                if (header == null && cells.looksLikeHeader()) {
                    header = cells.map { it.normalizedKey() }
                    return@forEachIndexed
                }

                val values = if (cells.looksLikeKeyValues()) {
                    cells.mapNotNull { cell ->
                        val key = cell.substringBefore("=", missingDelimiterValue = "").trim()
                        val value = cell.substringAfter("=", missingDelimiterValue = "").trim()
                        if (key.isBlank()) null else key.normalizedKey() to value
                    }.toMap()
                } else {
                    cells.toValueMap(header ?: DEFAULT_HEADER)
                }

                runCatching {
                    channels.add(values.toCustomChannel())
                }.onFailure { error ->
                    errors.add("line ${index + 1}: ${error.message ?: error::class.simpleName}")
                }
            }

        return CustomTelemetryImportResult(
            channels = channels.withStableIds(),
            errors = errors,
        )
    }

    private fun List<String>.looksLikeHeader(): Boolean {
        val keys = map { it.normalizedKey() }.toSet()
        return "slot" in keys || "rc3field" in keys || "field" in keys || "rc3" in keys ||
            "address" in keys && "label" in keys
    }

    private fun List<String>.looksLikeKeyValues(): Boolean {
        return all { cell -> cell.contains("=") } && any { cell -> cell.substringBefore("=").trim().isNotBlank() }
    }

    private fun List<String>.toValueMap(header: List<String>): Map<String, String> {
        return header.zip(this).toMap()
    }

    private fun Map<String, String>.toCustomChannel(): CustomTelemetryChannel {
        val rc3Field = normalizeRc3Field(required("slot", "rc3field", "field", "rc3"))
        val label = required("label", "name")
        val unit = value("unit").orEmpty()
        val address = parseAddress(value("address") ?: value("addr") ?: value("command").orEmpty())
        val bytes = value("bytes", "size")?.toIntOrNull() ?: 1
        val scale = value("scale", "multiplier", "multiply")?.toDoubleOrNull() ?: 1.0
        val offset = value("offset", "add")?.toDoubleOrNull() ?: 0.0
        val mode = parseMode(value("mode") ?: ChannelMode.SLOW.name)
        val signed = value("signed")?.toBooleanStrictOrNull() ?: false
        val id = value("id")?.takeIf { it.isNotBlank() }
            ?: listOf(rc3Field, label, address.toString(16)).joinToString("_")
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')

        return CustomTelemetryChannel(
            id = id,
            rc3Field = rc3Field,
            label = label,
            unit = unit,
            address = address,
            bytes = bytes,
            scale = scale,
            offset = offset,
            mode = mode,
            signed = signed,
        )
    }

    private fun Map<String, String>.required(vararg keys: String): String {
        return value(*keys)?.takeIf { it.isNotBlank() }
            ?: error("Missing required value: ${keys.joinToString("/")}")
    }

    private fun Map<String, String>.value(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this[key.normalizedKey()]
            if (value != null) {
                return value.trim()
            }
        }
        return null
    }

    private fun normalizeRc3Field(raw: String): String {
        val normalized = raw.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        val analog = when {
            normalized.startsWith("analog") -> normalized.removePrefix("analog").toIntOrNull()
            normalized.startsWith("a") -> normalized.removePrefix("a").toIntOrNull()
            else -> null
        }
        if (analog != null && analog in 1..15) {
            return "Analog $analog"
        }

        return when (normalized) {
            "digital1", "d1", "rpm", "digital1rpm" -> "Digital 1/RPM"
            "digital2", "d2", "gear" -> "Digital 2"
            else -> error("Unsupported RC3 slot: $raw")
        }
    }

    private fun parseAddress(raw: String): Int {
        val normalized = raw
            .removePrefix("0x")
            .removePrefix("0X")
            .replace(Regex("[^A-Fa-f0-9]"), "")
            .uppercase(Locale.US)
        require(normalized.isNotBlank()) { "Missing address" }

        val addressHex = if (normalized.startsWith("A8") && normalized.length > 2) {
            normalized.removePrefix("A8")
        } else {
            normalized
        }.takeLast(8)

        return addressHex.toInt(radix = 16)
    }

    private fun parseMode(raw: String): ChannelMode {
        val normalized = raw.lowercase(Locale.US)
        return when (normalized) {
            "off", "none", "disabled" -> ChannelMode.OFF
            "fast", "f" -> ChannelMode.FAST
            "slow", "s" -> ChannelMode.SLOW
            else -> error("Unsupported mode: $raw")
        }
    }

    private fun List<CustomTelemetryChannel>.withStableIds(): List<CustomTelemetryChannel> {
        val seen = mutableMapOf<String, Int>()
        return map { channel ->
            val count = seen.getOrDefault(channel.id, 0)
            seen[channel.id] = count + 1
            if (count == 0) {
                channel
            } else {
                channel.copy(id = "${channel.id}_${count + 1}")
            }
        }
    }

    private fun String.normalizedKey(): String {
        return lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                quoted && character == '"' && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index += 1
                }
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    cells.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(character)
            }
            index += 1
        }
        cells.add(current.toString().trim())
        return cells
    }

    private val DEFAULT_HEADER = listOf(
        "slot",
        "label",
        "unit",
        "address",
        "bytes",
        "scale",
        "offset",
        "mode",
        "signed",
    )
}
