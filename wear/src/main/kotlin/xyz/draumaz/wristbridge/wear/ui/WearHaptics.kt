package xyz.draumaz.wristbridge.wear.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Expressive haptic feedback for watch toggle interactions.
 */
object WearHaptics {

    fun playToggle(context: Context, turningOn: Boolean, isMomentary: Boolean = false) {
        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> buildExpressiveEffect(vibrator, turningOn, isMomentary)
            else -> VibrationEffect.createPredefined(
                if (turningOn || isMomentary) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK,
            )
        }
        runCatching { vibrator.vibrate(effect) }
    }

    private fun buildExpressiveEffect(
        vibrator: Vibrator,
        turningOn: Boolean,
        isMomentary: Boolean,
    ): VibrationEffect {
        val primitives = intArrayOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        )
        val supported = vibrator.areAllPrimitivesSupported(*primitives)
        if (!supported) {
            return VibrationEffect.createPredefined(
                if (turningOn || isMomentary) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK,
            )
        }

        val composition = VibrationEffect.startComposition()
        when {
            isMomentary -> {
                composition
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 35)
            }
            turningOn -> {
                // Crisp kinetic "fill-in" feel: snappy click followed by a quick upward swell
                composition
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.75f, 20)
            }
            else -> {
                // Soft "empty-out" release: quick downward tap
                composition
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.65f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f, 15)
            }
        }
        return composition.compose()
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
