package io.github.panevx.csearchrevamped

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Vibrator::class.java)
        }

    private fun play(c: Context, effect: Int) {
        val v = vibrator(c) ?: return
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(effect))
    }

    fun tick(c: Context) = play(c, VibrationEffect.EFFECT_TICK)
    fun click(c: Context) = play(c, VibrationEffect.EFFECT_CLICK)
    fun heavy(c: Context) = play(c, VibrationEffect.EFFECT_HEAVY_CLICK)
}
