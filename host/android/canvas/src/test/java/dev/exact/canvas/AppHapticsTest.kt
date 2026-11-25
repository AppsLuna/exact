package dev.exact.canvas

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Pinkoo's fork, C13: an app's haptic on the vibrator, an explicit one-shot or waveform as an alarm (LagX's recipe). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AppHapticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val vibrator get() = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)

    private fun usage() = (vibrator.vibrationAttributesFromLastVibration as VibrationAttributes).usage

    /** Each kind's duration and amplitude, the `vibration` plugin's numbers LagX plays. */
    @Test fun eachKindIsAnExplicitDurationAndAmplitude() {
        val shots = mapOf(1 to (25L to 110), 2 to (30L to 140), 5 to (30L to 140), 3 to (40L to 190), 6 to (40L to 190), 4 to (55L to 255))
        for ((kind, shot) in shots) assertEquals(VibrationEffect.createOneShot(shot.first, shot.second), AppHaptics.effect(kind, true))
        assertEquals(VibrationEffect.createWaveform(longArrayOf(0, 35, 45, 45), intArrayOf(0, 160, 0, 255), -1), AppHaptics.effect(7, true))
        for (kind in listOf(8, 9))
            assertEquals(VibrationEffect.createWaveform(longArrayOf(0, 55, 55, 55), intArrayOf(0, 255, 0, 255), -1), AppHaptics.effect(kind, true))
        assertNull(AppHaptics.effect(0, true))
        assertNull(AppHaptics.effect(10, true))
    }

    /** Without amplitude control, the same durations at the vibrator's default amplitude. */
    @Test fun withoutAmplitudeControlTheDefaultAmplitude() {
        assertEquals(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE), AppHaptics.effect(1, false))
        assertEquals(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE), AppHaptics.effect(4, false))
        assertEquals(VibrationEffect.createWaveform(longArrayOf(0, 35, 45, 45), -1), AppHaptics.effect(7, false))
        assertEquals(VibrationEffect.createWaveform(longArrayOf(0, 55, 55, 55), -1), AppHaptics.effect(9, false))
    }

    /** On the vibrator, as an alarm: the touch-feedback switch and battery saver don't mute it. */
    @Test fun itPlaysOnTheVibratorAsAnAlarm() {
        vibrator.setHasAmplitudeControl(true)
        val haptics = AppHaptics(context)
        for ((kind, shot) in listOf(3 to (40L to 190), 1 to (25L to 110), 4 to (55L to 255))) {
            val (ms, level) = shot
            assertEquals(VibrationEffect.createOneShot(ms, level), haptics.play(kind))
            assertEquals(ms, vibrator.milliseconds)
            assertEquals(VibrationAttributes.USAGE_ALARM, usage())
        }
        haptics.play(8)
        assertArrayEquals(longArrayOf(0, 55, 55, 55), vibrator.pattern)
        assertEquals(VibrationAttributes.USAGE_ALARM, usage())
        assertNull(haptics.play(0))
    }

    /** A motor without amplitude control (the shadow's default): the default amplitude, still as an alarm. */
    @Test fun aMotorWithoutAmplitudeControlPlaysTheDefault() {
        vibrator.setHasAmplitudeControl(false)
        assertEquals(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE), AppHaptics(context).play(3))
        assertEquals(40L, vibrator.milliseconds)
        assertEquals(VibrationAttributes.USAGE_ALARM, usage())
    }

    /** A phone without a vibrator: nothing, and no failure. */
    @Test fun noVibratorPlaysNothing() {
        vibrator.setHasVibrator(false)
        assertNull(AppHaptics(context).play(3))
        assertFalse(vibrator.isVibrating)
    }

    /** Exact's haptic request (kind 2: `haptic()`, a press's `-exact-press-haptic`) ends on the vibrator. */
    @Test fun theHapticRequestVibrates() {
        val requests = Requests(Robolectric.buildActivity(ExactActivity::class.java).get())
        requests.perform(intArrayOf(2, 1, 1, 3))
        assertTrue(vibrator.isVibrating)
        assertEquals(40L, vibrator.milliseconds)
        assertEquals(VibrationAttributes.USAGE_ALARM, usage())
    }

    /** Before Android 13, an alarm's audio attributes, as the plugin hands them (Android 12 makes them an alarm's vibration). */
    @Test @Config(sdk = [31]) fun beforeAndroid13AnAlarmsAudioAttributes() {
        assertEquals(AudioAttributes.USAGE_ALARM, AppHaptics.ALARM.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, AppHaptics.ALARM.contentType)
        AppHaptics(context).play(4)
        assertEquals(55L, vibrator.milliseconds)
        assertEquals(VibrationAttributes.USAGE_ALARM, usage())
    }
}
