package pinkoo.nag

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Looper
import dev.exact.canvas.Activations
import dev.exact.canvas.Exact
import dev.exact.canvas.ExtensionContext
import dev.exact.canvas.ExtensionInvokeTest
import dev.exact.canvas.Extensions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    /** A send's reply, which the module gives at once. */
    private fun ask(name: String, args: JSONObject): JSONObject { var r: JSONObject? = null; module!!.later(name, args) { r = it }; return r!! }
    private fun keysOf(vararg d: JSONObject) = JSONObject().put("keys", JSONArray(d.map { it.getString("key") }))
    private val overLock get() = shadowOf(activity).showWhenLocked
    private fun ignored() = Store.events(app).filter { it.kind == "ignored" }

    /** Pill A's 08:00 nag rung by the alarm and played by the service, with the app in front. */
    private fun ringing(first: Int = 20, repeat: Int = 15) {
        made()
        Engine.schedule(app, NagFixtures.plan(pillA, first = first, repeat = repeat), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        assertNotNull(NagService.running)
    }

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

    // MARK: the nag screen's "Not now" (`ignoreNags`)

    /**
     * The full-screen nag opened the route (its ring stopped, nothing climbed), then "Agora não": the nag's one ignore.
     * It climbs one rung, the next loud nag counts from the tap, and the notification stays, silent. The app reads the
     * same rung, and records the event as its own `not_now`.
     */
    @Test fun theNagScreensNotNowIsTheNagsIgnoreAndTheNextNagCountsFromIt() {
        ringing()
        clock += 10_000
        send("nagScreen", JSONObject().put("showing", true).put("keys", keysOf(pillA).getJSONArray("keys")))
        assertEquals(0, group().ignores)
        clock += 20_000
        val tapped = clock
        assertFalse(ask("ignoreNags", keysOf(pillA)).has("error"))
        assertEquals(1, group().ignores)
        assertEquals("pending", group().state)
        assertEquals("ignored", Store.events(app).last().kind)
        assertEquals("notNow", Store.events(app).last().reason)
        assertEquals(tapped, Store.events(app).last().at)
        assertEquals("Standard: 20 minutes after the tap", tapped + 20 * 60_000L, group().nextAt)
        assertEquals(tapped + 20 * 60_000L, alarms.nextAlarmClock.triggerTime)
        assertNull("nothing rings", NagService.running)
        assertEquals("the notification stays, silent", Shade.CH_WAITING, nag()?.notification?.channelId)
        val dose = ask("nagState", JSONObject()).getJSONArray("doses").getJSONObject(0)
        assertEquals(listOf("pending", 1, 1, tapped + 20 * 60_000L), listOf(dose.getString("state"), dose.getInt("ignores"), dose.getInt("rung"), dose.getLong("nextAt")))
    }

    /** Decision A: one ignore per nag. A second "Agora não" before the next nag counts nothing; the next nag's own does. */
    @Test fun aSecondNotNowForTheSameNagCountsNothing() {
        ringing()
        clock += 30_000
        val tapped = clock
        send("ignoreNags", keysOf(pillA))
        clock += 60_000
        assertFalse(ask("ignoreNags", keysOf(pillA)).has("error"))
        assertEquals(1, group().ignores)
        assertEquals(tapped + 20 * 60_000L, group().nextAt)
        assertEquals(tapped + 20 * 60_000L, alarms.nextAlarmClock.triggerTime)
        assertEquals(1, ignored().size)
        clock = group().nextAt!!
        Engine.fire(app, clock)
        serve()
        clock += 5_000
        send("ignoreNags", keysOf(pillA))
        assertEquals(2, group().ignores)
        assertEquals("then every repeat from each ignore", clock + 15 * 60_000L, group().nextAt)
    }

    /** Standard (20 then 15), Gentle (30 then 20) and Insistent (10 then 5): each next nag at the tap plus the wait. */
    @Test fun eachLadderCountsTheNextNagFromTheTap() {
        for ((name, first, repeat) in listOf(Triple("Standard", 20, 15), Triple("Gentle", 30, 20), Triple("Insistent", 10, 5))) {
            NagFixtures.clear(app)
            Shade.forget()
            module?.destroy {}
            ringing(first = first, repeat = repeat)
            for (n in 1..3) {
                if (n > 1) { Engine.fire(app, clock); serve() }
                clock += 7_000
                send("ignoreNags", keysOf(pillA))
                val wait = (if (n == 1) first else repeat) * 60_000L
                assertEquals("$name after $n", n, group().ignores)
                assertEquals("$name after $n", clock + wait, group().nextAt)
                assertEquals("$name after $n", clock + wait, alarms.nextAlarmClock.triggerTime)
                clock += wait
            }
        }
    }

    /** A nag swiped away already took its ignore: "Agora não" on it counts nothing, and a swipe after the tap neither. */
    @Test fun aSwipeAndTheButtonShareTheNagsOneIgnore() {
        ringing()
        clock += 5_000
        val swiped = clock
        NagReceiver().onReceive(app, Intent(NagReceiver.DISMISS).putExtra("nag", group().nag))
        assertEquals(1, group().ignores)
        clock += 30_000
        send("nagScreen", JSONObject().put("showing", true).put("keys", keysOf(pillA).getJSONArray("keys")))
        send("ignoreNags", keysOf(pillA))
        assertEquals(1, group().ignores)
        assertEquals("still counted from the swipe", swiped + 20 * 60_000L, group().nextAt)
        // The next nag: the button first, then a swipe of its silent notification.
        clock = group().nextAt!!
        Engine.fire(app, clock)
        serve()
        clock += 10_000
        val tapped = clock
        send("ignoreNags", keysOf(pillA))
        clock += 10_000
        NagReceiver().onReceive(app, Intent(NagReceiver.DISMISS).putExtra("nag", group().nag))
        assertEquals(2, group().ignores)
        assertEquals(tapped + 15 * 60_000L, group().nextAt)
        assertEquals(listOf("swipe", "notNow"), ignored().map { it.reason })
    }

    /** Pressed while it still rings (the route was not told it showed), the ignore ends the ring itself. */
    @Test fun aNotNowWhileItStillRingsEndsTheRing() {
        ringing()
        clock += 15_000
        send("ignoreNags", keysOf(pillA))
        assertNull(NagService.running)
        assertEquals(1, group().ignores)
        assertEquals(Shade.CH_WAITING, nag()?.notification?.channelId)
    }

    /** A ring that ran out unheard (nothing timed it out yet) was the nag's ignore at its end, as the app's catch-up
     * counts it: the button after it counts nothing, and the next nag counts from the ring's end. */
    @Test fun aNotNowAfterTheRingRanOutCountsItsTimeoutAsTheAppDoes() {
        made()
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        clock = at("2026-11-18T08:01") + 30_000
        send("ignoreNags", keysOf(pillA))
        assertEquals(1, group().ignores)
        assertEquals(listOf("timeout" to at("2026-11-18T08:01")), ignored().map { it.reason to it.at })
        assertEquals(at("2026-11-18T08:21"), group().nextAt)
    }

    /** Nothing to ignore is no error: keys the engine does not know, none, a promised dose, or no plan yet. */
    @Test fun aNotNowWithNothingToIgnoreIsRefusedSoftly() {
        ringing()
        clock += 10_000
        assertFalse(ask("ignoreNags", JSONObject().put("keys", JSONArray(listOf("nope@2026-11-18T08:00")))).has("error"))
        assertFalse(ask("ignoreNags", JSONObject()).has("error"))
        assertEquals(0, group().ignores)
        assertEquals("ringing", group().state)
        assertNotNull("it rings on", NagService.running)
        send("resolveNags", keysOf(pillA).put("kind", "promised").put("until", clock + 600_000))
        send("ignoreNags", keysOf(pillA))
        assertEquals("promised", group().state)
        assertEquals(0, group().ignores)
        assertTrue(ignored().isEmpty())
        NagFixtures.clear(app)
        assertFalse(ask("ignoreNags", keysOf(pillA)).has("error"))
    }
}
