package com.utopia.racechronobridge.logger

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 警報を音とバイブで知らせる。RaceChrono を前面に出していても鳴るよう、フォアグラウンドサービス側から呼ぶ。
 * 薄い・過給超過・開弁率は長い高音、ノック補正は短い 2 回の音にして、見なくても区別できるようにする。
 */
class AlarmNotifier(context: Context) {
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun notify(event: AlarmEvent) {
        val severe = event.kind != AlarmKind.KNOCK
        tone?.startTone(if (severe) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP2, if (severe) 900 else 300)
        val pattern = if (severe) longArrayOf(0, 400, 150, 400) else longArrayOf(0, 120, 100, 120)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun release() {
        tone?.release()
    }
}
