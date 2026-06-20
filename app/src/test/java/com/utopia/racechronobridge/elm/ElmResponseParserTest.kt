package com.utopia.racechronobridge.elm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ElmResponseParserTest {
    private val parser = ElmResponseParser()

    @Test
    fun parsesObservedShortResponse() {
        val response = parser.parseSsm2Data("E8 7F>")

        val data = assertIs<ElmSsm2Response.Data>(response)
        assertEquals(listOf(0xE8, 0x7F), data.allBytes)
        assertEquals(listOf(0x7F), data.dataBytes)
    }

    @Test
    fun parsesLengthPrefixedResponse() {
        val response = parser.parseSsm2Data("04 E8 3D>")

        val data = assertIs<ElmSsm2Response.Data>(response)
        assertEquals(listOf(0x3D), data.dataBytes)
    }

    @Test
    fun parsesHeaderIncludedResponseAndIgnoresEcho() {
        val response = parser.parseSsm2Data(
            raw = "A800000008\r7E8 04 E8 7F\r>",
            echoCommand = "A800000008",
        )

        val data = assertIs<ElmSsm2Response.Data>(response)
        assertEquals(listOf(0x04, 0xE8, 0x7F), data.allBytes)
        assertEquals(listOf(0x7F), data.dataBytes)
    }

    @Test
    fun reportsNoData() {
        val response = parser.parseSsm2Data("NO DATA>")

        val error = assertIs<ElmSsm2Response.Error>(response)
        assertEquals("NO DATA", error.reason)
    }

    @Test
    fun reportsQuestionMarkErrorWithPrompt() {
        val response = parser.parseSsm2Data("?>")

        val error = assertIs<ElmSsm2Response.Error>(response)
        assertEquals("?", error.reason)
    }
}
