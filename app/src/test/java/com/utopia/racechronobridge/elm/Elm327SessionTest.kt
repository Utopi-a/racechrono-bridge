package com.utopia.racechronobridge.elm

import com.utopia.racechronobridge.ssm2.ScriptedElmTransport
import kotlin.test.Test
import kotlin.test.assertFailsWith

class Elm327SessionTest {
    @Test
    fun atCommandsMustBeAcknowledgedWithOk() {
        // 遅れて届いた SSM2 の応答（E8 22）を AT の応答として受け取って先へ進まない
        val session = Elm327Session(ScriptedElmTransport { "E8 22\r>" }, onRawResponse = {}, onLog = {})
        assertFailsWith<IllegalStateException> { session.configure(listOf("ATH0")) }
    }

    @Test
    fun atzAnswersWithVersionNotOk() {
        val session = Elm327Session(ScriptedElmTransport { if (it == "ATZ") "ELM327 v1.5\r>" else "OK\r>" }, onRawResponse = {}, onLog = {})
        session.configure(listOf("ATZ", "ATE0", "ATSP6"))
    }
}
