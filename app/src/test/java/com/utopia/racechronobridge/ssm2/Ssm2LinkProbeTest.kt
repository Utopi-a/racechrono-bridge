package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ssm2LinkProbeTest {
    private val memory = (0xFF7080..0xFF70D0).associateWith { it and 0xFF } + mapOf(0x08 to 0x5A, 0x0E to 0x2E, 0x0F to 0xE0, 0x10 to 0x64)
    private val targets = Ssm2LinkProbeTargets(
        standardAddress = 0x08,
        ramAddress = 0xFF709E,
        ramBlock = Ssm2Read.Block(0xFF709E, 46),
        batchAddresses = listOf(0x08, 0x0E, 0x0F, 0x10),
    )

    private fun probe(ecu: FakeEcu): Ssm2LinkProbeResult {
        val transport = ScriptedElmTransport(ecu::respond)
        val session = Elm327Session(transport, onRawResponse = {}, onLog = {})
        return Ssm2LinkProbe(transport, session, clock = { 0L }).run(targets)
    }

    @Test
    fun showsThatCanRejectsRamWhileKLineReadsIt() {
        val result = probe(FakeEcu(memory, acceptRamReads = false))
        val (can, kLine) = result.reports
        assertEquals(
            listOf(
                "アダプター: ELM327 v2.3 / ST コマンド なし",
                // ELM327 は CAN の 1 フレーム（7 バイト）に 2 アドレスの A8（8 バイト）を入れられない
                "CAN: 識別 ○ 0 ms / 標準 ○ 0 ms / RAM 拒否 / ブロック 拒否 / まとめ読み 2 件 ×",
                "K-line: 識別 ○ 0 ms / 標準 ○ 0 ms / RAM ○ 0 ms / ブロック ○ 0 ms / まとめ読み 2 件 ○ 0 ms",
            ),
            result.summary().lines(),
        )
        assertEquals("ECU 5104584007", kLine.checks.first { it.kind == Ssm2LinkCheckKind.IDENTIFY }.detail)
        assertEquals("0x9E", kLine.checks.first { it.kind == Ssm2LinkCheckKind.RAM_ADDRESS }.detail)
    }

    @Test
    fun stnAdapterReadsManyAddressesInOneRequestOnBothLinks() {
        val result = probe(FakeEcu(memory, stn = true))
        assertEquals("アダプター: ELM327 v2.3 / ST コマンド あり（STN2120 v5.10.3）", result.adapter.summary())
        result.reports.forEach { report ->
            val batch = report.checks.first { it.kind == Ssm2LinkCheckKind.BATCH }
            assertEquals(Ssm2LinkOutcome.OK, batch.outcome, report.summary())
            assertEquals("まとめ読み 4 件（STPX）", batch.label)
            assertEquals("0x5A 0x2E 0xE0 0x64", batch.detail)
        }
    }

    @Test
    fun adapterThatAnswersOkToEverythingIsNotTakenForStn() {
        val transport = ScriptedElmTransport { "OK\r>" }
        val session = Elm327Session(transport, onRawResponse = {}, onLog = {})
        assertEquals(null, Ssm2LinkProbe(transport, session, clock = { 0L }).run(targets).adapter.stnId)
    }

    @Test
    fun reportsAnAdapterThatCannotBeSetToKLine() {
        val (_, kLine) = probe(FakeEcu(memory, unsupportedAtCommands = setOf("ATIB48"))).reports
        assertTrue(kLine.summary().startsWith("K-line: 設定できず"), kLine.summary())
        assertTrue("ATIB48" in kLine.setupFailure.orEmpty())
    }

    @Test
    fun noAnswerOnKLineIsAFailureNotARejection() {
        val (_, kLine) = probe(FakeEcu(memory, answersKLine = false)).reports
        assertEquals(List(5) { Ssm2LinkOutcome.FAILED }, kLine.checks.map { it.outcome })
    }

    @Test
    fun measuresTheAverageRoundTrip() {
        var now = 0L
        val ecu = FakeEcu(memory)
        val transport = ScriptedElmTransport { command -> now += if (ecu.link == Ssm2Link.KLINE) 50 else 10; ecu.respond(command) }
        val session = Elm327Session(transport, onRawResponse = {}, onLog = {})
        val (can, kLine) = Ssm2LinkProbe(transport, session, clock = { now }).run(targets).reports
        assertEquals(10L, can.checks.first { it.kind == Ssm2LinkCheckKind.STANDARD }.averageMillis)
        assertEquals(50L, kLine.checks.first { it.kind == Ssm2LinkCheckKind.STANDARD }.averageMillis)
    }
}
