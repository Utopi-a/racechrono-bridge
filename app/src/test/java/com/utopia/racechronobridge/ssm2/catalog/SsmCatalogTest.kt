package com.utopia.racechronobridge.ssm2.catalog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SsmExpressionTest {
    @Test
    fun evaluatesRomRaiderExpressions() {
        assertEquals(11.0, SsmExpression.parse("(x-128)/2").evaluate(150.0))
        assertEquals(14.7, SsmExpression.parse("x/128*14.7").evaluate(128.0), 1e-9)
        assertEquals(14.7, SsmExpression.parse("14.7/(x*.0004882812)").evaluate(2048.0), 1e-4)
        assertEquals(-1.40625, SsmExpression.parse("(x*.3515625)-45").evaluate(124.0), 1e-9)
        assertEquals(1.0, SsmExpression.parse("-x+3").evaluate(2.0))
        assertEquals(212.0, SsmExpression.parse("32+9*(x-40)/5").evaluate(140.0), 1e-9)
    }

    @Test
    fun rejectsSyntaxOutsideTheDefinitionGrammar() {
        listOf("x^2", "sqrt(x)", "x*", "(x", "x y", "").forEach { source ->
            assertFailsWith<IllegalArgumentException>(source) { SsmExpression.parse(source) }
        }
    }
}

class SsmStorageTest {
    @Test
    fun decodesBigEndianValues() {
        assertEquals(1.0, SsmStorage.FLOAT.decode(listOf(0x3F, 0x80, 0x00, 0x00)))
        assertEquals(-1.0, SsmStorage.INT8.decode(listOf(0xFF)))
        assertEquals(-2.0, SsmStorage.INT16.decode(listOf(0xFF, 0xFE)))
        assertEquals(256.0, SsmStorage.UINT16.decode(listOf(0x01, 0x00)))
        assertEquals(4294967295.0, SsmStorage.UINT32.decode(listOf(0xFF, 0xFF, 0xFF, 0xFF)))
    }

    @Test
    fun infersUnsignedStorageFromLengthWhenDefinitionHasNoType() {
        assertEquals(SsmStorage.UINT8, SsmStorage.of("", 1))
        assertEquals(SsmStorage.UINT16, SsmStorage.of("", 2))
        assertEquals(SsmStorage.FLOAT, SsmStorage.of("float", 4))
    }
}

class SsmCatalogAssetTest {
    private val catalog = SsmCatalog.parse(File("src/main/assets/ssm_catalog.tsv").readLines().asSequence())
    private val gh8 = catalog.forEcu("5104584007")

    @Test
    fun recordsTheRomRaiderSourceVersion() {
        assertTrue("VERSION 370" in catalog.source, catalog.source)
    }

    @Test
    fun gh8HasStandardAndExtendedParameters() {
        assertEquals(setOf("5104584007"), catalog.ecuIds())
        assertTrue(gh8.values.count { it.kind == SsmParameterKind.EXTENDED } >= 50)
        assertTrue(gh8.values.count { it.kind == SsmParameterKind.STANDARD } >= 200)
    }

    @Test
    fun engineSpeedReadsTwoConsecutiveBytes() {
        val p = gh8.getValue("P8")
        assertEquals(listOf(0x0E, 0x0F), p.byteAddresses)
        assertEquals(3000.0, p.decode(listOf(0x2E, 0xE0)))
    }

    @Test
    fun extendedOneByteKnockCorrectionUsesRamAddress() {
        val p = gh8.getValue("E94")
        assertEquals(listOf(0xFF70C6), p.byteAddresses)
        assertEquals(-1.40625, p.decode(listOf(124)), 1e-9)
        assertEquals(0.0, p.decode(listOf(128)), 1e-9)
    }

    @Test
    fun fourByteExtendedParametersAreFloats() {
        val p = gh8.getValue("E60")
        assertEquals(SsmStorage.FLOAT, p.conversion().storage)
        assertEquals(listOf(0xFF7A48, 0xFF7A49, 0xFF7A4A, 0xFF7A4B), p.byteAddresses)
        // 12000 us (0x463B8000) を ms に
        assertEquals(12.0, p.decode(listOf(0x46, 0x3B, 0x80, 0x00)), 1e-9)
    }

    @Test
    fun afSensorCanBeReadAsAfrInsteadOfTheDefaultLambda() {
        val p = gh8.getValue("P58")
        assertEquals("Lambda", p.conversion().units)
        assertEquals(14.7, p.decode(listOf(128), p.conversion("AFR")), 1e-9)
    }

    @Test
    fun columnNamesMatchRomRaiderLoggerCsvHeaders() {
        // 2026-02-24 に RomRaider Logger で取ったログの見出しと同じ文字列
        assertEquals("Engine Speed (rpm)", gh8.getValue("P8").columnName())
        assertEquals("Mass Airflow (g/s)", gh8.getValue("P12").columnName())
        assertEquals("Feedback Knock Correction (1-byte)** (degrees)", gh8.getValue("E94").columnName())
        assertEquals("Manifold Relative Pressure (kPa)", gh8.getValue("P25").columnName())
    }

    @Test
    fun capabilityFlagsDecideStandardParameterSupport() {
        val cap = assertNotNull(gh8.getValue("P8").capability)
        val identification = MutableList(48) { 0 }
        assertFalse(cap.isSupportedBy(identification))
        identification[cap.byteIndex] = 1 shl cap.bit
        assertTrue(cap.isSupportedBy(identification))
    }
}
