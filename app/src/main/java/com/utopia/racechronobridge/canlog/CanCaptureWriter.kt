package com.utopia.racechronobridge.canlog

import java.io.Writer

/**
 * Writes one CSV row per event: `elapsed_ms,kind,id,data`.
 * kind is F (frame), M (user marker) or S (adapter status).
 * Markers are written from the UI thread while frames come from the capture thread.
 */
class CanCaptureWriter(
    private val writer: Writer,
    private val elapsedMillis: () -> Long,
) {
    var frameCount = 0L
        private set

    private val ids = sortedSetOf<String>()

    @Synchronized
    fun writeHeader(comment: String) {
        writer.write("# $comment\n")
        writer.write("elapsed_ms,kind,id,data\n")
    }

    @Synchronized
    fun frame(frame: MonitorLine.Frame) {
        writer.write("${elapsedMillis()},F,${frame.id},${frame.data}\n")
        frameCount++
        ids.add(frame.id)
    }

    @Synchronized
    fun marker(label: String) {
        writer.write("${elapsedMillis()},M,,${label.toCsvField()}\n")
        writer.flush()
    }

    @Synchronized
    fun status(text: String) {
        writer.write("${elapsedMillis()},S,,${text.toCsvField()}\n")
    }

    @Synchronized
    fun seenIds(): List<String> = ids.toList()

    @Synchronized
    fun flush() {
        writer.flush()
    }

    @Synchronized
    fun close() {
        writer.close()
    }

    private fun String.toCsvField(): String = replace(',', ' ').replace('\n', ' ').trim()
}
