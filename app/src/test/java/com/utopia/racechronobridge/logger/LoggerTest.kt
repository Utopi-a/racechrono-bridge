package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.ssm2.FakeEcu
import com.utopia.racechronobridge.ssm2.ScriptedElmTransport
import com.utopia.racechronobridge.ssm2.Ssm2Client
import com.utopia.racechronobridge.ssm2.Ssm2Read
import com.utopia.racechronobridge.ssm2.Ssm2ReadPlanner
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.catalog.SsmCatalog
import java.io.File
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val GH8 = SsmCatalog.parse(File("src/main/assets/ssm_catalog.tsv").readLines().asSequence()).forEcu("5104584007")

class LogPresetsTest {
    @Test
    fun everyPresetResolvesCompletelyForGh8() {
        LogPresets.all.forEach { preset ->
            val layout = LogLayout.resolve(preset, GH8)
            assertEquals(emptyList(), layout.missing, "preset ${preset.key}")
            assertEquals(preset.parameters.size, layout.columns.size, "preset ${preset.key}")
        }
    }

    @Test
    fun extendedParametersOfEachPresetFitInOneBlockRead() {
        // GH8 の 1〜2 バイト版拡張パラメータは 0xFF708C〜0xFF70CB にまとまっている。巡航（D）だけは学習域と CL/OL が離れた場所にある
        LogPresets.all.filter { it.key != "D" }.forEach { preset ->
            val layout = LogLayout.resolve(preset, GH8)
            val blocks = Ssm2ReadPlanner.plan(layout.fastAddresses).filterIsInstance<Ssm2Read.Block>()
            assertTrue(blocks.size <= 1, "preset ${preset.key}: $blocks")
        }
    }

    @Test
    fun fuelPresetAddsInjectorDutyBoostAndCalculatedLoad() {
        val layout = LogLayout.resolve(LogPresets.FUEL, GH8)
        assertEquals(listOf(DerivedColumn.INJECTOR_DUTY, DerivedColumn.MANIFOLD_RELATIVE, DerivedColumn.ENGINE_LOAD), layout.derived)
        assertTrue("A/F Sensor #1 (2-byte)** (estimated AFR)" in layout.headers)
        assertTrue("A/F Sensor #1 (AFR)" in layout.headers)
    }

    @Test
    fun boostPresetHasActualAndTargetOnTheSameAtmosphericBasis() {
        val layout = LogLayout.resolve(LogPresets.BOOST, GH8)
        assertTrue(DerivedColumn.MANIFOLD_RELATIVE in layout.derived)
        assertTrue(DerivedColumn.TARGET_BOOST_RELATIVE in layout.derived)
        // gh8-ecu-tuning の解析ツールは列名の前方一致で探す
        assertTrue(layout.headers.any { it.startsWith("Manifold Relative") })
        assertTrue(layout.headers.any { it.startsWith("Target Boost Relative") })
    }

    @Test
    fun wideOpenThrottleIsJudgedWithP13() {
        LogPresets.all.filter { it.key != "D" }.forEach { preset ->
            assertTrue(preset.parameters.any { it.id == "P13" }, preset.key)
        }
    }

    @Test
    fun unknownUnitsAreRejected() {
        val preset = LogPreset("X", "x", "x", listOf(LogParameterRef("P8", units = "furlongs")))
        assertFailsWith<IllegalArgumentException> { LogLayout.resolve(preset, GH8) }
    }
}

class DerivedColumnTest {
    @Test
    fun injectorDutyIsPulseWidthTimesRpmOver1200() {
        assertEquals(91.5, DerivedColumn.INJECTOR_DUTY.compute(mapOf("P21" to 16.8, "P8" to 6535.7))!!, 0.1)
    }

    @Test
    fun boostFromMapIsNotCappedAt127Kpa() {
        // 1.2 bar の跳ね: MAP 222 kPa 絶対、大気圧 101 kPa
        assertEquals(121.0, DerivedColumn.MANIFOLD_RELATIVE.compute(mapOf("E88" to 222.0, "P24" to 101.0))!!, 1e-9)
    }

    @Test
    fun calculatedLoadMatchesRomRaiderP200() {
        assertEquals(2.36, DerivedColumn.ENGINE_LOAD.compute(mapOf("P12" to 246.2, "P8" to 6254.0))!!, 0.005)
        assertEquals(null, DerivedColumn.ENGINE_LOAD.compute(mapOf("P12" to 3.0, "P8" to 0.0)))
    }
}

class RomRaiderCsvWriterTest {
    @Test
    fun writesRomRaiderStyleHeaderAndBlanksForMissingValues() {
        val preset = LogPreset("T", "t", "t", listOf(LogParameterRef("P8"), LogParameterRef("E94"), LogParameterRef("P21"), LogParameterRef("P12")))
        val layout = LogLayout.resolve(preset, GH8)
        val out = StringWriter()
        val writer = RomRaiderCsvWriter(out, layout)
        writer.writeHeader()
        writer.write(LogRecord(250, mapOf("P8" to 3000.0, "E94" to -1.40625, "P21" to 5.12), emptyMap()))
        val lines = out.toString().trimEnd().lines()
        assertEquals(
            "Time (msec),Engine Speed (rpm),Feedback Knock Correction (1-byte)** (degrees),Fuel Injector #1 Pulse Width (ms),Mass Airflow (g/s)," +
                "Injector Duty Cycle (Calculated) (%),Engine Load (Calculated) (g/rev)",
            lines[0],
        )
        assertEquals("250,3000,-1.41,5.12,,,", lines[1])
    }

    @Test
    fun nonFiniteValuesAreWrittenAsBlanks() {
        val layout = LogLayout.resolve(LogPreset("T", "t", "t", listOf(LogParameterRef("E124", units = "estimated AFR"))), GH8)
        val out = StringWriter()
        RomRaiderCsvWriter(out, layout).write(LogRecord(0, mapOf("E124" to Double.POSITIVE_INFINITY), emptyMap()))
        assertEquals("0,", out.toString().trim())
    }
}

class LoggerSessionTest {
    private val memory = buildMap {
        put(0x0E, 0x66); put(0x0F, 0x18) // 6534 rpm
        put(0x13, 0x60); put(0x14, 0x2C) // MAF 246.20 g/s
        put(0x20, 0x42) // IPW 16.896 ms
        put(0x15, 0xFF) // throttle 100 %
        put(0x23, 101) // 大気圧 101 kPa
        put(0x12, 0x3C) // IAT 20 C
        put(0x08, 0x7C) // coolant 84 C
        (0xFF7080..0xFF70D0).forEach { put(it, 0x40) }
        put(0xFF70AE, 0x06); put(0xFF70AF, 0x36) // MAP 1590 × 0.1333224 = 212.0 kPa 絶対
    }

    @Test
    fun recordsUntilStoppedAndReadsSlowColumnsLessOften() {
        val transport = ScriptedElmTransport(FakeEcu(memory)::respond)
        val out = StringWriter()
        val layout = LogLayout.resolve(LogPresets.FUEL, GH8)
        var now = 0L
        lateinit var session: LoggerSession
        val records = mutableListOf<LogRecord>()
        session = LoggerSession(
            client = Ssm2Client(transport),
            layout = layout,
            writer = RomRaiderCsvWriter(out, layout),
            clock = { now += 10; now },
            onRecord = { r, _ -> records += r; if (records.size == 12) session.requestStop() },
        )
        session.run()

        val lines = out.toString().trimEnd().lines()
        assertEquals(13, lines.size)
        val first = records.first()
        assertEquals(6534.0, first.values.getValue("P8"))
        assertEquals(111.0, first.derived.getValue(DerivedColumn.MANIFOLD_RELATIVE), 0.05)
        assertEquals(100.0, first.values.getValue("P13"))
        assertEquals(20.0, first.values.getValue("P11"))
        assertEquals(92.0, first.derived.getValue(DerivedColumn.INJECTOR_DUTY), 0.1)
        // 温度（遅い列）は 12 件の記録で 2 回だけ読む
        assertEquals(2, transport.sent.count { it == "A800000012" })
        assertTrue(records.all { "P2" in it.values })
    }

    @Test
    fun slowColumnFailureDoesNotStopFastColumnsAndResynchronizes() {
        val ecu = FakeEcu(memory)
        var coolantFailures = 1
        val transport = ScriptedElmTransport { command ->
            if (command == "A800000008" && coolantFailures-- > 0) "NO DATA\r>" else ecu.respond(command)
        }
        val layout = LogLayout.resolve(LogPresets.FUEL, GH8)
        val records = mutableListOf<LogRecord>()
        lateinit var session: LoggerSession
        session = LoggerSession(
            client = Ssm2Client(transport),
            layout = layout,
            writer = RomRaiderCsvWriter(StringWriter(), layout),
            onRecord = { r, _ -> records += r; if (records.size == 3) session.requestStop() },
        )
        session.run()
        assertEquals(3, records.size)
        assertTrue("ATE0" in transport.sent, "resync after the failed slow read")
        assertTrue(records.last().values.containsKey("P2"), "slow column read again on a later cycle")
    }

    @Test
    fun stopsAfterRepeatedFailures() {
        val layout = LogLayout.resolve(LogPresets.SHIFT, GH8)
        val session = LoggerSession(
            client = Ssm2Client(ScriptedElmTransport { "NO DATA\r>" }),
            layout = layout,
            writer = RomRaiderCsvWriter(StringWriter(), layout),
        )
        assertFailsWith<IllegalStateException> { session.run() }
    }
}

class LogRecordTelemetryTest {
    @Test
    fun copiesLoggedValuesToRaceChronoFieldsAndKeepsTheRest() {
        val before = SubaruTelemetry.EMPTY.copy(batteryVoltage = 13.9)
        val after = before.updatedWith(LogRecord(0, mapOf("P8" to 6000.0, "E93" to 100.0, "P60" to 3.0), emptyMap()), timestampMillis = 5)
        assertEquals(6000.0, after.rpm)
        assertEquals(100.0, after.throttlePercent)
        assertEquals(3, after.gear)
        assertEquals(13.9, after.batteryVoltage)
        assertEquals(0, before.updatedWith(LogRecord(0, mapOf("P60" to 256.0), emptyMap()), 0).gear)
        val boosted = before.updatedWith(LogRecord(0, emptyMap(), mapOf(DerivedColumn.MANIFOLD_RELATIVE to 121.0)), 0)
        assertEquals(121.0, boosted.boostKpa)
    }
}
