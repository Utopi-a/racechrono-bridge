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

class ElmMultiFrameResponseTest {
    private val parser = ElmResponseParser()

    @Test
    fun assemblesMultiFrameBlockReadWithoutSpaces() {
        // ATS0 / ATCAF1: 1 行目がバイト数（0x0A = 10）、続いて行番号付きの行
        val raw = "00A\r0:E0010203040506\r1:070809AAAAAAAA\r\r>"
        val data = assertIs<ElmSsm2Response.Data>(
            parser.parseSsm2Response(raw = raw, echoCommand = "A000FF708C08", marker = ElmResponseParser.SSM2_BLOCK_READ_MARKER),
        )
        assertEquals((1..9).toList(), data.dataBytes)
    }

    @Test
    fun assemblesMultiFrameWithSpacesAndWrappedLineNumbers() {
        val lines = listOf("021") + (0 until 5).map { i -> "${"%X".format(i)}: " + List(7) { "%02X".format(i * 7 + it) }.joinToString(" ") }
        val raw = lines.joinToString("\r") + "\r>"
        val data = assertIs<ElmSsm2Response.Data>(
            parser.parseSsm2Response(raw = raw, echoCommand = null, marker = 0x00),
        )
        // 33 バイト中、先頭の 0x00 を応答コードとして扱うので残り 32 バイト
        assertEquals((1 until 33).toList(), data.dataBytes)
    }

    @Test
    fun treats7FAsRejectionEvenWithLengthPrefix() {
        val response = parser.parseSsm2Response(raw = "03 7F A0 12\r>", echoCommand = null, marker = ElmResponseParser.SSM2_BLOCK_READ_MARKER)
        val error = assertIs<ElmSsm2Response.Error>(response)
        assertEquals(true, "rejected" in error.reason)
    }

    @Test
    fun keepsDataByte7FAfterAddressReadMarker() {
        val data = assertIs<ElmSsm2Response.Data>(parser.parseSsm2Data("02 E8 7F>"))
        assertEquals(listOf(0x7F), data.dataBytes)
    }

    @Test
    fun reportsBufferFull() {
        val error = assertIs<ElmSsm2Response.Error>(parser.parseSsm2Data("E8 01\rBUFFER FULL\r>"))
        assertEquals("BUFFER FULL", error.reason)
    }
}

class ElmErrorLineTest {
    private val parser = ElmResponseParser()

    @Test
    fun errorLinesFailTheWholeResponseEvenWithData() {
        listOf("E0 01 02 03\r<DATA ERROR\r>", "E8 3D\r<RX ERROR\r>", "BUS BUSY\r>", "UNABLE TO CONNECT\r>", "ERR94\r>", "E8 3D\rFB ERROR\r>").forEach { raw ->
            assertIs<ElmSsm2Response.Error>(parser.parseSsm2Response(raw = raw, echoCommand = null, marker = 0xE8), raw)
        }
    }

    @Test
    fun bytesComeOnlyFromHexLines() {
        // "OK" や英字の行から DA・AB などをバイトとして拾わない
        val data = assertIs<ElmSsm2Response.Data>(parser.parseSsm2Response(raw = "OK\rE8 3D\r>", echoCommand = null, marker = 0xE8))
        assertEquals(listOf(0x3D), data.dataBytes)
    }

    @Test
    fun strictMarkerOnlyAcceptsTheFirstOrSecondByte() {
        assertIs<ElmSsm2Response.Error>(parser.parseSsm2Response(raw = "E0 01 E8 22\r>", echoCommand = null, marker = 0xE8))
        // 従来の A8 読み出し（Ssm2Reader）は位置を問わないまま
        assertIs<ElmSsm2Response.Data>(parser.parseSsm2Data("E0 01 E8 22\r>"))
    }
}
