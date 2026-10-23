package com.utopia.racechronobridge.ssm2

import com.utopia.racechronobridge.elm.Elm327Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 送られたコマンドを記録し、決めた応答を返す ELM327 の代役。 */
class ScriptedElmTransport(private val respond: (String) -> String) : Elm327Transport {
    val sent = mutableListOf<String>()
    override fun sendCommand(command: String, timeoutMillis: Long): String {
        sent += command
        return respond(command)
    }
    override fun writeRaw(text: String) = Unit
    override fun readAvailable(timeoutMillis: Long) = ""
    override fun close() = Unit
}

/** ECU の RAM を模した応答を作る。A8 は 1 バイト、A0 は複数フレームで返す。 */
class FakeEcu(private val memory: Map<Int, Int>, private val acceptBlockReads: Boolean = true) {
    fun respond(command: String): String = when {
        command.startsWith("A800") -> {
            val address = command.substring(4).toInt(16)
            "02 E8 %02X\r>".format(memory[address] ?: 0)
        }
        command.startsWith("A000") && !acceptBlockReads -> "03 7F A0 12\r>"
        command.startsWith("A000") -> {
            val start = command.substring(4, 10).toInt(16)
            val count = command.substring(10, 12).toInt(16) + 1
            multiFrame(listOf(0xE0) + (start until start + count).map { memory[it] ?: 0 })
        }
        command == "AA" -> multiFrame(listOf(0xEA, 0x00, 0x00, 0x00, 0x51, 0x04, 0x58, 0x40, 0x07) + List(48) { 0xFF })
        else -> "?\r>"
    }

    private fun multiFrame(payload: List<Int>): String {
        if (payload.size <= 7) return payload.joinToString(" ") { "%02X".format(it) } + "\r>"
        val lines = mutableListOf("%03X".format(payload.size))
        payload.chunked(7).forEachIndexed { i, chunk ->
            lines += "%X:".format(i % 16) + chunk.joinToString("") { "%02X".format(it) } + "AA".repeat(7 - chunk.size)
        }
        return lines.joinToString("\r") + "\r\r>"
    }
}

class Ssm2ClientTest {
    private val memory = (0xFF7080..0xFF70D0).associateWith { it and 0xFF } + mapOf(0x0E to 0x2E, 0x0F to 0xE0)

    @Test
    fun identifiesEcuIdFromRomId() {
        val client = Ssm2Client(ScriptedElmTransport(FakeEcu(memory)::respond))
        assertEquals("5104584007", client.identify().ecuId)
    }

    @Test
    fun blockReadReturnsContiguousBytes() {
        val transport = ScriptedElmTransport(FakeEcu(memory)::respond)
        val bytes = Ssm2Client(transport).readBlock(0xFF708C, 64)
        assertEquals((0x8C..0xCB).toList(), bytes)
        assertEquals(listOf("A000FF708C3F"), transport.sent)
    }

    @Test
    fun addressReadUsesSingleFrameCommand() {
        val transport = ScriptedElmTransport(FakeEcu(memory)::respond)
        assertEquals(0x2E, Ssm2Client(transport).readAddress(0x0E))
        assertEquals(listOf("A80000000E"), transport.sent)
    }

    @Test
    fun rejectedBlockReadFails() {
        val client = Ssm2Client(ScriptedElmTransport(FakeEcu(memory, acceptBlockReads = false)::respond))
        assertFailsWith<Ssm2RequestFailed> { client.readBlock(0xFF708C, 4) }
    }

    @Test
    fun shortBlockResponseFails() {
        val client = Ssm2Client(ScriptedElmTransport { "E0 01 02\r>" })
        assertFailsWith<Ssm2RequestFailed> { client.readBlock(0xFF708C, 4) }
    }
}

class Ssm2ReadPlannerTest {
    @Test
    fun groupsNearbyRamAddressesAndReadsStandardOnesSingly() {
        // GH8 の 1〜2 バイト版拡張パラメータの実際の並び（隙間は最大 13 バイト）
        val gh8Cluster = listOf(0xFF708C, 0xFF708E, 0xFF7090, 0xFF7094, 0xFF7098, 0xFF709A, 0xFF709E, 0xFF70A0, 0xFF70AE, 0xFF70B2, 0xFF70B4, 0xFF70BA, 0xFF70BC, 0xFF70C6, 0xFF70C9, 0xFF70CB)
        val plan = Ssm2ReadPlanner.plan(listOf(0x0E, 0x0F) + gh8Cluster)
        assertEquals(
            listOf(Ssm2Read.Block(0xFF708C, 0xFF70CB - 0xFF708C + 1), Ssm2Read.Address(0x0E), Ssm2Read.Address(0x0F)),
            plan,
        )
    }

    @Test
    fun splitsAtLargeGapsAndMaxBlockSize() {
        val far = Ssm2ReadPlanner.plan(listOf(0xFF7000, 0xFF7000 + Ssm2ReadPlanner.MAX_GAP + 2))
        assertEquals(2, far.size)
        val long = Ssm2ReadPlanner.plan((0xFF7000 until 0xFF7000 + Ssm2ReadPlanner.MAX_BLOCK_BYTES + 1).toList())
        assertEquals(listOf(Ssm2Read.Block(0xFF7000, Ssm2ReadPlanner.MAX_BLOCK_BYTES), Ssm2Read.Block(0xFF7000 + Ssm2ReadPlanner.MAX_BLOCK_BYTES, 1)), long)
    }
}

class Ssm2SamplerTest {
    private val memory = (0xFF7080..0xFF70D0).associateWith { it and 0xFF } + mapOf(0x0E to 0x2E, 0x0F to 0xE0)
    private val wanted = listOf(0x0E, 0x0F, 0xFF708C, 0xFF7094, 0xFF709E, 0xFF709F, 0xFF70A0)

    @Test
    fun readsEveryWantedAddressWithOneBlockRequest() {
        val transport = ScriptedElmTransport(FakeEcu(memory)::respond)
        val sample = Ssm2Sampler(Ssm2Client(transport), wanted).sample()
        assertEquals(wanted.associateWith { memory.getValue(it) }, sample.bytes)
        assertEquals(3, sample.requests) // ブロック 1 + RPM の 2 バイト
    }

    @Test
    fun fallsBackToAddressReadsWhenEcuRejectsBlocksAndDoesNotRetryBlocks() {
        val transport = ScriptedElmTransport(FakeEcu(memory, acceptBlockReads = false)::respond)
        val logs = mutableListOf<String>()
        val sampler = Ssm2Sampler(Ssm2Client(transport), wanted, onLog = { logs += it })

        val first = sampler.sample()
        assertEquals(wanted.associateWith { memory.getValue(it) }, first.bytes)
        assertEquals(1, logs.size)

        transport.sent.clear()
        val second = sampler.sample()
        assertEquals(wanted.associateWith { memory.getValue(it) }, second.bytes)
        assertEquals(0, transport.sent.count { it.startsWith("A0") })
        assertEquals(wanted.size, second.requests)
    }
}
