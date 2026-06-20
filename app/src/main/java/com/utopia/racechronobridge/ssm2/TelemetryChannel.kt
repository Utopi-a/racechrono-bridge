package com.utopia.racechronobridge.ssm2

enum class TelemetryChannel(
    val label: String,
    val unit: String,
    val defaultMode: ChannelMode,
    val rc3Field: String,
) {
    RPM("RPM", "rpm", ChannelMode.FAST, "Digital 1/RPM"),
    GEAR("Gear", "", ChannelMode.SLOW, "Digital 2"),
    BOOST("Boost", "kPa", ChannelMode.FAST, "Analog 1"),
    COOLANT("Coolant", "C", ChannelMode.SLOW, "Analog 2"),
    THROTTLE("Throttle", "%", ChannelMode.FAST, "Analog 3"),
    ACCELERATOR("Accelerator", "%", ChannelMode.FAST, "Analog 4"),
    PRIMARY_WGDC("Primary WGDC", "%", ChannelMode.SLOW, "Analog 5"),
    VEHICLE_SPEED("Vehicle speed", "km/h", ChannelMode.FAST, "Analog 6"),
    INTAKE_AIR_TEMP("Intake air temp", "C", ChannelMode.SLOW, "Analog 7"),
    BATTERY_VOLTAGE("Battery", "V", ChannelMode.SLOW, "Analog 8"),
    MASS_AIRFLOW("Mass airflow", "g/s", ChannelMode.SLOW, "Analog 9"),
    IGNITION_TIMING("Ignition timing", "deg", ChannelMode.SLOW, "Analog 10"),
    KNOCK_CORRECTION("Knock correction", "deg", ChannelMode.SLOW, "Analog 11"),
    LEARNED_IGNITION("Learned ignition", "deg", ChannelMode.SLOW, "Analog 12"),
    INJECTOR_PULSE_WIDTH("Injector pulse", "ms", ChannelMode.SLOW, "Analog 13"),
    FUEL_PUMP_DUTY("Fuel pump", "%", ChannelMode.SLOW, "Analog 14"),
    ALTERNATOR_DUTY("Alternator", "%", ChannelMode.SLOW, "Analog 15");

    val preferenceKey: String = "channel_${name.lowercase()}_mode"
    val mappingLabel: String = listOf(label, unit.takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" ")

    companion object {
        fun defaultModes(): MutableMap<TelemetryChannel, ChannelMode> {
            return entries.associateWith { it.defaultMode }.toMutableMap()
        }
    }
}

fun Map<TelemetryChannel, ChannelMode>.modeFor(channel: TelemetryChannel): ChannelMode {
    return this[channel] ?: channel.defaultMode
}
