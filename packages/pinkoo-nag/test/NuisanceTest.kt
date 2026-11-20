package pinkoo.nag

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.util.DataSource

/** The bubble, opening at unlock and the ring's sound, through the ladder, a promise and a process death. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class NuisanceTest : EngineCase() {
    /**
     * Decision 5: a bubble she drags onto the cross is that nag's ignore and stays closed until the next nag, even
     * across a process death (it is kept in the state, not in memory).
     */
    @Test fun aClosedBubbleStaysClosedUntilTheNextNagAcrossAProcessDeath() {
        ShadowSettings.setCanDrawOverlays(true)
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        val t = climb(2)
        assertTrue("rung 2, waiting: the bubble", Bubble.shown)
        clock = t
        Engine.fire(app, t)
        serve()
        assertTrue(Bubble.shown)
        val nag = group().nag
        Engine.bubbleClosed(app, nag, t + 2_000)
        assertFalse(Bubble.shown)
        assertEquals("the ringing nag's ignore", 3, group().ignores)
        assertEquals("bubble", Store.events(app).last().reason)
        assertEquals(nag, Store.state(app).bubbleClosed)
        // The process dies: the window and the service go with it. Back (a boot, an alarm), the bubble stays closed.
        Bubble.hide()
        NagService.running = null
        Engine.restore(app, t + 60_000)
        serve()
        assertFalse(Bubble.shown)
        assertEquals(3, group().ignores)
        // The next nag brings it back.
        clock = group().nextAt!!
        Engine.fire(app, clock)
        serve()
        assertTrue(Bubble.shown)
    }

    /**
     * Decision 4: during a pinky promise the bubble and opening at unlock pause, the rung is kept, and the deadline's
     * nag comes back at that same rung, with them.
     */
    @Test fun aPromisePausesTheBubbleAndTheUnlockAndItsDeadlineNagsAtTheSameRung() {
        ShadowSettings.setCanDrawOverlays(true)
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        val t = climb(3)
        assertTrue(Bubble.shown)
        assertTrue(listensToUnlock())
        assertNotNull("the service holds the bubble and the unlock", NagService.running)
        val now = t - 60_000
        clock = now
        Engine.resolve(app, listOf(pillA.getString("key")), "promised", now + 15 * 60_000L, now)
        assertFalse(Bubble.shown)
        assertFalse(listensToUnlock())
        assertNull("nothing holds the process meanwhile", NagService.running)
        assertNull(nag())
        assertNotNull(nm.activeNotifications.firstOrNull { it.id == Shade.PROMISE })
        assertEquals(3, group().ignores)
        assertEquals(now + 15 * 60_000L, alarms.nextAlarmClock.triggerTime)
        clock = now + 15 * 60_000L
        Engine.fire(app, clock)
        serve()
        assertEquals("ringing", group().state)
        assertEquals("the same rung", 3, Rules.rung(Store.plan(app)!!, Store.state(app), group()))
        assertTrue(Bubble.shown)
        assertTrue(listensToUnlock())
    }

    /** Decision 5: with her alarm sound set to None the ring plays her ringtone; with one set, the alarm sound. */
    @Test fun theRingPlaysTheRingtoneWhenTheAlarmSoundIsNone() {
        for (uri in listOf(Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_RINGTONE_URI)) {
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, uri), ShadowMediaPlayer.MediaInfo(10_000, 0))
        }
        Settings.System.putString(app.contentResolver, Settings.System.RINGTONE, "content://media/internal/audio/media/3")
        Settings.System.putString(app.contentResolver, Settings.System.ALARM_ALERT, null)
        NagService.player(app, 0.6)!!.let { p ->
            try { assertEquals(DataSource.toDataSource(app, Settings.System.DEFAULT_RINGTONE_URI), shadowOf(p).dataSource) } finally { p.release() }
        }
        // The ring channel, made for a nag no service can ring, takes the same sound.
        Shade.channels(app, Plan.parse(NagFixtures.plan()).texts)
        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, nm.getNotificationChannel(Shade.CH_RING).sound)
        Settings.System.putString(app.contentResolver, Settings.System.ALARM_ALERT, "content://media/internal/audio/media/7")
        NagService.player(app, 0.6)!!.let { p ->
            try { assertEquals(DataSource.toDataSource(app, Settings.System.DEFAULT_ALARM_ALERT_URI), shadowOf(p).dataSource) } finally { p.release() }
        }
    }
}
