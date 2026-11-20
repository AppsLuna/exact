package pinkoo.nag

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import dev.exact.canvas.Exact
import dev.exact.canvas.ExtensionContext
import dev.exact.canvas.ExtensionInvokeTest
import dev.exact.canvas.Extensions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Decision 17: with Pinkoo in front, a loud nag rings on the nag screen without Android's heads-up over it. The nag that
 * rings in front is posted on the waiting channel (low importance: Android shows no heads-up for it) with no full-screen
 * intent, and stays so to its end; the ring plays as always. In the background nothing changes.
 *
 * Decision 7: the engine opens nothing in front. It tells the app (`nagState().front`): the app opens the nag screen at
 * once, or shows a banner while she is in a form and opens it when she leaves. The front lasts until the nag screen shows
 * one of its doses, the dose is answered, promised or missed, or a loud nag rings in the background; a nag the nag screen
 * already shows has none.
 *
 * Item 20: with notifications off nothing rings, but a nag due with Pinkoo in front still tells the app, which opens the
 * nag screen without a ring (`?via=silent`). It rang nothing, so nothing can ignore it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [ExtensionInvokeTest.Host::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class FrontTest : EngineCase() {
    private lateinit var activity: Activity
    private var module: NagModule? = null
    private val route = "/nag/2026-11-18T08%3A00?via=fsi"
    private val silentRoute = "/nag/2026-11-18T08%3A00?via=silent"

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
    }

    /** The module the session makes at its first frame, with the activity resumed. */
    private fun made() = NagModule(ExtensionContext(app, "nag")).also { module = it }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun send(name: String, args: JSONObject) = module!!.later(name, args) {}
    private fun keys() = JSONArray(listOf(pillA.getString("key")))
    private fun showing(on: Boolean) = send("nagScreen", JSONObject().put("showing", on).put("keys", if (on) keys() else JSONArray()))

    /** What the app reads: `nagState().front`, or null. */
    private fun front(): JSONObject? {
        var reply: JSONObject? = null
        module!!.later("nagState", JSONObject()) { reply = it }
        val r = reply!!
        assertTrue("nagState always says front, null when none", r.has("front"))
        return r.optJSONObject("front")
    }

    private fun list(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    /** Notifications for Pinkoo switched off (or on) in Android's Settings: no ring can be stopped, so none starts. */
    private fun notifications(on: Boolean) = shadowOf(nm).setNotificationsEnabled(on)

    /** The app leaves the front: the activity pauses, and the module hears it. */
    private fun away() { Extensions.paused(); module!!.paused() }
    private fun back() { Extensions.resumed(); module!!.resumed() }

    /** Whatever the module started in the app since the last look: the engine never opens the nag route in front. */
    private fun assertOpenedNothing() = assertNull("the app decides; the engine opens nothing", shadowOf(activity).nextStartedActivity)

    /** Pill A's 08:00 dose due at [at], rung by the alarm and played by the service. */
    private fun fire(at: String = "2026-11-18T08:00") {
        clock = at(at)
        Engine.fire(app, clock)
        serve()
    }

    /** Pill A's plan, or one of [doses]. */
    private fun schedule(vararg doses: JSONObject) =
        Engine.schedule(app, NagFixtures.plan(*(if (doses.isEmpty()) arrayOf(pillA) else doses)), at("2026-11-18T07:00"))

    @Test fun aNagThatRingsInFrontHasNoHeadsUpAndTellsTheApp() {
        made()
        schedule()
        assertNull("nothing rang yet", front())
        fire()
        val n = nag()!!.notification
        assertEquals("the waiting channel", Shade.CH_WAITING, n.channelId)
        assertTrue("whose importance shows no heads-up",
            nm.getNotificationChannel(Shade.CH_WAITING).importance < NotificationManager.IMPORTANCE_HIGH)
        assertNull("no full-screen intent", n.fullScreenIntent)
        assertEquals("it is still the ringing nag", "Time for Pill A", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertTrue("with its Not now", n.actions.any { it.title == "notNow" })
        assertNotNull("the service plays the ring", NagService.running)
        assertEquals("ringing", group().state)
        val f = front()!!
        assertEquals("the nag's number, new for every loud nag", group().nag, f.getInt("nag"))
        assertEquals("2026-11-18T08:00", f.getString("group"))
        assertEquals("the route the full-screen intent opens, ringing until her first touch", route, f.getString("route"))
        assertEquals(listOf("a@2026-11-18T08:00"), (0 until f.getJSONArray("keys").length()).map { f.getJSONArray("keys").getString(it) })
        assertEquals("the banner's names", "Pill A", f.getJSONArray("names").getString(0))
        assertEquals(at("2026-11-18T08:00"), f.getLong("at"))
        idle(5_000)
        assertOpenedNothing()
        assertEquals("still there for the app until the nag screen shows it", f.toString(), front().toString())
    }

    @Test fun inTheBackgroundTheLoudNagIsUnchangedAndHasNoFront() {
        made()
        away()
        schedule()
        fire()
        val n = nag()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Shade.CH_NAG).importance)
        assertEquals(FullScreen.allowed(app), n.fullScreenIntent != null)
        assertNull(front())
        back()
        assertNull("back in front while it rings: its heads-up and full-screen nag were the pop-up", front())
        idle(5_000)
        assertOpenedNothing()
    }

    /** "Ainda não" in Novo remédio opens the nag route itself and the route says what it shows: no front. */
    @Test fun aNagTheNagScreenAlreadyShowsHasNoFront() {
        made()
        schedule()
        clock = at("2026-11-18T07:59")
        showing(true)
        fire()
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
        assertEquals("it rings on the nag screen", "ringing", group().state)
        assertNull(front())
    }

    /** The app opened the route; her first touch says it shows the dose (it silences the ring): the front ends. */
    @Test fun theNagScreenShowingTheDoseEndsTheFront() {
        made()
        schedule()
        fire()
        assertNotNull(front())
        clock += 3_000
        showing(true)
        assertNull(front())
        assertEquals("the ring stopped, nothing climbed", "pending", group().state)
        assertEquals(0, group().ignores)
        showing(false)
        assertNull("leaving the route brings nothing back", front())
    }

    /** Off the nag route (the app says so), the next nag in front tells the app again. */
    @Test fun offTheNagRouteTheNextNagInFrontTellsTheApp() {
        made()
        schedule()
        showing(true)
        showing(false)
        fire()
        assertEquals(route, front()!!.getString("route"))
    }

    /** Answered from elsewhere (Today's own pinky, say): nothing is left to show. */
    @Test fun anAnsweredDoseEndsTheFront() {
        made()
        schedule()
        fire()
        assertNotNull(front())
        send("resolveNags", JSONObject().put("keys", keys()).put("kind", "taken"))
        assertNull(front())
        idle(5_000)
        assertOpenedNothing()
    }

    /** Unseen until it is missed (she stayed in the form all day): nothing is left to show. */
    @Test fun aMissedDoseEndsTheFront() {
        made()
        schedule()
        fire()
        assertNotNull(front())
        // Its ring runs out and nobody looks until 20:00, when the dose is missed.
        clock = at("2026-11-18T20:00")
        Engine.fire(app, clock)
        assertEquals("lapsed", Store.state(app).doses.getValue("a@2026-11-18T08:00").state)
        assertNull(front())
    }

    /** A promise holds it (no front); its deadline, rung in front, tells the app again, as a new nag. */
    @Test fun aPromiseDeadlineInFrontTellsTheAppAgain() {
        made()
        schedule()
        fire()
        val first = front()!!.getInt("nag")
        send("resolveNags", JSONObject().put("keys", keys()).put("kind", "promised").put("until", at("2026-11-18T08:10")))
        assertNull(front())
        fire("2026-11-18T08:10")
        assertEquals("ringing", group().state)
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
        assertNull(nag()!!.notification.fullScreenIntent)
        val f = front()!!
        assertTrue("a new nag: the app acts on it again", f.getInt("nag") > first)
        assertEquals(route, f.getString("route"))
        idle(5_000)
        assertOpenedNothing()
    }

    /**
     * She is in a form (the app shows its banner, so nothing shows the nag screen): the front waits for her across
     * leaving the app, the ring running out (its ignore) and a process death, and ends only once the nag screen shows it.
     */
    @Test fun theFrontWaitsUntilTheNagScreenShowsIt() {
        made()
        schedule()
        fire()
        val f = front()!!
        away()
        assertEquals("left mid-form", f.toString(), front().toString())
        back()
        clock = at("2026-11-18T08:01")
        Engine.fire(app, clock)
        assertEquals("the ring ran out: its ignore", 1, group().ignores)
        assertEquals("pending", group().state)
        assertEquals("still unseen", f.getInt("nag"), front()!!.getInt("nag"))
        // A process death: the module, its screen and what was posted are gone; the state file keeps the front.
        module!!.destroy {}
        Shade.forget()
        NagService.running = null
        made()
        assertEquals(f.getInt("nag"), front()!!.getInt("nag"))
        showing(true)
        assertNull(front())
    }

    /** Two groups one alarm rings together (a late alarm): the front names the earliest, which the nag route shows. */
    @Test fun twoGroupsInOneNagLeadWithTheEarliest() {
        val pillB = NagFixtures.dose("b@2026-11-18T08:20", "2026-11-18T08:20", "2026-11-18T20:20", "Pill B")
        made()
        schedule(pillB, pillA)
        fire("2026-11-18T08:20")
        val f = front()!!
        assertEquals("2026-11-18T08:00", f.getString("group"))
        assertEquals("a@2026-11-18T08:00", f.getJSONArray("keys").getString(0))
        assertEquals(1, f.getJSONArray("names").length())
        // The nag screen shows the 08:00 dose: the nag that rang for both was shown.
        showing(true)
        assertNull(front())
    }

    /**
     * Quiet to its end: leaving the app while it rings, its ring running out and a process death post nothing loud
     * (nothing pops up twice). The next nag, rung with the app in the background, is loud again (a new notification with
     * its heads-up and full-screen intent) and ends the front: its full-screen nag is the pop-up.
     */
    @Test fun aNagRungInFrontStaysQuietToItsEndAndTheNextOneInTheBackgroundIsLoud() {
        made()
        schedule()
        fire()
        val id = nag()!!.id
        val quiet = shadowOf(nm).getNotification(id)
        away()
        assertSame("leaving posts nothing", quiet, shadowOf(nm).getNotification(id))
        // A process death mid-ring: the service and what it posted are gone; the state file says this nag is quiet.
        Shade.forget()
        NagService.running = null
        Engine.restore(app, clock + 10_000)
        serve()
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
        assertNull(nag()!!.notification.fullScreenIntent)
        // Its ring runs out: an ignore, still on the waiting channel.
        clock = at("2026-11-18T08:01")
        Engine.fire(app, clock)
        assertEquals(1, group().ignores)
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
        assertNotNull("unseen: the app opens it when she is back", front())
        // The next nag rings with the app in the background.
        fire("2026-11-18T08:21")
        val loud = nag()!!
        assertTrue("a new notification", loud.id != id)
        assertEquals(Shade.CH_NAG, loud.notification.channelId)
        assertEquals(FullScreen.allowed(app), loud.notification.fullScreenIntent != null)
        assertNull(front())
        // Back in front while it rings: what showed stays as it was (its heads-up came and went with the app away).
        val shown = shadowOf(nm).getNotification(loud.id)
        back()
        assertSame(shown, shadowOf(nm).getNotification(loud.id))
        assertNull("rung in the background: the app does not open the nag route", front())
        idle(5_000)
        assertOpenedNothing()
    }

    // MARK: item 20, notifications off

    /**
     * Notifications off and Pinkoo in front: the nag rings nothing (no service, no next nag), but the app is told, with a
     * route that opens the nag screen as her own opening does. Nothing ignores it, it survives a process death, and the
     * nag screen showing it ends it.
     */
    @Test fun withNotificationsOffANagDueInFrontTellsTheAppWithoutARing() {
        notifications(false)
        made()
        schedule()
        fire()
        assertNull("no ring: no service", NagService.running)
        assertEquals("pending", group().state)
        assertNull("no next nag while nothing can ring", group().nextAt)
        val f = front()!!
        assertEquals("a nag of its own number", group().nag, f.getInt("nag"))
        assertEquals("2026-11-18T08:00", f.getString("group"))
        assertEquals("the nag screen, telling the engine at its first show", silentRoute, f.getString("route"))
        assertEquals(listOf("a@2026-11-18T08:00"), list(f.getJSONArray("keys")))
        assertEquals(listOf("Pill A"), list(f.getJSONArray("names")))
        assertEquals("when it came due", at("2026-11-18T08:00"), f.getLong("at"))
        assertTrue("nothing fired: the app's ladder sees no ring", Store.events(app).none { it.kind == "fired" })
        idle(5_000)
        assertOpenedNothing()
        // It rang nothing: "Agora não", a swipe or the bubble's close count nothing.
        clock += 10_000
        send("ignoreNags", JSONObject().put("keys", keys()))
        Engine.ignore(app, f.getInt("nag"), "swipe", clock)
        Engine.bubbleClosed(app, f.getInt("nag"), clock)
        assertEquals(0, group().ignores)
        assertNull(group().nextAt)
        assertTrue(Store.events(app).none { it.kind == "ignored" })
        assertEquals("still unseen", f.toString(), front().toString())
        // A process death: the state file keeps it.
        module!!.destroy {}
        made()
        assertEquals(f.toString(), front().toString())
        // The nag screen shows it (its first show, `via=silent`): the front ends, nothing climbed.
        showing(true)
        assertNull(front())
        assertEquals(0, group().ignores)
        assertEquals("pending", group().state)
        assertNull(NagService.running)
    }

    /** In the background a nag that can't ring is only muted, as before: nothing is told, nor once she is back. */
    @Test fun withNotificationsOffANagDueInTheBackgroundTellsNothing() {
        notifications(false)
        made()
        away()
        schedule()
        fire()
        assertEquals("pending", group().state)
        assertNull(front())
        back()
        assertNull("nothing came due while in front", front())
        idle(5_000)
        assertOpenedNothing()
    }

    /** "Ainda não" in Novo remédio: the nag screen already shows the dose, so nothing is told. */
    @Test fun withNotificationsOffANagTheNagScreenAlreadyShowsHasNoFront() {
        notifications(false)
        made()
        schedule()
        clock = at("2026-11-18T07:59")
        showing(true)
        fire()
        assertEquals("pending", group().state)
        assertNull(front())
    }

    /** A promise's deadline, come with notifications off and the app in front, opens the nag screen too. */
    @Test fun withNotificationsOffAPromiseDeadlineInFrontTellsTheApp() {
        made()
        schedule()
        fire()
        val rang = front()!!.getInt("nag")
        send("resolveNags", JSONObject().put("keys", keys()).put("kind", "promised").put("until", at("2026-11-18T08:10")))
        assertNull(front())
        notifications(false)
        fire("2026-11-18T08:10")
        assertEquals("the promise is over, and nothing rings", "pending", group().state)
        val f = front()!!
        assertTrue("a new nag: the app acts on it again", f.getInt("nag") > rang)
        assertEquals(silentRoute, f.getString("route"))
        assertEquals(at("2026-11-18T08:10"), f.getLong("at"))
    }

    /**
     * Notifications back on (she comes back from Settings): the muted dose nags at once. In front it rings on the nag
     * screen as any nag in front does, a new nag that replaces the silent one, on a notification of the other id.
     */
    @Test fun withNotificationsBackTheNextNagInFrontRingsAndReplacesTheSilentOne() {
        notifications(false)
        made()
        schedule()
        fire()
        val silent = front()!!.getInt("nag")
        notifications(true)
        away()
        back()
        assertEquals("set at once", clock, alarms.nextAlarmClock.triggerTime)
        fire("2026-11-18T08:05")
        assertEquals("ringing", group().state)
        assertNotNull(NagService.running)
        val f = front()!!
        assertTrue(f.getInt("nag") > silent)
        assertEquals(route, f.getString("route"))
        assertEquals(at("2026-11-18T08:05"), f.getLong("at"))
        val n = nag()!!
        assertEquals(Shade.nagId(f.getInt("nag")), n.id)
        assertTrue("a new notification, not the silent nag's", n.id != Shade.nagId(silent))
        assertEquals("rung in front: no heads-up", Shade.CH_WAITING, n.notification.channelId)
        assertNull(n.notification.fullScreenIntent)
    }
}
