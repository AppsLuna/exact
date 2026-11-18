package dev.exact.canvas

import android.app.Activity
import android.content.ComponentName
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An over-lock activation shows the activity over the keyguard only through the unexported alias. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OverLockTest {
    @Test fun anOverLockActivationThroughTheAliasShowsOverTheKeyguardOnce() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val intent = Activations.intent(activity, "nag", "", JSONArray(), "/nag/1", overLock = true)
        Activations.overLock(activity, intent)
        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)
        Activations.overLock(activity, false)
        Activations.overLock(activity, intent)
        assertFalse("the extra is taken off as it is read", shadowOf(activity).showWhenLocked)
    }

    @Test fun anOverLockExtraSentToTheExportedActivityIsIgnored() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val intent = Activations.intent(activity, "nag", "", JSONArray(), "/nag/1", overLock = true)
            .setComponent(ComponentName(activity.packageName, "dev.exact.app.MainActivity"))
        Activations.overLock(activity, intent)
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).turnScreenOn)
    }
}
