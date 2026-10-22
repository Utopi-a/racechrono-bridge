package com.utopia.racechronobridge.canlog

import com.utopia.racechronobridge.canbus.MonitorLine
import com.utopia.racechronobridge.elm.Elm327Transport
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanCaptureSessionTest {
    @Test
    fun restartsMonitorAfterBufferFullAndStopsCleanly() {
        lateinit var session: CanCaptureSession
        val transport = ScriptedTransport(
            chunks = ArrayDeque(
                listOf(
                    "41009001B0F00E702C1\r4120",
                    "0BA00BA0006C1DD\rBUFFER FULL\r\r>",
                    "51402007706E81C0B00\r",
                ),
            ),
            onExhausted = { session.requestStop() },
            stopResponse = "STOPPED\r\r>",
        )
        val output = StringWriter()
        val writer = CanCaptureWriter(output, elapsedMillis = { 0 })
        session = CanCaptureSession(
            transport = transport,
            writer = writer,
            onLog = {},
            onProgress = {},
        )

        session.run()

        assertEquals(listOf("ATMA\r", "ATMA\r", "\r"), transport.rawWrites)
        assertEquals(3, writer.frameCount)
        assertEquals(listOf("410", "412", "514"), writer.seenIds())
        val rows = output.toString().lines()
        assertTrue("0,F,412,00BA00BA0006C1DD" in rows)
        assertTrue("0,S,,BUFFER FULL" in rows)
        assertTrue("0,S,,ATMA restarted" in rows)
        assertTrue("0,S,,STOPPED" in rows)
    }

    @Test
    fun abortsWhenTheAdapterKeepsReturningWithoutFrames() {
        val transport = ScriptedTransport(
            chunks = ArrayDeque(List(5) { "?\r\r>" }),
            onExhausted = { error("should abort before running out of chunks") },
            stopResponse = ">",
        )
        val writer = CanCaptureWriter(StringWriter(), elapsedMillis = { 0 })
        val session = CanCaptureSession(transport = transport, writer = writer, onLog = {}, onProgress = {})

        assertFailsWith<IllegalStateException> { session.run() }
        assertEquals(5, transport.rawWrites.count { it == "ATMA\r" })
    }

    @Test
    fun writerRecordsMarkersBetweenFrames() {
        val output = StringWriter()
        var now = 100L
        val writer = CanCaptureWriter(output, elapsedMillis = { now })
        writer.writeHeader("test")
        writer.frame(MonitorLine.Frame(id = "410", data = "00"))
        now = 250L
        writer.marker("brake")

        assertEquals(
            listOf("# test", "elapsed_ms,kind,id,data", "100,F,410,00", "250,M,,brake", ""),
            output.toString().lines(),
        )
    }

    private class ScriptedTransport(
        private val chunks: ArrayDeque<String>,
        private val onExhausted: () -> Unit,
        private val stopResponse: String,
    ) : Elm327Transport {
        val rawWrites = mutableListOf<String>()
        private var stopSent = false

        override fun sendCommand(command: String, timeoutMillis: Long): String = "OK\r\r>"

        override fun writeRaw(text: String) {
            rawWrites += text
            if (text == "\r") {
                stopSent = true
            }
        }

        override fun readAvailable(timeoutMillis: Long): String {
            if (stopSent) {
                return stopResponse.also { stopSent = false }
            }
            return chunks.removeFirstOrNull() ?: "".also { onExhausted() }
        }

        override fun close() = Unit
    }
}
