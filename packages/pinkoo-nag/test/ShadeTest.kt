package pinkoo.nag

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.provider.Settings
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager

/** The nag notification: a new one for every loud nag, posted only when it changed, sounding itself when no service
 * can ring, and showing the rung and line the nag screen shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ShadeTest : EngineCase() {
    private fun only(n: Notification) = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    /**
     * Android launches a full-screen intent only for a notification newly posted, never for an update of one that
     * shows (measured on Android 16: a dose's third nag showed no full-screen nag). Each loud nag is a new
     * notification: its id alternates between two, the one before going, so there is still only one.
     */
    @Test fun everyLoudNagIsANewNotificationSoItsFullScreenNagLaunches() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        val ids = ArrayList<Int>()
        var t = at("2026-11-18T08:00")
        repeat(4) {
            clock = t
            Engine.fire(app, t)
            val s = serve()!!
            val loud = nag()!!
            assertEquals(shadowOf(s).lastForegroundNotificationId, loud.id)
            assertEquals(Shade.CH_NAG, loud.notification.channelId)
            assertEquals(Notification.CATEGORY_ALARM, loud.notification.category)
            assertEquals(FullScreen.allowed(app), loud.notification.fullScreenIntent != null)
            ids += loud.id
            // Rung out: the same notification goes silent, as an update.
            clock = t + 60_000
            Engine.fire(app, t + 60_000)
            assertEquals(loud.id, nag()!!.id)
            assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
            t = group().nextAt!!
        }
        assertEquals(listOf(Shade.NAG, Shade.NAG_ALT, Shade.NAG, Shade.NAG_ALT), ids)
    }

    /** Without the service too (the nag posted by the engine itself), a new loud nag is a new notification. */
    @Test fun aLoudNagTheEnginePostsIsNewToo() {
        Engine.schedule(refused, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(refused, at("2026-11-18T08:00"))
        val first = nag()!!.id
        Engine.fire(refused, at("2026-11-18T08:01"))
        Engine.fire(refused, at("2026-11-18T08:21"))
        assertTrue(nag()!!.id != first)
        assertEquals(Notification.CATEGORY_ALARM, nag()!!.notification.category)
    }

    /**
     * A pause, a resume, a plan sent again unchanged, a time change: nothing the nag shows changed, so nothing is
     * posted (an update of the loud nag popped its heads-up again). What changes is an update that alerts nothing.
     */
    @Test fun aRefreshThatChangesNothingPostsNothingAndAnUpdateAlertsNothing() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        Engine.fire(app, clock)
        serve()
        val id = nag()!!.id
        val first = shadowOf(nm).getNotification(id)
        assertTrue("a later post of the same never alerts again", only(first))
        Engine.restore(app, clock + 5_000, front = true)
        Engine.restore(app, clock + 6_000, front = false)
        Engine.schedule(app, NagFixtures.plan(pillA, made = 5), clock + 7_000)
        NagSystemReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        assertSame(first, shadowOf(nm).getNotification(id))
        // A second medication joins the 08:00 dose: the notification names both, updated in place, silently.
        val pillB = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill B")
        Engine.schedule(app, NagFixtures.plan(pillA, pillB, made = 9), clock + 8_000)
        val second = shadowOf(nm).getNotification(id)
        assertNotSame(first, second)
        assertEquals("Time for Pill A, Pill B", second.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(only(second))
        // Waiting, it is posted once too.
        Engine.ignore(app, group().nag, "notNow", clock + 9_000)
        val waiting = shadowOf(nm).getNotification(id)
        assertEquals(Shade.CH_WAITING, waiting.channelId)
        Engine.restore(app, clock + 10_000, front = true)
        assertSame(waiting, shadowOf(nm).getNotification(id))
    }

    /** A context whose foreground service start Android refuses, as it does from the background to an app without
     * exact-alarm access. */
    private val refused: Context by lazy {
        object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = throw ForegroundServiceStartNotAllowedException("background")
        }
    }

    /**
     * Decision 5: without exact-alarm access the alarm is inexact and the service may not start, so nothing plays the
     * ring (measured on Android 16: a silent heads-up). The loud nag then sounds itself on a channel that plays her
     * alarm sound on the alarm stream, insistent until the ring ends; the ring's end makes it silent.
     */
    @Test fun aRingNoServiceCanPlaySoundsOnTheAlarmChannel() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        Engine.schedule(refused, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        val ch = nm.getNotificationChannel(Shade.CH_RING)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertEquals(AudioAttributes.USAGE_ALARM, ch.audioAttributes.usage)
        assertEquals(Settings.System.DEFAULT_ALARM_ALERT_URI, ch.sound)
        assertTrue(ch.shouldVibrate())
        assertEquals("Reminders with sound", ch.name.toString())
        assertNull("the ring's own channel stays silent", nm.getNotificationChannel(Shade.CH_NAG).sound)
        Engine.fire(refused, at("2026-11-18T08:00"))
        assertNull(NagService.running)
        val loud = nag()!!.notification
        assertEquals(Shade.CH_RING, loud.channelId)
        assertTrue(loud.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(Notification.CATEGORY_ALARM, loud.category)
        Engine.fire(refused, at("2026-11-18T08:01"))
        assertEquals(1, group().ignores)
        assertEquals(Shade.CH_WAITING, nag()!!.notification.channelId)
        assertTrue(nag()!!.notification.flags and Notification.FLAG_INSISTENT == 0)
    }

    /** With the service ringing, the loud nag stays on the silent nag channel: the service plays the ring. */
    @Test fun aRingTheServicePlaysIsOnTheSilentChannel() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        serve()
        assertEquals(Shade.CH_NAG, nag()!!.notification.channelId)
        assertTrue(nag()!!.notification.flags and Notification.FLAG_INSISTENT == 0)
    }

    /**
     * The notification shows what the nag screen shows (`src/app/plan.ts` nagRung, nagLineIndex): the rung is the
     * group's ignores up to the top, 4, with that rung's mascot, and the line `lines[rung][(seed + ignores) mod count]`.
     */
    @Test fun theNotificationShowsTheRungAndLineTheNagScreenShows() {
        val seeded = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T23:00", "Pill A", seed = 10)
        val fills = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF")
        val o = NagFixtures.plan(seeded).put("mascots", JSONArray(fills.map { NagFixtures.mascot(it) }))
        Engine.schedule(app, o, at("2026-11-18T07:00"))
        val reds = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        var t = at("2026-11-18T08:00")
        for (ignores in 0..5) {
            clock = t
            Engine.fire(app, t)
            serve()
            val n = nag()!!.notification
            val rung = minOf(4, ignores)
            val lines = NagFixtures.lines[rung]
            assertEquals("ignores $ignores", lines[((10 + ignores) % lines.size)], n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            val picture = (n.getLargeIcon().loadDrawable(app) as BitmapDrawable).bitmap
            assertEquals("ignores $ignores", reds[rung], picture.getPixel(picture.width / 2, picture.height / 2))
            assertEquals(rung, Rules.doses(Store.plan(app)!!, Store.state(app)).single().getInt("rung"))
            Engine.ignore(app, group().nag, "notNow", t + 1_000)
            t = group().nextAt!!
        }
    }
}
