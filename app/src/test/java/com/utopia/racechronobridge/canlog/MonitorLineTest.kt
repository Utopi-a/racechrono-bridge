package com.utopia.racechronobridge.canlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonitorLineTest {
    @Test
    fun parsesFramesWithAndWithoutSpaces() {
        assertEquals(
            MonitorLine.Frame(id = "410", data = "09001B0F00E702C1"),
            MonitorLine.parse("410 09 00 1B 0F 00 E7 02 C1"),
        )
        assertEquals(
            MonitorLine.Frame(id = "514", data = "02007706E81C0B00"),
            MonitorLine.parse("51402007706E81C0B00"),
        )
    }

    @Test
    fun treatsAdapterMessagesAsStatusAndIgnoresBlankLines() {
        assertEquals(MonitorLine.Status("BUFFER FULL"), MonitorLine.parse("BUFFER FULL"))
        assertEquals(MonitorLine.Status("STOPPED"), MonitorLine.parse(">STOPPED"))
        assertNull(MonitorLine.parse("  "))
        assertNull(MonitorLine.parse(">"))
    }
}
