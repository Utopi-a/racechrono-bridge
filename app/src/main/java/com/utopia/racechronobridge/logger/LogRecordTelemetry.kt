package com.utopia.racechronobridge.logger

import com.utopia.racechronobridge.ssm2.SubaruTelemetry

/**
 * 記録中も RaceChrono への出力を続けるため、記録した値を RC3 の欄に写す。
 * 同じ ECU 値を読む欄だけに写し、記録していない欄は前回の値のまま残す。
 */
fun SubaruTelemetry.updatedWith(record: LogRecord, timestampMillis: Long): SubaruTelemetry {
    val v = record.values
    return copy(
        rpm = v["P8"] ?: rpm,
        boostKpa = record.derived[DerivedColumn.MANIFOLD_RELATIVE] ?: v["P25"] ?: boostKpa,
        coolantC = v["P2"] ?: coolantC,
        throttlePercent = v["P13"] ?: v["E93"] ?: throttlePercent,
        acceleratorPercent = v["P30"] ?: acceleratorPercent,
        primaryWastegateDutyPercent = v["P36"] ?: primaryWastegateDutyPercent,
        vehicleSpeedKph = v["P9"] ?: vehicleSpeedKph,
        // 0xFF（未取得）は x+1 で 256 になる。既存の SSM2 読み出しと同じく 1〜8 以外は不明（0）にする
        gear = v["P60"]?.toInt()?.let { if (it in 1..8) it else 0 } ?: gear,
        intakeAirTempC = v["P11"] ?: intakeAirTempC,
        batteryVoltage = v["P17"] ?: batteryVoltage,
        massAirflowGps = v["P12"] ?: massAirflowGps,
        ignitionTimingDeg = v["P10"] ?: ignitionTimingDeg,
        knockCorrectionDeg = v["P23"] ?: knockCorrectionDeg,
        learnedIgnitionTimingDeg = v["P29"] ?: learnedIgnitionTimingDeg,
        injectorPulseWidthMs = v["P21"] ?: injectorPulseWidthMs,
        timestampMillis = timestampMillis,
    )
}
