package com.utopia.racechronobridge.logging

import java.time.LocalTime
import java.time.format.DateTimeFormatter

class DebugLog(
    private val maxLines: Int = 200,
) {
    private val timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun append(message: String) {
        lines.addLast("${LocalTime.now().format(timestampFormat)}  $message")
        while (lines.size > maxLines) {
            lines.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
