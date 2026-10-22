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

    /** Puts the GH8 CAN-only signals on the slots the tuning preset does not depend on most. */
    val canSignalPresetCsv: String =
        """
        slot,label,unit,address,bytes,scale,offset,mode,signed
        Analog 9,Brake,,can:brake,1,1,0,Fast,false
        Analog 10,Clutch,,can:clutch,1,1,0,Fast,false
        Analog 11,Wheel 1,km/h,can:wheel1,1,1,0,Fast,false
        Analog 12,Wheel 2,km/h,can:wheel2,1,1,0,Fast,false
        Analog 13,Wheel 3,km/h,can:wheel3,1,1,0,Fast,false
        Analog 14,Wheel 4,km/h,can:wheel4,1,1,0,Fast,false
        Analog 15,Handbrake,,can:handbrake,1,1,0,Fast,false
        """.trimIndent()
}
