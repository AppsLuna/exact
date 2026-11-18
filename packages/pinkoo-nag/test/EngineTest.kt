package pinkoo.nag

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSettings
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** The engine on Robolectric's platform: the alarm it sets, what a fire posts and starts, the receivers' restores. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class EngineTest : EngineCase() {
    private fun posted(id: Int): Notification? = nm.activeNotifications.firstOrNull { it.id == id }?.notification

    @Test fun aPlanSetsOneAlarmClockAtItsFirstDoseAndThePlanSentAgainChangesNothing() {
        Engine.schedule(app, NagFixtures.plan(pillA, made = 1), at("2026-11-18T07:00"))
        assertEquals(at("2026-11-18T08:00"), alarms.nextAlarmClock.triggerTime)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        val hash = Store.state(app).planHash
        Engine.schedule(app, NagFixtures.plan(pillA, made = 2), at("2026-11-18T07:30"))
        assertEquals(hash, Store.state(app).planHash)
        assertEquals("only the last plan's time moves", at("2026-11-18T07:30"), Store.state(app).planAt)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test fun withoutExactAlarmsTheChainFallsBackToAnInexactAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        assertNull(alarms.nextAlarmClock)
        val a = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, a.type)
        assertEquals(at("2026-11-18T08:00"), a.triggerAtTime)
        assertFalse(Chain.exact(app))
    }

    @Test fun theAlarmStartsTheRingWhoseServicePostsTheLoudNagWithItsPublicVersion() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        assertEquals("ringing", group().state)
        val started = shadowOf(app).nextStartedService
        assertEquals(NagService::class.java.name, started.component?.className)
        val service = Robolectric.buildService(NagService::class.java, started).create()
        try {
            service.get().onStartCommand(started, 0, 1)
            val n = shadowOf(service.get()).lastForegroundNotification
            assertEquals(Shade.NAG, shadowOf(service.get()).lastForegroundNotificationId)
            assertEquals(Shade.CH_NAG, n.channelId)
            assertEquals(Notification.CATEGORY_ALARM, n.category)
            assertEquals("Time for Pill A", n.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(listOf("take", "promise", "notNow"), n.actions.map { it.title.toString() })
            assertNotNull("a swipe is the nag's ignore", n.deleteIntent)
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals("the lock screen shows no name", "Reminder", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(FullScreen.allowed(app), n.fullScreenIntent != null)
            assertEquals("the alarm is the ring's end", at("2026-11-18T08:01"), alarms.nextAlarmClock.triggerTime)
            // "Not now" from the shade: the ring ends as an ignore, the notification stays, silent, and the next nag is
            // counted from it.
            NagReceiver().onReceive(app, Intent(NagReceiver.NOT_NOW).putExtra("nag", group().nag))
            assertEquals(1, group().ignores)
            assertEquals("notNow", Store.events(app).last().reason)
            assertNull(NagService.running)
            assertEquals(Shade.CH_WAITING, posted(Shade.NAG)?.channelId)
            assertEquals(group().nextAt, alarms.nextAlarmClock.triggerTime)
        } finally { service.destroy() }
    }

    @Test fun withNotificationsOffNothingRingsAndNothingNagsAgainUntilTheLapse() {
        shadowOf(nm).setNotificationsEnabled(false)
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("pending", group().state)
        assertNull(group().nextAt)
        assertEquals(at("2026-11-18T20:00"), alarms.nextAlarmClock.triggerTime)
    }

    @Test fun aSwipedWaitingNagComesBackAtOnceSilentAndCountsOnce() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        val nag = group().nag
        Engine.ignore(app, nag, "timeout", at("2026-11-18T08:01"))
        nm.cancel(Shade.NAG)
        NagReceiver().onReceive(app, Intent(NagReceiver.DISMISS).putExtra("nag", nag))
        assertEquals(1, group().ignores)
        assertEquals(Shade.CH_WAITING, posted(Shade.NAG)?.channelId)
        assertTrue(Store.events(app).count { it.kind == "ignored" } == 1)
    }

    @Test fun aSwipeOfTheLoudNagIsItsIgnoreAndBringsItBackSilent() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        Engine.dismissed(app, group().nag, at("2026-11-18T08:00") + 5_000)
        assertEquals(1, group().ignores)
        assertEquals("swipe", Store.events(app).last().reason)
        assertEquals(Shade.CH_WAITING, posted(Shade.NAG)?.channelId)
        assertEquals(at("2026-11-18T08:20") + 5_000, alarms.nextAlarmClock.triggerTime)
    }

    @Test fun theLowStockNoticeRidesOnTheFirstRingOfEachDay() {
        val tomorrow = NagFixtures.dose("a@2026-11-19T08:00", "2026-11-19T08:00", "2026-11-19T20:00", "Pill A")
        Engine.schedule(app, NagFixtures.plan(pillA, tomorrow, stock = true), at("2026-11-18T07:00"))
        assertNull(posted(Shade.STOCK))
        Engine.fire(app, at("2026-11-18T08:00"))
        assertEquals("Pill A: 3 left", posted(Shade.STOCK)?.extras?.getString(Notification.EXTRA_TEXT))
        nm.cancel(Shade.STOCK)
        Engine.ignore(app, group().nag, "timeout", at("2026-11-18T08:01"))
        Engine.fire(app, at("2026-11-18T08:21"))
        assertNull("once a day", posted(Shade.STOCK))
        Engine.fire(app, at("2026-11-19T08:00"))
        assertNotNull(posted(Shade.STOCK))
    }

    @Test fun aBootATimeChangeAndAZoneChangeSetTheAlarmAgain() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        for (action in listOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            alarms.cancel(shadowOf(alarms).scheduledAlarms.single().operation!!)
            assertNull(alarms.nextAlarmClock)
            NagSystemReceiver().onReceive(app, Intent(action))
            assertEquals(action, at("2026-11-18T08:00"), alarms.nextAlarmClock.triggerTime)
        }
        // A trip to Tokyo: 08:00 there.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        NagSystemReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(at("2026-11-18T08:00"), alarms.nextAlarmClock.triggerTime)
        assertEquals(12 * 3_600_000L, LocalDateTime.parse("2026-11-18T08:00").atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli() - alarms.nextAlarmClock.triggerTime)
    }

    @Test fun aRingTheProcessLostTimesOutAtItsRingEndAsTheAppCountsItAndNagsAgainOnTime() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        NagService.running = null
        clock = at("2026-11-18T08:05")
        NagSystemReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("pending", group().state)
        assertEquals("the ring that ended unanswered is the nag's one ignore, as the app's catch-up counts it", 1, group().ignores)
        val ignored = Store.events(app).last()
        assertEquals("timeout", ignored.reason)
        assertEquals(at("2026-11-18T08:01"), ignored.at)
        assertEquals(at("2026-11-18T08:21"), alarms.nextAlarmClock.triggerTime)
    }

    @Test fun aRingNoServiceRanEndsAtItsRingTimeAsTheNagsOneIgnore() {
        // The system refused the service (no exact-alarm access: an inexact alarm gives no start from the background,
        // measured on Android 16): the loud nag is posted, but nothing times it out but the alarm.
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        assertNull(NagService.running)
        assertEquals("the alarm is the ring's end", at("2026-11-18T08:01"), alarms.nextAlarmClock.triggerTime)
        Engine.fire(app, at("2026-11-18T08:01"))
        assertEquals("pending", group().state)
        assertEquals(1, group().ignores)
        assertEquals(listOf("fired", "ignored"), Store.events(app).map { it.kind })
        assertEquals(Shade.CH_WAITING, posted(Shade.NAG)?.channelId)
        assertEquals(at("2026-11-18T08:21"), alarms.nextAlarmClock.triggerTime)
        // The next nag climbs a rung, and so on.
        Engine.fire(app, at("2026-11-18T08:21"))
        Engine.fire(app, at("2026-11-18T08:22"))
        assertEquals(2, group().ignores)
        assertEquals(at("2026-11-18T08:37"), alarms.nextAlarmClock.triggerTime)
    }

    @Test fun theServicesOwnTimerEndsTheRingAtTheRingsEnd() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        val started = shadowOf(app).nextStartedService
        val service = Robolectric.buildService(NagService::class.java, started).create()
        try {
            clock = at("2026-11-18T08:00") + 400
            service.get().onStartCommand(started, 0, 1)
            clock = at("2026-11-18T08:01")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(60))
            assertEquals(1, group().ignores)
            assertEquals("at the ring's end, not a start's delay later", at("2026-11-18T08:01"), Store.events(app).last().at)
        } finally { service.destroy() }
    }

    @Test fun aDoseMutedWhileNotificationsWereOffNagsOnceTheyAreBackAndTheAppIsOpened() {
        shadowOf(nm).setNotificationsEnabled(false)
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        assertEquals("pending", group().state)
        shadowOf(nm).setNotificationsEnabled(true)
        // She turns them on and opens the app: the module is made, and the dose nags at once.
        Engine.restore(app, at("2026-11-18T08:30"))
        assertEquals(at("2026-11-18T08:30"), alarms.nextAlarmClock.triggerTime)
        Engine.fire(app, at("2026-11-18T08:30"))
        assertEquals("ringing", group().state)
    }

    @Test fun aFireThatStartsNoServiceLetsGoOfItsWakeLock() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        clock = at("2026-11-18T07:30")
        NagReceiver().onReceive(app, Intent(NagReceiver.FIRE))
        assertFalse("nothing was due: no service will release it", ShadowPowerManager.getLatestWakeLock().isHeld)
        clock = at("2026-11-18T08:00")
        NagReceiver().onReceive(app, Intent(NagReceiver.FIRE))
        assertTrue("held until the service it started has started", ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun theRingPlaysTheSettingsAlarmSoundWhichSoundsBeforeTheFirstUnlock() {
        // Before the first unlock after a boot the sound's own file (content://media/…) cannot be read (measured on
        // Android 16: "setDataSource failed"); the settings' default URI plays from the device-protected cache.
        android.provider.Settings.System.putString(app.contentResolver, android.provider.Settings.System.ALARM_ALERT, "content://media/internal/audio/media/7")
        org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
            org.robolectric.shadows.util.DataSource.toDataSource(app, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI),
            org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(10_000, 0))
        val p = NagService.player(app, 0.6)!!
        try {
            assertEquals(org.robolectric.shadows.util.DataSource.toDataSource(app, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI), shadowOf(p).dataSource)
            assertTrue(p.isPlaying)
        } finally { p.release() }
    }

    @Test fun theStaleNoticeCountsFromTheLastTimeTheAppWasInFront() {
        // The app sends its plan only when it changed (src/app/actions.ts appOpened): opened on day 5 with nothing
        // new, the plan it holds still covers the week from then.
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.resolve(app, listOf(pillA.getString("key")), "taken", null, at("2026-11-18T08:00"))
        assertEquals("day 6, before the plan's seven days run out", at("2026-11-24T07:00"), alarms.nextAlarmClock.triggerTime)
        Engine.restore(app, at("2026-11-23T09:00"), front = true)
        assertEquals(at("2026-11-29T09:00"), alarms.nextAlarmClock.triggerTime)
        // A boot is no opening.
        Engine.restore(app, at("2026-11-24T09:00"))
        assertEquals(at("2026-11-29T09:00"), alarms.nextAlarmClock.triggerTime)
    }

    @Test fun aMalformedMascotPartNeverTakesThePlanDown() {
        val o = NagFixtures.plan(pillA)
        o.getJSONObject("mascot").put("parts", JSONArray()
            .put(JSONObject().put("t", "circle").put("cx", 10).put("cy", 10).put("r", 5).put("fill", "#FF7A59"))
            .put(JSONObject().put("t", "circle").put("r", 5).put("fill", "#FF7A59").put("tf", "rotate(10deg) scale(x)")))
        Engine.schedule(app, o, at("2026-11-18T07:00"))
        assertNotNull("the plan is kept", Store.plan(app))
        assertEquals(2, Store.plan(app)!!.mascot(0)!!.parts.size)
        assertEquals(at("2026-11-18T08:00"), alarms.nextAlarmClock.triggerTime)
    }

    @Test fun aPromiseCountsDownAndOnceDismissedIsNotShownAgain() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        NagService.running = null
        Engine.resolve(app, listOf(pillA.getString("key")), "promised", at("2026-11-18T08:20"), at("2026-11-18T08:01"))
        val n = posted(Shade.PROMISE)!!
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(at("2026-11-18T08:20"), n.`when`)
        assertNull("nothing waits while promised", posted(Shade.NAG))
        assertEquals(at("2026-11-18T08:20"), alarms.nextAlarmClock.triggerTime)
        NagReceiver().onReceive(app, Intent(NagReceiver.PROMISE_DISMISSED).putExtra("at", at("2026-11-18T08:20")))
        assertNull(posted(Shade.PROMISE))
        Engine.restore(app, at("2026-11-18T08:02"))
        assertNull(posted(Shade.PROMISE))
        // Resolved: nothing left to nag; the one alarm left is the stale notice's, six days after the last plan.
        Engine.resolve(app, listOf(pillA.getString("key")), "taken", null, at("2026-11-18T08:03"))
        assertEquals(at("2026-11-24T07:00"), alarms.nextAlarmClock.triggerTime)
    }

    @Test fun eventsWaitUntilTheAppAcknowledgesThemAndTheOldestGoBeyondAThousand() {
        Store.append(app, (1L..1005L).map { Event(it, "k", "fired", null, it) })
        val kept = Store.events(app)
        assertEquals(Store.EVENTS, kept.size)
        assertEquals(6L, kept.first().id)
        Engine.ack(app, 505)
        assertEquals(500, Store.events(app).size)
        assertEquals(506L, Store.events(app).first().id)
    }

    @Test fun theRegistryTurnsOnWhatARungNamesWhenAllowedAndIgnoresWhatItDoesNotKnow() {
        val o = NagFixtures.plan(pillA)
        o.getJSONArray("rungs").getJSONObject(1).put("add", JSONArray(listOf("returning", "wallpaper")))
        val plan = Plan.parse(o)
        val st = State()
        Rules.merge(plan, st, 0)
        val g = st.groups.values.single().apply { state = "pending"; nag = 1 }
        val words = { ignores: Int -> g.ignores = ignores; Live(plan, st).let { l -> Nuisances.all.filter { Nuisances.on(app, it, l) }.map { it.word } } }
        assertEquals(listOf("fullScreen").filter { FullScreen.allowed(app) }, words(0))
        assertEquals(listOf("fullScreen").filter { FullScreen.allowed(app) } + "returning", words(1))
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals("no overlay access: the bubble and unlock are skipped, the rest keep their order",
            listOf("fullScreen").filter { FullScreen.allowed(app) } + "returning", words(3))
        ShadowSettings.setCanDrawOverlays(true)
        assertEquals(listOf("fullScreen").filter { FullScreen.allowed(app) } + listOf("returning", "bubble", "unlock"), words(3))
        assertFalse(Nuisances.all.any { it.word == "wallpaper" })
    }

    @Test fun theStateTheAppReadsHasEachDoseAndTheEventsInOrder() {
        Engine.schedule(app, NagFixtures.plan(pillA), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        Engine.opened(app, listOf(pillA.getString("key")), at("2026-11-18T08:00") + 9_000)
        val doses = Rules.doses(Store.plan(app)!!, Store.state(app))
        assertEquals("pending", doses.single().getString("state"))
        assertEquals(listOf("fired", "opened"), Store.events(app).map { it.kind })
        val ids = Store.events(app).map { it.json().getString("id").toLong() }
        assertTrue("unique and rising, from above any id a state before could have handed out", ids[0] > at("2026-11-18T07:00") && ids[1] == ids[0] + 1)
        assertTrue(JSONObject(Store.events(app).last().json().toString()).isNull("reason"))
    }
}
