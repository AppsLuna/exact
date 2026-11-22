package pinkoo.nag

import android.app.Notification
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.util.DataSource
import java.time.Duration

/** The bubble, opening at the unlock, the ring service and the stop contract (items 9 to 12), fed the core's posts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class NuisanceTest : SurfaceCase() {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private val windows: List<View> get() =
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(app.getSystemService(WindowManager::class.java)).views
    private fun balloon(): TextView? = windows.filterIsInstance<TextView>().firstOrNull()
    private fun paramsOf(v: View) = v.layoutParams as WindowManager.LayoutParams

    private fun media(vararg uris: android.net.Uri) {
        // Longer than any ring: Robolectric's looping player replays a short file without end while the clock idles.
        for (u in uris) ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, u), ShadowMediaPlayer.MediaInfo(600_000, 0))
    }
    private val phoneSound = "content://media/internal/audio/media/9"

    // MARK: the bubble (item 11)

    /**
     * The bubble at De novo: the face's window first, then the service that holds the process (from target 35 a
     * foreground service may start from the background only while an overlay shows). The balloon, a second window,
     * shows the line: not touchable and at an alpha of at most 0.8, so her taps pass to the app under it; a polite live
     * region for TalkBack; gone after about 6 s while the face stays.
     */
    @Test fun theBubbleShowsItsFaceThenItsBalloonThatTapsPassThrough() {
        ShadowSettings.setCanDrawOverlays(true)
        out().bubble(post(2, Step.AGAIN, bubble = true, line = "a1", rung = 1))
        assertTrue(Bubble.shown)
        assertNotNull("the service holds the process", serve())
        val b = balloon()!!
        assertEquals("a1", b.text.toString())
        val p = paramsOf(b)
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(p.alpha <= Bubble.BALLOON_ALPHA)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, b.accessibilityLiveRegion)
        val face = windows.first { it !is TextView }
        assertEquals(0, paramsOf(face).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals("Tap here, it's time", face.contentDescription)
        // Beside the face, on the screen: the face sits on the right, so the balloon's right edge (END gravity, its x
        // from the right) is 6 dp left of the face. Found on the emulator: it sat off the left edge, never seen.
        val width = app.resources.displayMetrics.widthPixels
        val dp6 = (6 * app.resources.displayMetrics.density).toInt()
        assertEquals(Gravity.END, p.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertEquals("its right edge 6 dp left of the face", paramsOf(face).x - dp6, width - p.x)
        assertTrue("on the screen", p.x in 0 until width && p.y == paramsOf(face).y + (8 * app.resources.displayMetrics.density).toInt())
        idle(Bubble.BALLOON_MS + 100)
        assertNull("the balloon goes", balloon())
        assertTrue("the face stays", Bubble.shown)
        // A refresh of the same step doesn't greet again.
        out().bubble(post(2, Step.AGAIN, sounding = false, bubble = true, line = "a1", rung = 1))
        assertNull(balloon())
    }

    /** Over the lock screen nobody would see them: the balloon and the wiggle wait for the first moment she is in her
     * apps (the unlock). */
    @Test fun overTheLockScreenTheBalloonWaitsForTheUnlock() {
        ShadowSettings.setCanDrawOverlays(true)
        phone(locked = true, on = false)
        out().bubble(post(2, Step.AGAIN, bubble = true, line = "a1"))
        assertTrue(Bubble.shown)
        assertNull(balloon())
        phone(locked = true, on = true)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertNull("the screen on, still locked", balloon())
        phone(locked = false, on = true)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertEquals("a1", balloon()!!.text.toString())
    }

    /** It lives one step: the core takes it away (null), and the service lets go. It hides while the app is in front,
     * and without "Aparecer por cima" it never shows. */
    @Test fun theBubbleGoesWhenTheCoreSaysAndHidesInFront() {
        ShadowSettings.setCanDrawOverlays(true)
        out().bubble(post(2, Step.AGAIN, bubble = true))
        val s = serve()!!
        out().bubble(null)
        assertFalse(Bubble.shown)
        assertNull(NagService.running)
        assertTrue(shadowOf(s).isStoppedBySelf)
        Engine.appInFront = true
        out().bubble(post(2, Step.AGAIN, bubble = true))
        assertFalse(Bubble.shown)
        Engine.appInFront = false
        ShadowSettings.setCanDrawOverlays(false)
        out().bubble(post(2, Step.AGAIN, bubble = true))
        assertFalse(Bubble.shown)
    }

    /** A tap on the face opens the nag route; dragging it into the nest (no X) sends it away and tells the core, which
     * keeps it away for its step, across a process death. */
    @Test fun aTapOpensTheRouteAndTheNestSendsItAway() {
        ShadowSettings.setCanDrawOverlays(true)
        out().bubble(post(2, Step.AGAIN, bubble = true))
        val face = windows.first { it !is TextView }
        val t = SystemClock.uptimeMillis()
        face.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        face.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 10f, 10f, 0))
        val opened = shadowOf(app).nextStartedActivity
        assertNotNull(opened)
        assertTrue(opened.toUri(0), opened.toUri(0).contains("bubble"))
        // Dragged down into the nest at the bottom's middle.
        val m = app.resources.displayMetrics
        val nestX = m.widthPixels / 2f
        val nestY = m.heightPixels - 76 * m.density
        face.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_DOWN, nestX - 300, nestY - 600, 0))
        face.dispatchTouchEvent(MotionEvent.obtain(t, t + 150, MotionEvent.ACTION_MOVE, nestX, nestY, 0))
        assertTrue("the nest shows while dragging", windows.size >= 2)
        face.dispatchTouchEvent(MotionEvent.obtain(t, t + 200, MotionEvent.ACTION_UP, nestX, nestY, 0))
        assertFalse(Bubble.shown)
        assertEquals("the core heard it went to the nest", listOf("nested 2"), told)
        assertTrue("and no window is left", windows.isEmpty())
    }

    // MARK: opening at the unlock (item 12)

    /** "Abrir quando eu desbloquear": the service holds the process while it waits; her unlock opens the nag route once
     * and the wait ends; never in front. */
    @Test fun theUnlockOpensTheRouteOnceWhileTheServiceHoldsTheProcess() {
        ShadowSettings.setCanDrawOverlays(true)
        val o = out()
        o.post(post(3, Step.FULL, fullScreen = true))
        o.waitUnlock(clock + 15 * 60_000L)
        assertTrue(listens(Intent.ACTION_USER_PRESENT))
        assertNotNull("the service holds the process", serve())
        Engine.appInFront = true
        broadcast(Intent.ACTION_USER_PRESENT)
        assertNull("in front nothing opens", shadowOf(app).nextStartedActivity)
        Engine.appInFront = false
        broadcast(Intent.ACTION_USER_PRESENT)
        val opened = shadowOf(app).nextStartedActivity
        assertTrue(opened.toUri(0), opened.toUri(0).contains("unlock"))
        assertFalse("once", Unlock.waiting)
        assertEquals("the core hears it, so no later refresh waits again", listOf("unlocked"), told)
        assertNull("nothing holds the process any more", NagService.running)
    }

    /** The wait ends when the core says (null), at its time, and at most 2 h; without "Aparecer por cima" nothing waits. */
    @Test fun theUnlockWaitEndsAtItsTimeAndAtMostTwoHours() {
        ShadowSettings.setCanDrawOverlays(true)
        val o = out()
        o.waitUnlock(clock + 10 * 60_000L)
        assertTrue(Unlock.waiting)
        o.waitUnlock(null)
        assertFalse(Unlock.waiting)
        o.waitUnlock(clock + 5 * 3600_000L)
        idle(Unlock.MAX_MS - 1_000)
        assertTrue(Unlock.waiting)
        idle(2_000)
        assertFalse("2 h at most", Unlock.waiting)
        o.waitUnlock(clock - 1)
        assertFalse("a time past waits for nothing", Unlock.waiting)
        ShadowSettings.setCanDrawOverlays(false)
        o.waitUnlock(clock + 10 * 60_000L)
        assertFalse(Unlock.waiting)
    }

    /**
     * Once per dose through the core: her unlock opened the nag route, so the app's resume after it (a refresh) waits
     * for nothing again, even when the route never says it shows the dose. Found on the emulator (engine-device,
     * 2026-11-22): the resume armed the wait again and her next unlock opened the route a second time.
     */
    @Test fun theUnlockOpensOnceAcrossTheCoresNextRefresh() {
        Told.reset()
        ShadowSettings.setCanDrawOverlays(true)
        phone(locked = true, on = false)
        val p = planJson().apply { getJSONObject("steps").put("unlock", true) }
        Engine.schedule(app, p, at("2026-11-18T07:00"))
        for (t in listOf("08:00", "08:20", "08:35")) { clock = at("2026-11-18T$t"); Engine.fire(app, clock) }
        assertEquals("2026-11-18T08:00", AndroidOut.last?.groups?.firstOrNull())
        assertTrue("after the full screen it waits", Unlock.waiting)
        clock = at("2026-11-18T08:36")
        phone(locked = false, on = true)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertTrue(shadowOf(app).nextStartedActivity.toUri(0).contains("unlock"))
        assertFalse(Unlock.waiting)
        clock = at("2026-11-18T08:37")
        Engine.restore(app, clock, front = true)
        assertFalse("the resume after it waits for nothing", Unlock.waiting)
        Engine.restore(app, at("2026-11-18T08:38"))
        assertFalse("nor a later refresh", Unlock.waiting)
    }

    // MARK: the ring service (item 9) and the stop contract (item 10)

    /**
     * The alarm rings in the ring service: its foreground notification is its own (low and ongoing on the waiting
     * channel), never the reminder; it loops the dose's sound on the alarm stream at the plan's volume through the
     * decibel curve; once per nag, however often the core asks.
     */
    @Test fun theAlarmRingsTheDosesSoundInTheServiceWithItsOwnNotification() {
        media(android.net.Uri.parse(phoneSound), Settings.System.DEFAULT_ALARM_ALERT_URI)
        val o = out(plan(dose("a@2026-11-18T08:00", "2026-11-18T08:00", sound = phoneSound)))
        o.post(post(4, Step.ALARM, alarm = true, channel = phoneSound))
        o.ring(post(4, Step.ALARM, alarm = true, channel = phoneSound))
        val s = serve()!!
        assertEquals(Shade.SERVICE, shadowOf(s).lastForegroundNotificationId)
        val own = shadowOf(s).lastForegroundNotification
        assertEquals(Shade.CH_WAITING, own.channelId)
        assertTrue(own.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Bolota is around", own.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(NagService.ringing(4))
        assertEquals(Shade.CH_NAG, nag()!!.notification.channelId)
        val player = playerOf(s)!!
        assertEquals(DataSource.toDataSource(app, android.net.Uri.parse(phoneSound)), shadowOf(player).dataSource)
        assertTrue(player.isLooping)
        assertEquals(Sounds.gain(0.6), shadowOf(player).leftVolume, 0.0001f)
        o.ring(post(4, Step.ALARM, alarm = true, channel = phoneSound))
        assertSame("asked again, it rings on", player, playerOf(s))
    }

    /** "Só vibrar" and "Sem som" ring the default preset (Sininho; the platform's alarm in a build with no presets): an
     * alarm must sound. */
    @Test fun anAlarmWithoutASoundPlaysTheDefault() {
        media(Settings.System.DEFAULT_ALARM_ALERT_URI)
        val o = out()
        o.ring(post(4, Step.ALARM, alarm = true, channel = "vibrate"))
        val s = serve()!!
        assertEquals(DataSource.toDataSource(app, Settings.System.DEFAULT_ALARM_ALERT_URI), shadowOf(playerOf(s)!!).dataSource)
    }

    /** Her phone sound can't be read (before the first unlock after a reboot, Android can't open it): the alarm plays
     * the default instead. */
    @Test fun anUnreadablePhoneSoundRingsTheDefault() {
        media(Settings.System.DEFAULT_ALARM_ALERT_URI)
        out().ring(post(4, Step.ALARM, alarm = true, channel = "content://media/external/audio/media/77"))
        val s = serve()!!
        assertEquals(DataSource.toDataSource(app, Settings.System.DEFAULT_ALARM_ALERT_URI), shadowOf(playerOf(s)!!).dataSource)
    }

    /** The decibel curve: 20% is −20 dB, 100% is 0 dB, the steps between even to the ear. */
    @Test fun theAlarmVolumeFollowsTheDecibelCurve() {
        assertEquals(0.1f, Sounds.gain(0.2), 0.0001f)
        assertEquals(1f, Sounds.gain(1.0), 0.0001f)
        assertEquals(0.3162f, Sounds.gain(0.6), 0.0001f)
        assertEquals("below 20% is 20%", 0.1f, Sounds.gain(0.0), 0.0001f)
    }

    private fun playerOf(s: NagService): android.media.MediaPlayer? =
        NagService::class.java.getDeclaredField("player").apply { isAccessible = true }.get(s) as android.media.MediaPlayer?

    private fun ringing(): NagService {
        media(Settings.System.DEFAULT_ALARM_ALERT_URI)
        val o = out()
        o.post(post(1, Step.NOTIFY, sounding = false, alarm = true, folded = true))
        o.ring(post(1, Step.NOTIFY, sounding = false, alarm = true, folded = true))
        return serve()!!
    }

    /**
     * "Parar" (a broadcast to the engine's receiver, so it runs on the locked phone) silences it at once, tells the
     * core, and the ring never starts again for that nag, however often the core asks.
     */
    @Test fun stopFromTheNotificationSilencesAndTellsTheCore() {
        val s = ringing()
        val stop = shadowOf(nag()!!.notification.actions[0].actionIntent).savedIntent
        // The engine's receiver's `STOP`: the core's `Engine.silenced`, or the surface's own handling, as here.
        assertEquals("pinkoo.nag.STOP", stop.action)
        AndroidOut.stopped(app, stop)
        assertNull(playerOf(s))
        assertFalse(NagService.ringing(1))
        assertEquals(listOf("stopped 1 stop"), told)
        assertNull("nothing else holds it", NagService.running)
        out().ring(post(1, Step.NOTIFY, sounding = false, alarm = true, folded = true))
        assertNull("once per nag", NagService.ring)
    }

    /** The unlock stops it; so does the power key: the screen going off once a sound started, or the press that wakes a
     * dark phone once the ring has played about a second (the post's own wake doesn't count). */
    @Test fun theUnlockAndThePowerKeyStopIt() {
        ringing()
        broadcast(Intent.ACTION_USER_PRESENT)
        assertNull(NagService.ring)
        assertEquals(listOf("stopped 1 unlock"), told)
        NagService.rang = 0
        Shade.forget()
        nm.cancelAll()
        ringing()
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertNull(NagService.ring)
        NagService.rang = 0
        nm.cancelAll()
        ringing()
        broadcast(Intent.ACTION_SCREEN_ON)
        assertNotNull("the post's own wake doesn't count", NagService.ring)
        idle(NagService.SCREEN_ON_AFTER_MS + 100)
        broadcast(Intent.ACTION_SCREEN_ON)
        assertNull(NagService.ring)
        assertEquals(listOf("stopped 1 unlock", "stopped 1 power", "stopped 1 power"), told)
        assertFalse("its screen signals go with the sound", listens(Intent.ACTION_SCREEN_ON))
    }

    /** Unanswered, it rings out at 60 s: the core hears the cap (its `timeout`), and the core's stop is idempotent. */
    @Test fun itRingsOutAtItsCapAndAStopRingOfNothingDoesNothing() {
        val s = ringing()
        idle(NagService.RING_MS - 1_000)
        assertNotNull(playerOf(s))
        idle(2_000)
        assertNull(playerOf(s))
        assertNull(NagService.ring)
        assertEquals("the core hears the cap: its timeout", listOf("stopped 1 cap"), told)
        out().stopRing("answered")
        out().stopRing("answered")
        assertNull(NagService.running)
    }

    /** The core's stop ([Out.stopRing]) silences it; the refresh then brings the reminder back on her channel. */
    @Test fun theCoresStopSilencesItAndTheReminderComesBack() {
        val s = ringing()
        val o = out()
        o.stopRing("opened")
        assertNull(playerOf(s))
        assertTrue("the core asked: nothing to tell it", told.isEmpty())
        o.refresh(post(1, Step.NOTIFY, sounding = false, alarm = true, folded = true))
        assertEquals("pinkoo.remind.sininho", nag()!!.notification.channelId)
    }
}
