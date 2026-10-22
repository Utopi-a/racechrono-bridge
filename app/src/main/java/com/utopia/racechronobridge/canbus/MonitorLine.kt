package com.utopia.racechronobridge.canbus

sealed interface MonitorLine {
    /** An 11-bit CAN frame printed by ELM327 ATMA with ATH1 and ATCAF0. */
    data class Frame(val id: String, val data: String) : MonitorLine

    /** Anything else the adapter prints while monitoring, e.g. BUFFER FULL or STOPPED. */
    data class Status(val text: String) : MonitorLine

    companion object {
        private val FRAME_PATTERN = Regex("^([0-9A-F]{3})((?:[0-9A-F]{2}){0,8})$")

        fun parse(line: String): MonitorLine? {
            val trimmed = line.trim().removePrefix(">").trim()
            if (trimmed.isEmpty()) {
                return null
            }
            val compact = trimmed.replace(" ", "").uppercase()
            val match = FRAME_PATTERN.matchEntire(compact) ?: return Status(trimmed)
            return Frame(id = match.groupValues[1], data = match.groupValues[2])
        }
    }
}
