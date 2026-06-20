package com.utopia.racechronobridge.ssm2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomTelemetryChannelParserTest {
    @Test
    fun parsesHeaderCsvFromLlmText() {
        val result = CustomTelemetryChannelParser.parse(
            """
            slot,label,unit,address,bytes,scale,offset,mode,signed
            Analog 15,Oil temp,C,0x000108,1,1,-40,Slow,false
            """.trimIndent(),
        )

        assertEquals(emptyList(), result.errors)
        val channel = result.channels.single()
        assertEquals("Analog 15", channel.rc3Field)
        assertEquals("Oil temp", channel.label)
        assertEquals("C", channel.unit)
        assertEquals(0x000108, channel.address)
        assertEquals(1, channel.bytes)
        assertEquals(1.0, channel.scale)
        assertEquals(-40.0, channel.offset)
        assertEquals(ChannelMode.SLOW, channel.mode)
        assertEquals("A800000108", channel.commandForByte(0))
    }

    @Test
    fun parsesKeyValueLinesAndNormalizesSlots() {
        val result = CustomTelemetryChannelParser.parse(
            "slot=a14,label=Fuel pressure,unit=kPa,address=A800000120,bytes=2,scale=0.1,offset=0,mode=fast,signed=false",
        )

        assertEquals(emptyList(), result.errors)
        val channel = result.channels.single()
        assertEquals("Analog 14", channel.rc3Field)
        assertEquals(2, channel.bytes)
        assertEquals(ChannelMode.FAST, channel.mode)
        assertEquals("A800000120", channel.commandForByte(0))
        assertEquals("A800000121", channel.commandForByte(1))
        assertEquals(466.0, channel.convert(4660))
    }

    @Test
    fun acceptsRc3HeaderAlias() {
        val result = CustomTelemetryChannelParser.parse(
            """
            rc3,label,unit,address
            d2,Calculated gear,,0x00004A
            """.trimIndent(),
        )

        assertEquals(emptyList(), result.errors)
        assertEquals("Digital 2", result.channels.single().rc3Field)
    }

    @Test
    fun reportsInvalidRowsWithoutDroppingValidRows() {
        val result = CustomTelemetryChannelParser.parse(
            """
            Analog 13,Oil pressure,bar,0x000106,1,0.01,0,Slow,false
            Analog 99,Bad,C,0x000108,1,1,0,Slow,false
            """.trimIndent(),
        )

        assertEquals(1, result.channels.size)
        assertEquals("Oil pressure", result.channels.single().label)
        assertTrue(result.errors.single().contains("Unsupported RC3 slot"))
    }

    @Test
    fun parsesStandardTuningPreset() {
        val result = CustomTelemetryChannelParser.parse(StandardSsm2Preset.tuningPresetCsv)

        assertEquals(emptyList(), result.errors)
        assertEquals(6, result.channels.size)

        val byField = result.channels.associateBy { it.rc3Field }
        assertEquals("A/F Correction #1", byField.getValue("Analog 10").label)
        assertEquals(0x000009, byField.getValue("Analog 10").address)
        assertEquals(0.78125, byField.getValue("Analog 10").scale)
        assertEquals(-100.0, byField.getValue("Analog 10").offset)
        assertEquals(ChannelMode.SLOW, byField.getValue("Analog 10").mode)

        assertEquals("IAM", byField.getValue("Analog 12").label)
        assertEquals(0x0000F9, byField.getValue("Analog 12").address)
        assertEquals(1.0, byField.getValue("Analog 12").convert(16))

        assertEquals("Fine knock learn", byField.getValue("Analog 13").label)
        assertEquals(0x000199, byField.getValue("Analog 13").address)
        assertEquals(-31.75, byField.getValue("Analog 13").convert(1))

        assertEquals("Engine Load", byField.getValue("Analog 15").label)
        assertEquals(0x000007, byField.getValue("Analog 15").address)
        assertEquals(100.0, byField.getValue("Analog 15").convert(255), 0.000001)
    }
}
