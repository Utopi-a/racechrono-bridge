package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ssm2LinkProbeTest {
    private val memory = (0xFF7080..0xFF70D0).associateWith { it and 0xFF } + mapOf(0x08 to 0x5A)
    private val targets = Ssm2LinkProbeTargets(
        standardAddress = 0x08,
        ramAddress = 0xFF709E,
        ramBlock = Ssm2Read.Block(0xFF709E, 46),
    )

    private fun probe(ecu: FakeEcu): List<Ssm2LinkReport> {
        val transport = ScriptedElmTransport(ecu::respond)
        val session = Elm327Session(transport, onRawResponse = {}, onLog = {})
        return Ssm2LinkProbe(transport, session, clock = { 0L }).run(targets)
    }

    @Test
    fun showsThatCanRejectsRamWhileKLineReadsIt() {
        val (can, kLine) = probe(FakeEcu(memory, acceptRamReads = false))
        assertEquals("CAN: 識別 ○ 0 ms / 標準 ○ 0 ms / RAM 拒否 / ブロック 拒否", can.summary())
        assertEquals("K-line: 識別 ○ 0 ms / 標準 ○ 0 ms / RAM ○ 0 ms / ブロック ○ 0 ms", kLine.summary())
        assertEquals("ECU 5104584007", kLine.checks.first { it.kind == Ssm2LinkCheckKind.IDENTIFY }.detail)
        assertEquals("0x9E", kLine.checks.first { it.kind == Ssm2LinkCheckKind.RAM_ADDRESS }.detail)
    }

    @Test
    fun reportsAnAdapterThatCannotBeSetToKLine() {
        val (_, kLine) = probe(FakeEcu(memory, unsupportedAtCommands = setOf("ATIB48")))
        assertTrue(kLine.summary().startsWith("K-line: 設定できず"), kLine.summary())
        assertTrue("ATIB48" in kLine.setupFailure.orEmpty())
    }

    @Test
    fun noAnswerOnKLineIsAFailureNotARejection() {
        val (_, kLine) = probe(FakeEcu(memory, answersKLine = false))
        assertEquals(List(4) { Ssm2LinkOutcome.FAILED }, kLine.checks.map { it.outcome })
    }

    @Test
    fun measuresTheAverageRoundTrip() {
        var now = 0L
        val ecu = FakeEcu(memory)
        val transport = ScriptedElmTransport { command -> now += if (ecu.link == Ssm2Link.KLINE) 50 else 10; ecu.respond(command) }
        val session = Elm327Session(transport, onRawResponse = {}, onLog = {})
        val (can, kLine) = Ssm2LinkProbe(transport, session, clock = { now }).run(targets)
        assertEquals(10L, can.checks.first { it.kind == Ssm2LinkCheckKind.STANDARD }.averageMillis)
        assertEquals(50L, kLine.checks.first { it.kind == Ssm2LinkCheckKind.STANDARD }.averageMillis)
    }
}
