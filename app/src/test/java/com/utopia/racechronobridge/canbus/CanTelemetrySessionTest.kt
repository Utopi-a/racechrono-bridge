package com.utopia.racechronobridge.canbus

import com.utopia.racechronobridge.elm.Elm327Session
import com.utopia.racechronobridge.elm.Elm327Transport
import com.utopia.racechronobridge.ssm2.CustomTelemetryChannelParser
import com.utopia.racechronobridge.ssm2.Ssm2Reader
import com.utopia.racechronobridge.ssm2.SubaruTelemetry
import com.utopia.racechronobridge.ssm2.TelemetryChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanTelemetrySessionTest {
    @Test
    fun alternatesBetweenMonitorWindowsAndSsm2BurstsForNonCanChannels() {
        val transport = FakeAdapter()
        val elm = Elm327Session(client = transport, onRawResponse = {}, onLog = {})
        val customChannels = CustomTelemetryChannelParser.parse(
            "slot,label,unit,address,bytes,scale,offset,mode,signed\nAnalog 9,Brake,,can:brake,1,1,0,Fast,false",
        ).channels
        val published = mutableListOf<SubaruTelemetry>()
        lateinit var session: CanTelemetrySession
        transport.afterSsm2Burst = { session.requestStop() }
        session = CanTelemetrySession(
            transport = transport,
            elmSession = elm,
            ssm2Reader = Ssm2Reader(elm),
            channelModes = { TelemetryChannel.defaultModes() },
            customChannels = { customChannels },
            onTelemetry = { published += it },
            onLog = {},
            nowMillis = { transport.clock },
        )

        session.run()

        val commands = transport.commands
        val burstStart = commands.indexOf("ATH0")
        assertEquals(listOf("ATH1", "ATCAF0"), commands.take(2))
        assertEquals("ATCAF1", commands[burstStart + 1])
        assertEquals(listOf("ATH1", "ATCAF0"), commands.takeLast(2))
        val ssm2Reads = commands.filter { it.startsWith("A8") }
        assertTrue(ssm2Reads.isNotEmpty())
        // RPM, coolant, accelerator, speed and gear come from CAN, so SSM2 must not poll them.
        val canSourcedAddresses = listOf("A80000000E", "A80000000F", "A800000008", "A800000029", "A800000010", "A80000004A")
        assertFalse(ssm2Reads.any { it in canSourcedAddresses }, "unexpected SSM2 reads: $ssm2Reads")

        val latest = published.last()
        assertEquals(1610.0, latest.rpm)
        assertEquals(33.0, latest.coolantC)
        assertEquals(1.0, latest.customValues.getValue(customChannels.single().id))
    }

    private class FakeAdapter : Elm327Transport {
        val commands = mutableListOf<String>()
        var clock = 0L
        var afterSsm2Burst: () -> Unit = {}
        private var monitoring = false
        private var stopPending = false
        private var inSsm2 = false

        override fun sendCommand(command: String, timeoutMillis: Long): String {
            commands += command
            when (command) {
                "ATH0" -> inSsm2 = true
                "ATCAF0" -> if (inSsm2) {
                    inSsm2 = false
                    afterSsm2Burst()
                }
            }
            return if (command.startsWith("A8")) "E880\r\r>" else "OK\r\r>"
        }

        override fun writeRaw(text: String) {
            when (text) {
                "ATMA\r" -> monitoring = true
                "\r" -> stopPending = true
            }
        }

        override fun readAvailable(timeoutMillis: Long): String {
            clock += 100
            if (stopPending) {
                stopPending = false
                monitoring = false
                return "STOPPED\r\r>"
            }
            return if (monitoring) {
                "41024003F12004A06E1\r600009B0549BD2202FF\r41105FF7F0001001004\r"
            } else {
                ""
            }
        }

        override fun close() = Unit
    }
}
