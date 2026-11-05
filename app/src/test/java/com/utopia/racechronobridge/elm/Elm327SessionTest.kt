package com.utopia.racechronobridge.elm

import com.utopia.racechronobridge.ssm2.ScriptedElmTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Elm327SessionTest {
    @Test
    fun atCommandsMustBeAcknowledgedWithOk() {
        // 遅れて届いた SSM2 の応答（E8 22）を AT の応答として受け取って先へ進まない
        val session = Elm327Session(ScriptedElmTransport { "E8 22\r>" }, onRawResponse = {}, onLog = {})
        assertFailsWith<IllegalStateException> { session.configure(listOf("ATH0")) }
    }

    @Test
    fun kLineSetupSendsProtocolBeforeBypassingInit() {
        val transport = ScriptedElmTransport { "OK\r>" }
        Elm327Session(transport, onRawResponse = {}, onLog = {}).switchToKLine()
        // SSM2 の 4800 bps と、長さを 4 バイト目に入れるヘッダー。初期化の省略はプロトコルを決めた後でないと効かない
        assertTrue(listOf("ATIB48", "ATSH8010F0", "ATSP5", "ATH0").all { it in transport.sent })
        assertTrue(transport.sent.indexOf("ATSP5") < transport.sent.indexOf("ATBI"))
    }

    @Test
    fun kLineSetupContinuesWhenAnOptionalCommandIsRejected() {
        val transport = ScriptedElmTransport { if (it == "ATSW00") "?\r>" else "OK\r>" }
        val logs = mutableListOf<String>()
        Elm327Session(transport, onRawResponse = {}, onLog = { logs += it }).switchToKLine()
        assertEquals("ATBI", transport.sent.last())
        assertTrue(logs.any { "ATSW00" in it })
    }

    @Test
    fun kLineSetupFailsWhenTheAdapterLacks4800Baud() {
        val transport = ScriptedElmTransport { if (it == "ATIB48") "?\r>" else "OK\r>" }
        val error = assertFailsWith<IllegalStateException> {
            Elm327Session(transport, onRawResponse = {}, onLog = {}).switchToKLine()
        }
        assertTrue("ATIB48" in error.message.orEmpty())
        assertFalse("ATBI" in transport.sent)
    }

    @Test
    fun atzAnswersWithVersionNotOk() {
        val session = Elm327Session(ScriptedElmTransport { if (it == "ATZ") "ELM327 v1.5\r>" else "OK\r>" }, onRawResponse = {}, onLog = {})
        session.configure(listOf("ATZ", "ATE0", "ATSP6"))
    }
}
