package dev.exact.canvas

import android.content.Context
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
import org.robolectric.shadows.ShadowVibrator
import org.robolectric.util.ReflectionHelpers

/** Pinkoo's fork, C13: an app's haptic on the vibrator, a predefined effect or a one-shot a cheap motor feels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AppHapticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val vibrator get() = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)

    private fun usage() = (vibrator.vibrationAttributesFromLastVibration as VibrationAttributes).usage

    /** The predefined effect the vibrator was last handed: its one segment's id (the shadow keeps the segments). */
    private fun played(): Int {
        val segments = ReflectionHelpers.getStaticField<List<Any>>(ShadowVibrator::class.java, "vibrationEffectSegments")
        assertEquals(1, segments.size)
        return segments[0].javaClass.getMethod("getEffectId").invoke(segments[0]) as Int
    }

    @Test fun eachKindIsAPredefinedEffect() {
        val ids = (1..9).map { AppHaptics.predefined(it) }
        assertEquals(listOf(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK,
            VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK,
            VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK), ids)
        assertNull(AppHaptics.predefined(0))
        assertNull(AppHaptics.predefined(10))
    }

    /** A vibrator that has the effects plays them, as hardware feedback: the touch-feedback switch doesn't mute it. */
    @Test fun aSupportedEffectIsRequestedOnTheVibrator() {
        val haptics = AppHaptics(context) { _, _ -> true }
        for ((kind, id) in listOf(3 to VibrationEffect.EFFECT_CLICK, 1 to VibrationEffect.EFFECT_TICK,
            7 to VibrationEffect.EFFECT_HEAVY_CLICK, 4 to VibrationEffect.EFFECT_HEAVY_CLICK, 9 to VibrationEffect.EFFECT_DOUBLE_CLICK)) {
            assertEquals(VibrationEffect.createPredefined(id), haptics.play(kind))
            assertEquals(id, played())
            assertEquals(VibrationAttributes.USAGE_HARDWARE_FEEDBACK, usage())
        }
        assertNull(haptics.play(0))
    }

    /** Without the effect, a one-shot at full amplitude long enough for a cheap motor, or two for the double click. */
    @Test fun withoutTheEffectAOneShotPlays() {
        val haptics = AppHaptics(context) { _, _ -> false }
        for ((kind, ms) in listOf(1 to AppHaptics.TICK_MS, 3 to AppHaptics.CLICK_MS, 4 to AppHaptics.HEAVY_MS)) {
            assertEquals(VibrationEffect.createOneShot(ms, AppHaptics.FULL), haptics.play(kind))
            assertEquals(ms, vibrator.milliseconds)
            assertEquals(VibrationAttributes.USAGE_HARDWARE_FEEDBACK, usage())
        }
        haptics.play(8)
        assertArrayEquals(longArrayOf(0, AppHaptics.CLICK_MS, AppHaptics.DOUBLE_GAP_MS, AppHaptics.CLICK_MS), vibrator.pattern)
    }

    /** A phone without a vibrator: nothing, and no failure. */
    @Test fun noVibratorPlaysNothing() {
        vibrator.setHasVibrator(false)
        assertNull(AppHaptics(context) { _, _ -> true }.play(3))
        assertFalse(vibrator.isVibrating)
    }

    /** Exact's haptic request (kind 2: `haptic()`, a press's `-exact-press-haptic`) ends on the vibrator. */
    @Test fun theHapticRequestVibrates() {
        val requests = Requests(Robolectric.buildActivity(ExactActivity::class.java).get())
        requests.perform(intArrayOf(2, 1, 1, 3))
        assertTrue(vibrator.isVibrating)
        assertEquals(VibrationAttributes.USAGE_HARDWARE_FEEDBACK, usage())
    }

    /** Android 12 has no vibration attributes for an app: a vibration without them, which touch feedback doesn't mute. */
    @Test @Config(sdk = [31]) fun beforeAndroid13ItVibratesWithoutTouchAttributes() {
        AppHaptics(context) { _, _ -> true }.play(4)
        assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK, played())
        assertNull(vibrator.audioAttributesFromLastVibration?.takeIf { it.usage == android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION })
    }
}
