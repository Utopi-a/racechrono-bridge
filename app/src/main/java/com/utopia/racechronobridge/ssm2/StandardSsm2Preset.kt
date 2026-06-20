package com.utopia.racechronobridge.ssm2

object StandardSsm2Preset {
    val tuningPresetCsv: String =
        """
        slot,label,unit,address,bytes,scale,offset,mode,signed
        Analog 10,A/F Correction #1,%,0x000009,1,0.78125,-100,Slow,false
        Analog 11,A/F Learning #1,%,0x00000A,1,0.78125,-100,Slow,false
        Analog 12,IAM,mult,0x0000F9,1,0.0625,0,Slow,false
        Analog 13,Fine knock learn,deg,0x000199,1,0.25,-32,Slow,false
        Analog 14,A/F Sensor #1,AFR,0x000046,1,0.11484375,0,Slow,false
        Analog 15,Engine Load,%,0x000007,1,0.3921568627,0,Slow,false
        """.trimIndent()
}
