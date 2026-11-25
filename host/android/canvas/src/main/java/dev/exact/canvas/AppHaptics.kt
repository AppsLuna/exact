package dev.exact.canvas

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * An app's haptic (`haptic()`, a pressable's `-exact-press-haptic`), by exact2's number for it (`presenter::Haptic`),
 * played on the vibrator with an explicit duration and amplitude (Pinkoo's fork, C13). `View.performHapticFeedback`
 * and the platform's predefined effects (the tick, the click…) played nothing a person could feel on a cheap motor (a
 * Galaxy A06), while the same phone felt every tap of an app that drives the motor itself, the Flutter `vibration`
 * plugin's way: a one-shot or a waveform as an alarm. So each kind is that recipe:
 *
 * - `selection` (1) a one-shot of 25 ms at 110; `impact-light` (2) and `impact-soft` (5) 30 ms at 140;
 * - `impact-medium` (3) and `impact-rigid` (6) 40 ms at 190; `impact-heavy` (4) 55 ms at 255;
 * - `success` (7) two pulses, 35 ms at 160 and, 45 ms later, 45 ms at 255;
 * - `warning` (8) and `error` (9) two 55 ms pulses at 255, 55 ms apart.
 *
 * A vibrator without amplitude control plays the same durations at its default amplitude. The attributes are an
 * alarm's (from Android 13 `VibrationAttributes`, before an alarm's `AudioAttributes`, as the plugin does): neither
 * the touch-feedback switch nor battery saver mutes them, and an app that has its own switch for its haptics asks for
 * none while it is off.
 */
internal class AppHaptics(context: Context) {
    private val vibrator: Vibrator? = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java))?.takeIf { it.hasVibrator() }
    private val amplitude = vibrator?.hasAmplitudeControl() == true

    /** Plays [kind]; what played, or null (no vibrator, or a kind this reader doesn't know). */
    fun play(kind: Int): VibrationEffect? {
        val v = vibrator ?: return null
        val effect = effect(kind, amplitude) ?: return null
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") v.vibrate(effect, ALARM)
        return effect
    }

    internal companion object {
        /** An alarm's audio attributes, as the `vibration` plugin hands them (before Android 13). */
        val ALARM: AudioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_ALARM).build()

        /** The effect for exact2's haptic [kind] on a vibrator with or without [amplitude] control, or null. */
        fun effect(kind: Int, amplitude: Boolean): VibrationEffect? {
            fun oneShot(ms: Long, level: Int) = VibrationEffect.createOneShot(ms, if (amplitude) level else VibrationEffect.DEFAULT_AMPLITUDE)
            fun twice(timings: LongArray, levels: IntArray) =
                if (amplitude) VibrationEffect.createWaveform(timings, levels, -1) else VibrationEffect.createWaveform(timings, -1)
            return when (kind) {
                1 -> oneShot(25, 110)
                2, 5 -> oneShot(30, 140)
                3, 6 -> oneShot(40, 190)
                4 -> oneShot(55, 255)
                7 -> twice(longArrayOf(0, 35, 45, 45), intArrayOf(0, 160, 0, 255))
                8, 9 -> twice(longArrayOf(0, 55, 55, 55), intArrayOf(0, 255, 0, 255))
                else -> null
            }
        }
    }
}
