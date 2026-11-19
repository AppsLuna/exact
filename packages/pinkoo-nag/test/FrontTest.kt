package pinkoo.nag

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
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
 * intent, and stays so to its end; the ring plays as always; the module opens the nag route itself, unless the nag
 * screen already shows the dose. In the background nothing changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [ExtensionInvokeTest.Host::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class FrontTest : EngineCase() {
    private lateinit var activity: Activity
    private var module: NagModule? = null
    private val route = "/nag/2026-11-18T08%3A00?via=fsi"

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

    /** The app leaves the front: the activity pauses, and the module hears it. */
    private fun away() { Extensions.paused(); module!!.paused() }
    private fun back() { Extensions.resumed(); module!!.resumed() }

    /** What the module opened in the app since the last look: the intent's location, or null. */
    private fun opened(): String? = shadowOf(activity).nextStartedActivity?.data?.toString()

    /** Pill A's 08:00 dose due at [at], rung by the alarm and played by the service. */
    private fun fire(at: String = "2026-11-18T08:00") {
        clock = at(at)
        Engine.fire(app, clock)
        serve()
    }

    private fun schedule() = Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))

    @Test fun aNagThatRingsInFrontHasNoHeadsUpAndOpensTheNagScreen() {
        made()
        schedule()
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
        idle(NagModule.IN_FRONT_OPEN_MS - 100)
        assertNull("not yet", opened())
        idle(100)
        assertEquals("the nag route, as the full-screen intent opens it", route, opened())
    }

    @Test fun inTheBackgroundTheLoudNagIsUnchanged() {
        made()
        away()
        schedule()
        fire()
        val n = nag()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Shade.CH_NAG).importance)
        assertEquals(FullScreen.allowed(app), n.fullScreenIntent != null)
        idle(5_000)
        assertNull("the full-screen intent opens it, not the module", opened())
    }

    /** "Ainda não" in Novo remédio opens the nag route itself and the route says what it shows: no second route. */
    @Test fun theNagScreenAlreadyShowingTheDoseIsNotOpenedAgain() {
        made()
        schedule()
        clock = at("2026-11-18T07:59")
        send("nagScreen", JSONObject().put("showing", true).put("keys", keys()))
        fire()
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
        assertEquals("it rings on the nag screen", "ringing", group().state)
        idle(5_000)
        assertNull(opened())
    }

    /** The route the app opened says so a moment after the ring started (its first read): still no second route. */
    @Test fun aRouteThatSaysItShowsWithinTheWaitIsNotOpenedAgain() {
        made()
        schedule()
        fire()
        idle(300)
        clock += 300
        send("nagScreen", JSONObject().put("showing", true).put("keys", keys()))
        idle(5_000)
        assertNull(opened())
    }

    /** Off the nag route (the app says so), the next nag in front opens it again. */
    @Test fun offTheNagRouteTheNextNagInFrontOpensIt() {
        made()
        schedule()
        send("nagScreen", JSONObject().put("showing", true).put("keys", keys()))
        send("nagScreen", JSONObject().put("showing", false).put("keys", JSONArray()))
        fire()
        idle(NagModule.IN_FRONT_OPEN_MS)
        assertEquals(route, opened())
    }

    /** Answered before the wait ends (Today's own pinky, say): nothing opens. */
    @Test fun nothingOpensOnceTheDoseIsAnswered() {
        made()
        schedule()
        fire()
        send("resolveNags", JSONObject().put("keys", keys()).put("kind", "taken"))
        idle(5_000)
        assertNull(opened())
    }

    /** Gone to the background before the wait ends: nothing opens; the ring goes on, quiet. */
    @Test fun nothingOpensOnceTheAppLeft() {
        made()
        schedule()
        fire()
        away()
        idle(5_000)
        assertNull(opened())
        assertEquals("ringing", group().state)
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
    }

    /**
     * Quiet to its end: leaving the app while it rings, its ring running out and a process death post nothing loud
     * (nothing pops up twice). The next nag, rung with the app in the background, is loud again: a new notification
     * with its heads-up and full-screen intent.
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
        // The next nag rings with the app in the background.
        fire("2026-11-18T08:21")
        val loud = nag()!!
        assertTrue("a new notification", loud.id != id)
        assertEquals(Shade.CH_NAG, loud.notification.channelId)
        assertEquals(FullScreen.allowed(app), loud.notification.fullScreenIntent != null)
        // Back in front while it rings: what showed stays as it was (its heads-up came and went with the app away).
        val shown = shadowOf(nm).getNotification(loud.id)
        back()
        assertSame(shown, shadowOf(nm).getNotification(loud.id))
        idle(5_000)
        assertNull("rung in the background: the app does not open the nag route", opened())
    }

    /** A promise's deadline that comes with the app in front rings the same way: on the nag screen, no heads-up. */
    @Test fun aPromiseDeadlineInFrontRingsOnTheNagScreen() {
        made()
        schedule()
        fire()
        send("resolveNags", JSONObject().put("keys", keys()).put("kind", "promised").put("until", at("2026-11-18T08:10")))
        idle(5_000)
        assertNull(opened())
        fire("2026-11-18T08:10")
        assertEquals("ringing", group().state)
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
        assertNull(nag()!!.notification.fullScreenIntent)
        idle(NagModule.IN_FRONT_OPEN_MS)
        assertEquals(route, opened())
    }

    /** The intent the module starts is the app's own alias, at the nag route, as a notification's tap is. */
    @Test fun theNagRouteOpensThroughTheAppsAlias() {
        made()
        schedule()
        fire()
        idle(NagModule.IN_FRONT_OPEN_MS)
        val intent: Intent = shadowOf(activity).nextStartedActivity!!
        assertEquals("dev.exact.app.Notify", intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(route), intent.data)
    }
}
