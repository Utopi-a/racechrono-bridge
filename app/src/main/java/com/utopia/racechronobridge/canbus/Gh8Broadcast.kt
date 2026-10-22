package com.utopia.racechronobridge.canbus

/**
 * Signals decoded from the GH8 (2007-2011 Impreza, MT, no VDC) CAN broadcast on the OBD port.
 *
 * Layouts were identified from a 5-minute drive capture. Scales marked "estimated" were inferred
 * without a reference reading and should be confirmed against SSM2.
 */
enum class CanSignal(
    val key: String,
    val label: String,
    val unit: String,
) {
    RPM("rpm", "RPM", "rpm"),
    VEHICLE_SPEED("speed", "Vehicle speed", "km/h"),
    WHEEL_1("wheel1", "Wheel speed 1", "km/h"),
    WHEEL_2("wheel2", "Wheel speed 2", "km/h"),
    WHEEL_3("wheel3", "Wheel speed 3", "km/h"),
    WHEEL_4("wheel4", "Wheel speed 4", "km/h"),
    ACCELERATOR("accelerator", "Accelerator", "%"),
    BRAKE("brake", "Brake", ""),
    CLUTCH("clutch", "Clutch", ""),
    HANDBRAKE("handbrake", "Handbrake", ""),
    GEAR("gear", "Gear", ""),
    COOLANT("coolant", "Coolant", "C"),
    ;

    companion object {
        fun fromKey(key: String): CanSignal? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

class Gh8BroadcastDecoder {
    private val values = mutableMapOf<CanSignal, Double>()

    @Synchronized
    fun accept(frame: MonitorLine.Frame) {
        val data = frame.bytes() ?: return
        when (frame.id) {
            "410" -> {
                values[CanSignal.RPM] = word(data[5], data[6]).toDouble()
                values[CanSignal.ACCELERATOR] = data[4] * ACCELERATOR_PERCENT_PER_BIT
            }
            "411" -> {
                // 1..5 are gears; 6 appears in neutral or while the clutch is pressed.
                val gear = data[4]
                values[CanSignal.GEAR] = if (gear in 1..5) gear.toDouble() else 0.0
                values[CanSignal.CLUTCH] = flag(data[0], 0x40 or 0x80)
                values[CanSignal.BRAKE] = flag(data[6], 0x10)
            }
            "512" -> values[CanSignal.VEHICLE_SPEED] = word(data[2], data[3]) * SPEED_KPH_PER_BIT
            "513" -> {
                values[CanSignal.WHEEL_1] = word(data[0], data[1]) * SPEED_KPH_PER_BIT
                values[CanSignal.WHEEL_2] = word(data[2], data[3]) * SPEED_KPH_PER_BIT
                values[CanSignal.WHEEL_3] = word(data[4], data[5]) * SPEED_KPH_PER_BIT
                values[CanSignal.WHEEL_4] = word(data[6], data[7]) * SPEED_KPH_PER_BIT
            }
            "514" -> values[CanSignal.HANDBRAKE] = flag(data[0], 0x04)
            "600" -> values[CanSignal.COOLANT] = data[3] - 40.0
        }
    }

    @Synchronized
    fun snapshot(): Map<CanSignal, Double> = values.toMap()

    private fun MonitorLine.Frame.bytes(): IntArray? {
        if (data.length != 16) return null
        return IntArray(8) { index -> data.substring(index * 2, index * 2 + 2).toInt(16) }
    }

    private fun word(low: Int, high: Int): Int = (high shl 8) or low

    private fun flag(value: Int, mask: Int): Double = if (value and mask != 0) 1.0 else 0.0

    companion object {
        // Estimated: matches 5th-gear rpm/speed for GH8 tyres, not yet checked against SSM2.
        const val SPEED_KPH_PER_BIT = 0.05625

        // Estimated: 0x7D was the highest raw value in the first capture; a full-pedal reading is not confirmed.
        const val ACCELERATOR_PERCENT_PER_BIT = 100.0 / 125.0
    }
}
