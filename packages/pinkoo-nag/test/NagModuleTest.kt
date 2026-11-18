package pinkoo.nag

import android.app.Activity
import android.app.KeyguardManager
import android.os.Looper
import dev.exact.canvas.Activations
import dev.exact.canvas.Exact
import dev.exact.canvas.ExtensionContext
import dev.exact.canvas.ExtensionInvokeTest
import dev.exact.canvas.Extensions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/** The module the app's session makes: what it does with the activity in front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [ExtensionInvokeTest.Host::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class NagModuleTest : EngineCase() {
    private lateinit var activity: Activity
    private var module: NagModule? = null
    private val pillB = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill B")

    @Before fun show() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Extensions.front = { activity }
        Extensions.started()
        Extensions.resumed()
    }

    @After fun leave() {
        module?.destroy {}
        Extensions.paused()
        Extensions.stopped(false)
        Extensions.front = { Exact.reader?.get() }
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    private fun made() = NagModule(ExtensionContext(app, "nag")).also { module = it }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun send(name: String, args: JSONObject) = module!!.later(name, args) {}
    private val overLock get() = shadowOf(activity).showWhenLocked

    /**
     * The module is made at the first frame, after the activity resumed: a tap on the nag notification that brings the
     * activity back through the alias resumes it before any module hears of it (measured: the bubble stayed over the
     * nag route). Made while the activity is resumed, the module hides the bubble at once.
     */
    @Test fun aModuleMadeWhileTheActivityIsResumedHidesTheBubble() {
        ShadowSettings.setCanDrawOverlays(true)
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        climb(2)
        assertTrue(Bubble.shown)
        made()
        assertFalse(Bubble.shown)
        module!!.paused()
        assertTrue(Bubble.shown)
        module!!.resumed()
        assertFalse(Bubble.shown)
    }

    /** A module that goes (its session ended) after the next one was made leaves the activity's state alone. */
    @Test fun anOldModuleGoingLeavesTheNewOnesFront() {
        val old = NagModule(ExtensionContext(app, "nag"))
        made()
        old.destroy {}
        assertTrue(Bubble.appInFront)
    }

    /**
     * Decision 5: confirmed over the lock screen (the full-screen nag showed the route over the keyguard), the route
     * celebrates for about three seconds, then the keyguard covers the app again. Only once nothing of the nag is left:
     * one medication confirmed of two keeps it; a new nag shown meanwhile keeps it too.
     */
    @Test fun confirmedOverTheLockScreenTheKeyguardComesBackAfterTheCelebration() {
        Engine.schedule(app, NagFixtures.plan(pillA, pillB), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Activations.overLock(activity, true)
        made()
        send("nagScreen", JSONObject().put("showing", true).put("keys", JSONArray(listOf(pillA.getString("key"), pillB.getString("key")))))
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillA.getString("key")))).put("kind", "taken"))
        idle(5_000)
        assertTrue("pill B is still to confirm", overLock)
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillB.getString("key")))).put("kind", "taken"))
        idle(NagModule.CELEBRATE_MS - 100)
        assertTrue("the celebration shows", overLock)
        idle(200)
        assertFalse("the keyguard covers the app again", overLock)
    }

    @Test fun aPromiseOverTheLockScreenGoesBackToItTooButNotWhenUnlocked() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        Activations.overLock(activity, true)
        made()
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillA.getString("key")))).put("kind", "promised").put("until", clock + 600_000))
        idle(NagModule.CELEBRATE_MS + 100)
        assertFalse(overLock)
        // Unlocked, nothing is dropped behind her back: the root clears the flags when the route closes.
        Activations.overLock(activity, true)
        keyguard.setKeyguardLocked(false)
        Engine.schedule(app, NagFixtures.plan(pillA, made = clock + 1), clock + 1)
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillA.getString("key")))).put("kind", "taken"))
        idle(NagModule.CELEBRATE_MS + 100)
        assertTrue(overLock)
    }

    @Test fun aNewNagShownDuringTheCelebrationKeepsTheRouteOverTheLockScreen() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Activations.overLock(activity, true)
        made()
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillA.getString("key")))).put("kind", "taken"))
        idle(1_000)
        send("nagScreen", JSONObject().put("showing", true).put("keys", JSONArray(listOf("x@2026-11-18T08:01"))))
        idle(NagModule.CELEBRATE_MS + 100)
        assertTrue(overLock)
    }
}
