package dev.exact.canvas

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * An app's haptic (`haptic()`, a pressable's `-exact-press-haptic`), by exact2's number for it (`presenter::Haptic`),
 * played on the vibrator (Pinkoo's fork, C13). `View.performHapticFeedback` played nothing a person could feel on a
 * cheap motor (a Galaxy A06), and Android's touch-feedback switch silences it; an app that has its own switch for its
 * haptics (and asks for none while it is off) wants them felt. So each kind is one of the platform's predefined
 * effects, which the phone tunes for its motor:
 *
 * - `selection` (1), `impact-light` (2) and `impact-soft` (5) the tick;
 * - `impact-medium` (3) and `impact-rigid` (6) the click;
 * - `impact-heavy` (4) and `success` (7) the heavy click;
 * - `warning` (8) and `error` (9) the double click.
 *
 * Where the vibrator doesn't say it has the effect (Android 11 answers; before, nothing is known), a one-shot of
 * [TICK_MS] to [HEAVY_MS] at full amplitude, or two for the double click, plays instead. The attributes are hardware
 * feedback's from Android 13: neither the touch-feedback switch nor battery saver mutes them (touch's are muted by
 * both), and the phone's master vibration switch still does. Before, a vibration without attributes, which the
 * touch-feedback switch doesn't mute either.
 */
internal class AppHaptics(context: Context, private val supports: (Vibrator, Int) -> Boolean = Companion::has) {
    private val vibrator: Vibrator? = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java))?.takeIf { it.hasVibrator() }

    /** Plays [kind]; what played, or null (no vibrator, or a kind this reader doesn't know). */
    fun play(kind: Int): VibrationEffect? {
        val v = vibrator ?: return null
        val id = predefined(kind) ?: return null
        val effect = if (supports(v, id)) VibrationEffect.createPredefined(id) else oneShot(id)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
        else v.vibrate(effect)
        return effect
    }

    internal companion object {
        /** Whether [v] says it has the predefined effect [id] (Android 11; before, nothing is known). */
        fun has(v: Vibrator, id: Int): Boolean =
            Build.VERSION.SDK_INT >= 30 && v.areEffectsSupported(id)[0] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES

        /** The predefined effect for exact2's haptic [kind], or null. */
        fun predefined(kind: Int): Int? = when (kind) {
            1, 2, 5 -> VibrationEffect.EFFECT_TICK
            3, 6 -> VibrationEffect.EFFECT_CLICK
            4, 7 -> VibrationEffect.EFFECT_HEAVY_CLICK
            8, 9 -> VibrationEffect.EFFECT_DOUBLE_CLICK
            else -> null
        }

        /** The one-shot a vibrator without the predefined effect [id] plays: long enough for a cheap motor to spin up. */
        fun oneShot(id: Int): VibrationEffect = when (id) {
            VibrationEffect.EFFECT_TICK -> VibrationEffect.createOneShot(TICK_MS, FULL)
            VibrationEffect.EFFECT_HEAVY_CLICK -> VibrationEffect.createOneShot(HEAVY_MS, FULL)
            VibrationEffect.EFFECT_DOUBLE_CLICK -> VibrationEffect.createWaveform(
                longArrayOf(0, CLICK_MS, DOUBLE_GAP_MS, CLICK_MS), intArrayOf(0, FULL, 0, FULL), -1)
            else -> VibrationEffect.createOneShot(CLICK_MS, FULL)
        }

        const val TICK_MS = 20L
        const val CLICK_MS = 30L
        const val HEAVY_MS = 40L
        const val DOUBLE_GAP_MS = 80L
        const val FULL = 255
    }
}
