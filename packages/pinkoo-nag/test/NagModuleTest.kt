package pinkoo.nag

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Looper
import android.provider.Settings
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
        assertTrue(Engine.appInFront)
    }

    /**
     * Decision 5: confirmed over the lock screen (the full-screen nag showed the route over the keyguard), the route
     * celebrates as long as its celebration lasts, says when it ends, and the keyguard covers the app again then. Only
     * once nothing of the nag is left: one medication confirmed of two keeps it; a new nag shown meanwhile keeps it too.
     */
    @Test fun confirmedOverTheLockScreenTheKeyguardComesBackWhenTheCelebrationEnds() {
        Engine.schedule(app, NagFixtures.plan(pillA, pillB), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Activations.overLock(activity, true)
        made()
        send("nagScreen", JSONObject().put("showing", true).put("keys", JSONArray(listOf(pillA.getString("key"), pillB.getString("key")))))
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillA.getString("key")))).put("kind", "taken"))
        idle(6_000)
        assertTrue("pill B is still to confirm", overLock)
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillB.getString("key")))).put("kind", "taken"))
        // The beads on the nag take about 4.3 s from the seal: longer than the old 3 s, so nothing cuts them now.
        idle(4_300)
        assertTrue("the celebration still shows", overLock)
        val left = ask("leaveLockScreen", JSONObject().put("after", 0))
        assertTrue("the route keeps its last frame: the keyguard is coming", left.getBoolean("locking"))
        idle(1)
        assertFalse("the keyguard covers the app again", overLock)
    }

    /** A route that never says its celebration ended is covered at the cap, 5 s after the confirmation. */
    @Test fun aCelebrationThatNeverEndsIsCoveredAtTheCap() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Activations.overLock(activity, true)
        made()
        send("resolveNags", keysOf(pillA).put("kind", "taken"))
        idle(NagModule.CELEBRATE_MAX_MS - 100)
        assertTrue(overLock)
        idle(200)
        assertFalse(overLock)
        // Said late, after the cap: the keyguard is still on its way. Android pauses the activity at once but shows it
        // until it sleeps, so the route keeps its frame (seen on the device: a two-dose nag's last celebration outlasted
        // the cap, the answer was false, and Today showed over the lock screen for about 2 s).
        Extensions.paused()
        assertTrue(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
        // In front again (she unlocked), it leaves as usual.
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        Extensions.resumed()
        assertFalse(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
    }

    /**
     * "Agora não" over the lock screen: the route says it is done and keeps its frame while the keyguard comes back
     * (nothing of Today under it shows there). The wait asked is capped as a celebration is.
     */
    @Test fun leavingTheLockScreenHandsItBackToTheKeyguard() {
        ringing()
        clock += 10_000
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Activations.overLock(activity, true)
        send("ignoreNags", keysOf(pillA))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertTrue("an ignore leaves the nag open: nothing hands back by itself", overLock)
        assertTrue(ask("leaveLockScreen", JSONObject().put("after", 60_000)).getBoolean("locking"))
        idle(NagModule.CELEBRATE_MAX_MS - 100)
        assertTrue(overLock)
        idle(200)
        assertFalse(overLock)
    }

    /** Unlocked (she unlocked over the nag, or it never showed over the keyguard), nothing is handed back: the route
     * leaves as usual. */
    @Test fun leavingWhenUnlockedChangesNothing() {
        made()
        Activations.overLock(activity, true)
        assertFalse(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertTrue(overLock)
        // Locked, but the app not in front: nothing to hand back either.
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Extensions.paused()
        assertFalse(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
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
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertFalse(overLock)
        // Unlocked, nothing is dropped behind her back: the root clears the flags when the route closes.
        Activations.overLock(activity, true)
        keyguard.setKeyguardLocked(false)
        Engine.schedule(app, NagFixtures.plan(pillA, made = clock + 1), clock + 1)
        send("resolveNags", JSONObject().put("keys", JSONArray(listOf(pillA.getString("key")))).put("kind", "taken"))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertTrue(overLock)
    }

    /**
     * Two doses over the lock screen: a promise on the first leaves the second to answer, so the keyguard stays away
     * while the route moves on to it (the review's lock-screen run: it came back ~10 s after the promise, with the
     * other dose unanswered). Answering the second hands the screen back.
     */
    @Test fun aPromiseOnOneOfTwoDosesOverTheLockScreenKeepsTheRouteForTheOther() {
        Engine.schedule(app, NagFixtures.plan(pillA, pillB), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Activations.overLock(activity, true)
        made()
        send("nagScreen", JSONObject().put("showing", true).put("keys", JSONArray(listOf(pillA.getString("key"), pillB.getString("key")))))
        send("resolveNags", keysOf(pillA).put("kind", "promised").put("until", clock + 600_000))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertTrue("the other dose is still open: the route stays over the lock screen", overLock)
        assertEquals("pending", group().state)
        send("resolveNags", keysOf(pillB).put("kind", "taken"))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertFalse("nothing of that nag left: the keyguard comes back", overLock)
        assertEquals("promised", group().state)
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
        idle(NagModule.CELEBRATE_MAX_MS + 100)
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

    // MARK: the ring test ("Testar o toque") and the sound page

    private fun preview(after: Long) = send("previewNag", JSONObject().put("rung", 0).put("after", after).put("title", "Ring test")
        .put("body", "This is how I call you").put("route", "/settings/health"))
    private fun previewShown() = nm.activeNotifications.firstOrNull { it.id == Shade.PREVIEW }

    /**
     * Decision: "Testar o toque" fires the real full-screen nag 5 s later, so she may lock the phone meanwhile. With the
     * app not in front it is a loud notification on the nag channel whose full-screen intent opens the test's screen
     * over the keyguard; it is never a dose: no event, no state, no alarm of the chain. Answered, it goes.
     */
    @Test fun theRingTestIsTheRealFullScreenNagFiveSecondsLaterAndNoDose() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        val chain = alarms.nextAlarmClock?.triggerTime
        val state = Store.state(app).groups.mapValues { it.value.state to it.value.ignores }
        made()
        Extensions.paused()
        Extensions.stopped(false)
        preview(5_000)
        idle(4_900)
        assertNull("not yet", previewShown())
        idle(200)
        val n = previewShown()
        assertNotNull("the full-screen nag", n)
        assertEquals(Shade.CH_NAG, n!!.notification.channelId)
        assertEquals("its full-screen nag, where allowed", FullScreen.allowed(app), n.notification.fullScreenIntent != null)
        assertNotNull("a tap opens it too", n.notification.contentIntent)
        assertEquals(android.app.Notification.CATEGORY_ALARM, n.notification.category)
        assertEquals("Ring test", n.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertTrue("no event", Store.events(app).isEmpty())
        assertEquals("no dose moved", state, Store.state(app).groups.mapValues { it.value.state to it.value.ignores })
        assertEquals("the chain's alarm untouched", chain, alarms.nextAlarmClock?.triggerTime)
        assertTrue("no nag notification", nags().isEmpty())
        send("stopNagPreview", JSONObject())
        assertNull("answered: it goes", previewShown())
    }

    /** With the app in front, the screen she is on shows the ring itself: only the ring, no notification over it. */
    @Test fun withTheAppInFrontTheRingTestOnlyRings() {
        made()
        preview(5_000)
        idle(5_100)
        assertNull(previewShown())
    }

    /** Unanswered, the test rings out; over the lock screen the keyguard comes back then. Cancelled before it rings,
     * nothing comes. */
    @Test fun anUnansweredRingTestEndsByItselfAndACancelledOneNeverComes() {
        made()
        Extensions.paused()
        preview(5_000)
        idle(5_100)
        assertNotNull(previewShown())
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Extensions.resumed()
        Activations.overLock(activity, true)
        idle(NagModule.PREVIEW_RING_MS)
        assertNull(previewShown())
        assertFalse("the keyguard covers it again", overLock)
        Extensions.paused()
        preview(5_000)
        idle(1_000)
        send("stopNagPreview", JSONObject())
        idle(10_000)
        assertNull(previewShown())
    }

    /** The ring test's "Didn't hear it" opens Android's sound settings. */
    @Test fun soundOpensAndroidsSoundSettings() {
        made()
        send("requestNagAccess", JSONObject().put("what", "sound"))
        idle(1)
        assertEquals(Settings.ACTION_SOUND_SETTINGS, shadowOf(activity).nextStartedActivityForResult?.intent?.action)
    }

    /** Tocar na hora's Do Not Disturb fix opens Do Not Disturb's own page (Modes from Android 15). */
    @Test fun dndOpensDoNotDisturbsPage() {
        made()
        send("requestNagAccess", JSONObject().put("what", "dnd"))
        idle(1)
        assertEquals(NagModule.ZEN_MODE_SETTINGS, shadowOf(activity).nextStartedActivityForResult?.intent?.action)
    }

    /** Where no app opens Do Not Disturb's page, the fix opens Android's sound page instead. */
    @Test fun dndFallsBackToTheSoundPage() {
        val settings = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.android.settings"; name = "Sound" }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SOUND_SETTINGS), settings)
        shadowOf(app).checkActivities(true)
        try {
            made()
            send("requestNagAccess", JSONObject().put("what", "dnd"))
            idle(1)
            assertEquals(Settings.ACTION_SOUND_SETTINGS, shadowOf(activity).nextStartedActivityForResult?.intent?.action)
        } finally {
            shadowOf(app).checkActivities(false)
        }
    }
}
