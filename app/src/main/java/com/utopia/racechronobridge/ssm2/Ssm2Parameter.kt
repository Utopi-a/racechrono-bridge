package com.utopia.racechronobridge.ssm2

enum class Ssm2Parameter(
    val command: String,
) {
    COOLANT("A800000008"),
    BOOST("A800000024"),
    RPM_HIGH("A80000000E"),
    RPM_LOW("A80000000F"),
}
