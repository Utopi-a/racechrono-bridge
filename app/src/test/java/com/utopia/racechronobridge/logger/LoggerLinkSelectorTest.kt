package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.elm.Elm327Session
import com.utopia.racechronobridge.ssm2.FakeEcu
import com.utopia.racechronobridge.ssm2.ScriptedElmTransport
import com.utopia.racechronobridge.ssm2.Ssm2Link
import com.utopia.racechronobridge.ssm2.catalog.SsmCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggerLinkSelectorTest {
    private val gh8 = SsmCatalog.parse(File("src/main/assets/ssm_catalog.tsv").readLines().asSequence()).forEcu("5104584007")
    private val memory = (0xFF7080..0xFF70D0).associateWith { it and 0xFF } + mapOf(0x08 to 0x5A)

    private class Run(val ecu: FakeEcu, val transport: ScriptedElmTransport, val choice: LoggerLinkChoice)

    private fun select(ecu: FakeEcu, preset: LogPreset): Run {
        val transport = ScriptedElmTransport(ecu::respond)
        val session = Elm327Session(transport, onRawResponse = {}, onLog = {})
        return Run(ecu, transport, LoggerLinkSelector(transport, session).select(preset, gh8))
    }

    @Test
    fun staysOnCanWhenRamIsReadable() {
        val run = select(FakeEcu(memory), LogPresets.FUEL)
        assertEquals(Ssm2Link.CAN, run.choice.client.link)
        assertTrue(run.choice.ramReadable)
        assertFalse("ATSP5" in run.transport.sent)
    }

    @Test
    fun switchesToKLineWhenCanRejectsRamThatThePresetNeeds() {
        val run = select(FakeEcu(memory, acceptRamReads = false), LogPresets.FUEL)
        assertEquals(Ssm2Link.KLINE, run.choice.client.link)
        assertTrue(run.choice.ramReadable)
        assertEquals(0x9E, run.choice.client.readAddress(0xFF709E))
    }

    @Test
    fun staysOnCanWhenStandardParametersGiveTheSameValues() {
        val run = select(FakeEcu(memory, acceptRamReads = false), LogPresets.SHIFT)
        assertEquals(Ssm2Link.CAN, run.choice.client.link)
        assertFalse(run.choice.ramReadable)
        assertFalse("ATSP5" in run.transport.sent)
    }

    @Test
    fun returnsToCanWhenTheEcuDoesNotAnswerOnKLine() {
        val run = select(FakeEcu(memory, acceptRamReads = false, answersKLine = false), LogPresets.FUEL)
        assertEquals(Ssm2Link.CAN, run.choice.client.link)
        assertFalse(run.choice.ramReadable)
        assertEquals(Ssm2Link.CAN, run.ecu.link)
        assertEquals(0x5A, run.choice.client.readAddress(0x08))
    }

    @Test
    fun returnsToCanWhenTheAdapterCannotBeSetToKLine() {
        val run = select(FakeEcu(memory, acceptRamReads = false, unsupportedAtCommands = setOf("ATIB48")), LogPresets.FUEL)
        assertEquals(Ssm2Link.CAN, run.choice.client.link)
        assertFalse(run.choice.ramReadable)
        assertTrue(run.transport.sent.indexOf("ATZ") > run.transport.sent.indexOf("ATIB48"))
    }
}
