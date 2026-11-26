package pinkoo.nag

import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** The engine on Robolectric's platform, on a fake [Out]: the alarms it sets, what each way in asks Android to show
 * and play, the receivers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class EngineTest : CoreCase() {
    private val pill8 = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A")
    private fun g20() = Store.state(app).groups.getValue("2026-11-18T20:00")

    /** The alarm armed under [code], or null. */
    private fun armed(code: Int): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(alarms).scheduledAlarms.firstOrNull { it.operation != null && shadowOf(it.operation).requestCode == code }

    /** The alarm under [code] fires, as Android would deliver it: through the receiver, at its time. */
    private fun deliver(code: Int) {
        val a = armed(code) ?: error("nothing armed under $code")
        clock = a.triggerAtTime
        NagReceiver().onReceive(app, shadowOf(a.operation).savedIntent)
    }

    /** Every alarm delivered in time order up to [until] (the earliest first, code 1 before code 0 at the same time). */
    private fun run(until: Long) {
        repeat(300) {
            val next = listOf(Chain.ALARM, Chain.STEP).mapNotNull { c -> armed(c)?.let { c to it.triggerAtTime } }.minByOrNull { it.second } ?: return
            if (next.second > until) return
            deliver(next.first)
        }
        error("no end")
    }

    // MARK: the alarms

    /** A reminder is a notification at an exact time with no alarm clock (no icon, her dose times told to no other
     * app): code 0 exact while idle, an inexact backup beside it, and no code 1 while the alarm is off. */
    @Test fun aPlanArmsAnExactAlarmAndItsBackupAndNoAlarmClock() {
        Engine.schedule(app, NagFixtures.plan(pill8, made = 1), at("2026-11-18T19:00"))
        val step = armed(Chain.STEP)!!
        assertEquals(at("2026-11-18T20:00"), step.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, step.type)
        assertTrue(step.isAllowWhileIdle)
        assertNull("no alarm clock", step.alarmClockInfo)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, step.windowLengthMs)
        val backup = armed(Chain.BACKUP)!!
        assertEquals(at("2026-11-18T20:00"), backup.triggerAtTime)
        assertTrue(backup.isAllowWhileIdle)
        assertTrue("inexact", backup.windowLengthMs != ShadowAlarmManager.WINDOW_EXACT)
        assertNull(armed(Chain.ALARM))
        assertNull(alarms.nextAlarmClock)
        val hash = Store.state(app).planHash
        Engine.schedule(app, NagFixtures.plan(pill8, made = 2), at("2026-11-18T19:30"))
        assertEquals("only the plan's time changed", hash, Store.state(app).planHash)
        assertEquals(at("2026-11-18T19:30"), Store.state(app).planAt)
        assertEquals(2, shadowOf(alarms).scheduledAlarms.size)
    }

    /** The alarm she turned on is the one alarm clock (code 1), armed on its own at its on-time time; code 0 at the
     * steps before it. */
    @Test fun theAlarmSheTurnedOnIsAnAlarmClockArmedOnItsOwn() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        assertEquals(at("2026-11-18T20:00"), armed(Chain.STEP)!!.triggerAtTime)
        assertEquals(at("2026-11-18T20:50"), armed(Chain.ALARM)!!.triggerAtTime)
        assertEquals(at("2026-11-18T20:50"), alarms.nextAlarmClock.triggerTime)
        assertEquals("the backup at the earliest", at("2026-11-18T20:00"), armed(Chain.BACKUP)!!.triggerAtTime)
        // "Já na hora": the dose time is the alarm's, code 0 at the next event.
        NagFixtures.clear(app)
        val onTime = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "onTime")
        Engine.schedule(app, NagFixtures.plan(onTime), at("2026-11-18T19:00"))
        assertEquals(at("2026-11-18T20:00"), armed(Chain.ALARM)!!.triggerAtTime)
        assertEquals("the lapse", at("2026-11-19T08:00"), armed(Chain.STEP)!!.triggerAtTime)
    }

    /** Battery restricted ("Restrito", or the restricted standby bucket): Android holds exact while-idle alarms back but
     * never an alarm clock, so code 0 is one too. */
    @Test fun withTheBatteryRestrictedCode0IsAnAlarmClock() {
        shadowOf(app.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        assertNotNull(armed(Chain.STEP)!!.alarmClockInfo)
        assertEquals(at("2026-11-18T20:00"), alarms.nextAlarmClock.triggerTime)
        shadowOf(app.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        Engine.restore(app, at("2026-11-18T19:01"))
        assertNull("rare is no restriction", armed(Chain.STEP)!!.alarmClockInfo)
        // Background use denied (RUN_ANY_IN_BACKGROUND): the same.
        shadowOf(app.getSystemService(android.app.ActivityManager::class.java)).setBackgroundRestricted(true)
        Engine.restore(app, at("2026-11-18T19:02"))
        assertNotNull(armed(Chain.STEP)!!.alarmClockInfo)
        assertTrue(Chain.restricted(app))
    }

    /** Without exact access (never granted, or taken away): the inexact backup alone, no alarm clock, and the alarm step
     * comes as a notification step, late perhaps, but it comes (item 14). */
    @Test fun withoutExactAccessTheBackupAloneAndTheAlarmStepIsANotification() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        Engine.schedule(app, NagFixtures.plan(d, fullScreen = false), at("2026-11-18T19:00"))
        assertNull(armed(Chain.STEP))
        assertNull(armed(Chain.ALARM))
        assertNull(alarms.nextAlarmClock)
        assertEquals(at("2026-11-18T20:00"), armed(Chain.BACKUP)!!.triggerAtTime)
        repeat(3) { deliver(Chain.BACKUP) }
        assertEquals(listOf(Step.NOTIFY, Step.AGAIN, Step.ALARM), out.posts.map { it.step })
        assertFalse("the alarm step as a notification", out.posts.last().alarm)
        assertFalse("with no full screen of its own", out.posts.last().fullScreen)
        assertTrue(out.posts.last().sounding)
        assertTrue(out.rings.isEmpty())
        assertFalse(Chain.exact(app))
    }

    /**
     * Code 1 never waits for code 0: De novo's exact alarm deferred 40 min (Doze), the alarm clock rings at 20:50 on
     * time, the steps it passed are skipped, and the deferred wake at 21:00 finds nothing due. The inexact backup, at
     * the alarm's time too, leaves the alarm to its alarm clock.
     */
    @Test fun theAlarmRingsOnTimeAfterACode0WakeFortyMinutesLate() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        deliver(Chain.STEP)
        assertEquals(Step.NOTIFY, out.posts.single().step)
        assertEquals(at("2026-11-18T20:20"), armed(Chain.STEP)!!.triggerAtTime)
        assertEquals(at("2026-11-18T20:50"), armed(Chain.ALARM)!!.triggerAtTime)
        assertEquals("the backup at the earliest", at("2026-11-18T20:20"), armed(Chain.BACKUP)!!.triggerAtTime)
        // Code 0 and its backup held until 20:50 (deep Doze): the backup's wake there rings nothing.
        clock = at("2026-11-18T20:50")
        NagReceiver().onReceive(app, Intent(NagReceiver.FIRE).putExtra(NagReceiver.BY, Chain.BACKUP))
        assertEquals(listOf(Step.NOTIFY), out.posts.map { it.step })
        assertTrue(out.rings.isEmpty())
        // The alarm clock does, on time.
        deliver(Chain.ALARM)
        assertEquals(listOf(Step.NOTIFY, Step.ALARM), out.posts.map { it.step })
        assertTrue(out.posts.last().alarm)
        assertEquals("ring:2", out.calls.last { it.startsWith("ring") })
        val g = g20()
        assertEquals("ringing", g.state)
        assertFalse("the full screen it passed never came", Store.state(app).doses.values.single().full)
        // The deferred code-0 wake at 21:00: the ring's cap passed, nothing else.
        clock = at("2026-11-18T21:00")
        NagReceiver().onReceive(app, Intent(NagReceiver.FIRE).putExtra(NagReceiver.BY, Chain.STEP))
        assertEquals(2, out.posts.size)
        assertEquals("pending", g20().state)
        assertEquals(at("2026-11-18T21:20"), armed(Chain.STEP)!!.triggerAtTime)
    }

    /** A wake with nothing due does nothing: no post, no event, the alarms as they were, its wake lock let go. */
    @Test fun aWakeWithNothingDueDoesNothing() {
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        val before = Store.state(app).json().put("planAt", 0).toString()
        clock = at("2026-11-18T19:30")
        NagReceiver().onReceive(app, Intent(NagReceiver.FIRE))
        assertTrue(out.posts.isEmpty())
        assertTrue(Store.events(app).isEmpty())
        assertEquals(before, Store.state(app).json().put("planAt", 0).toString())
        assertEquals(at("2026-11-18T20:00"), armed(Chain.STEP)!!.triggerAtTime)
        assertFalse("nothing will release it", ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    // MARK: the shared scenarios, through the engine

    /** Every thread scenario through the engine's ways in, its alarms and the fake [Out]: the same fires, in order. */
    @Test fun everyThreadScenarioThroughTheEngine() {
        val doc = JSONObject(listOf("../../../packages/pinkoo-nag/test/thread-scenarios.json", "packages/pinkoo-nag/test/thread-scenarios.json")
            .map { File(File(System.getProperty("user.dir")), it) }.first { it.exists() }.readText())
        val paces = doc.getJSONObject("paces")
        val cases = doc.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            NagFixtures.clear(app); shadowOf(alarms).scheduledAlarms.toList().forEach { a -> a.operation?.let { alarms.cancel(it) } }
            out.reset()
            val pace = c.opt("pace").let { if (it is String) paces.getJSONObject(it) else it as JSONObject }
            val steps = c.getJSONObject("steps")
            val dose = c.getJSONObject("dose")
            val key = "d@" + dose.getString("at")
            val d = NagFixtures.dose(key, dose.getString("at"), dose.getString("miss"), "Pill", seed = dose.getLong("seed"), alarm = c.getString("alarm"))
            Engine.schedule(app, NagFixtures.plan(d, first = pace.getInt("firstMin"), then = pace.getInt("thenMin"), repeatFirst = pace.getInt("repeatFirstMin"),
                repeatEvery = pace.getInt("repeatEveryMin"), repeats = pace.getInt("repeats"), bubble = steps.getBoolean("bubble"),
                fullScreen = steps.getBoolean("fullScreen"), unlock = steps.getBoolean("unlock")), at(dose.getString("at")) - 3_600_000L)
            val times = ArrayList<Long>()
            val events = (0 until c.getJSONArray("events").length()).map { c.getJSONArray("events").getJSONObject(it) }.sortedBy { at(it.getString("at")) }
            for (e in events + JSONObject().put("at", dose.getString("miss")).put("do", "end")) {
                val until = at(e.getString("at"))
                var last: Pair<Int, Long>? = null
                repeat(100) {
                    val next = listOf(Chain.ALARM, Chain.STEP).mapNotNull { k -> armed(k)?.let { k to it.triggerAtTime } }.minByOrNull { it.second }
                    if (next == null || next.second > until) return@repeat
                    assertTrue("$name: a wake re-armed itself ($next): a time nothing acts on", next != last)
                    last = next
                    val n = out.posts.size
                    deliver(next.first)
                    if (out.posts.size > n) times += clock
                }
                clock = until
                when (e.getString("do")) {
                    "notNow" -> Engine.notNowKeys(app, listOf(key), until)
                    "promise" -> Engine.resolve(app, listOf(key), "promised", at(e.getString("until")), until)
                    "swipe" -> NagReceiver().onReceive(app, Intent(NagReceiver.DISMISS).putExtra("nag", Rules.live(Store.plan(app)!!, Store.state(app))))
                    "open" -> Engine.opened(app, listOf(key), until)
                }
            }
            val got = out.posts.zip(times).map { (p, t) ->
                "${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ZoneId.systemDefault())} ${Rules.word(p.step)} rung=${p.rung} " +
                    "sounding=${p.sounding} bubble=${p.bubble} fullScreen=${p.fullScreen} alarm=${p.alarm} folded=${p.folded}"
            }
            val expect = c.getJSONArray("expect")
            val want = (0 until expect.length()).map { expect.getJSONObject(it) }.map { x ->
                "${x.getString("at")} ${x.getString("step")} rung=${x.getInt("rung")} sounding=${x.getBoolean("sounding")} bubble=${x.getBoolean("bubble")} " +
                    "fullScreen=${x.getBoolean("fullScreen")} alarm=${x.getBoolean("alarm")} folded=${x.getBoolean("folded")}"
            }
            assertEquals(name, want.joinToString("\n"), got.joinToString("\n"))
            // The alarm rang exactly where the scenario says, and stopped at its cap.
            assertEquals(name, want.count { it.contains("alarm=true") }, out.calls.count { it.startsWith("ring:") })
        }
    }

    // MARK: her answers

    /**
     * A swipe is kept, not counted: through a refresh (the app back in front), a plan sent again and another group's
     * fire, the reminder stays away and nothing sounds; its next step posts it anew.
     */
    @Test fun aSwipeStaysAwayThroughARefreshAPlanAndAnotherGroupsFire() {
        val b = NagFixtures.dose("b@2026-11-18T20:10", "2026-11-18T20:10", "2026-11-19T08:00", "Pill B")
        Engine.schedule(app, NagFixtures.plan(pill8, b, made = 1), at("2026-11-18T19:00"))
        deliver(Chain.STEP)
        val nag = out.posts.single().nag
        out.reset()
        NagReceiver().onReceive(app, Intent(NagReceiver.DISMISS).putExtra("nag", nag))
        assertNull(out.showing)
        Engine.restore(app, at("2026-11-18T20:02"), front = true)
        Engine.schedule(app, NagFixtures.plan(pill8, b, made = 2).put("liveUpdate", false), at("2026-11-18T20:03"))
        assertTrue("nothing posted, nothing refreshed: ${out.calls}", out.calls.all { it == "clear" })
        assertTrue(Store.events(app).none { it.kind == "ignored" })
        deliver(Chain.STEP)
        assertEquals("another group's post doesn't bring A back", listOf("Pill B"), out.posts.single().names)
        Engine.restore(app, at("2026-11-18T20:11"))
        assertEquals(listOf("Pill B"), out.refreshes.last().names)
        deliver(Chain.STEP)
        assertEquals(Step.AGAIN, out.posts.last().step)
        assertEquals(listOf("Pill A", "Pill B"), out.posts.last().names)
        assertTrue(out.posts.last().sounding)
    }

    /**
     * The notification's "Agora não": the next step moves to one gap after the tap, the post turns into the plan's
     * confirmation with that time for 5 s (nothing refreshes it meanwhile), then it goes and stays away until the next
     * step. Logged `notNow`, counting nothing.
     */
    @Test fun theNotificationsAgoraNaoConfirmsAndStaysAwayUntilTheNextStep() {
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        deliver(Chain.STEP)
        val nag = out.posts.single().nag
        out.reset()
        clock = at("2026-11-18T20:10")
        NagReceiver().onReceive(app, Intent(NagReceiver.NOT_NOW).putExtra("nag", nag))
        assertEquals(listOf(nag to "Again at 20:30."), out.confirms)
        assertEquals("the confirmation is left alone", listOf("confirm:$nag"), out.calls)
        assertEquals(at("2026-11-18T20:30"), armed(Chain.STEP)!!.triggerAtTime)
        assertEquals(Step.NOTIFY, g20().step)
        assertEquals(listOf("fired", "ignored"), Store.events(app).map { it.kind })
        assertEquals("notNow", Store.events(app).last().reason)
        assertEquals("notify", Store.events(app).last().json().getString("step"))
        Engine.restore(app, at("2026-11-18T20:10") + 3_000)
        assertFalse("still confirming", out.calls.contains("clear"))
        Engine.restore(app, at("2026-11-18T20:10") + 6_000)
        assertTrue("then away", out.calls.contains("clear"))
        assertNull(out.showing)
        // An old post's "Agora não" does nothing.
        NagReceiver().onReceive(app, Intent(NagReceiver.NOT_NOW).putExtra("nag", nag))
        assertEquals(1, out.confirms.size)
        deliver(Chain.STEP)
        assertEquals(Step.AGAIN, out.posts.last().step)
        assertNotNull(out.showing)
        // After the last repeat: the dose waits on Hoje.
        run(at("2026-11-18T23:40"))
        val last = out.posts.last().nag
        clock = at("2026-11-18T23:45")
        NagReceiver().onReceive(app, Intent(NagReceiver.NOT_NOW).putExtra("nag", last))
        assertEquals("It waits on Today.", out.confirms.last().second)
    }

    /** "Agora não" on the nag screen (`ignoreNags`): the same move, no confirmation (the app says it). Before the alarm
     * comes next, the notification's confirmation names it. */
    @Test fun theNagScreensAgoraNaoMovesTheSameAndTheAlarmIsNamed() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:35"))
        assertEquals(Step.FULL, out.posts.last().step)
        Engine.notNowKeys(app, listOf("a@2026-11-18T20:00"), at("2026-11-18T20:40"))
        assertTrue(out.confirms.isEmpty())
        assertEquals("the alarm moves with the thread", at("2026-11-18T20:55"), armed(Chain.ALARM)!!.triggerAtTime)
        assertNull("the reminder stays away", out.showing)
        Engine.notNow(app, out.posts.last().nag, at("2026-11-18T20:41"))
        assertTrue("dismissed: no post to confirm", out.confirms.isEmpty())
        // From a showing notification, the alarm is named.
        NagFixtures.clear(app); out.reset()
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:35"))
        clock = at("2026-11-18T20:36")
        NagReceiver().onReceive(app, Intent(NagReceiver.NOT_NOW).putExtra("nag", out.posts.last().nag))
        assertEquals("The alarm at 20:51.", out.confirms.single().second)
    }

    // MARK: what Android shows and plays

    /** The alarm rings for its cap and stops (`timeout`); "Parar" (a broadcast, on the locked phone) silences it; a
     * swipe on it silences it too. Nothing moves the next step. */
    @Test fun theAlarmRingsToItsCapOrUntilStopped() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "onTime")
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        out.reset()
        deliver(Chain.ALARM)
        val p = out.posts.single()
        assertTrue(p.alarm && p.folded && !p.sounding)
        assertEquals(listOf("post:${p.nag}", "ring:${p.nag}"), out.calls)
        assertEquals("its cap wakes the engine", at("2026-11-18T20:01"), armed(Chain.STEP)!!.triggerAtTime)
        deliver(Chain.STEP)
        assertEquals("stopRing:timeout", out.calls.last())
        assertEquals("timeout", Store.events(app).last().reason)
        assertEquals(at("2026-11-18T20:01"), Store.events(app).last().at)
        assertEquals(at("2026-11-18T20:20"), g20().nextAt)
        assertFalse(out.refreshes.last().alarm)
        // Another dose: "Parar".
        NagFixtures.clear(app); out.reset()
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        deliver(Chain.ALARM)
        clock += 10_000
        Engine.silenced(app, "stop")
        assertEquals("stopRing:stop", out.calls.last())
        assertEquals("pending", g20().state)
        assertTrue("silenced counts nothing", Store.events(app).none { it.kind == "ignored" })
        // And a swipe on it.
        NagFixtures.clear(app); out.reset()
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        deliver(Chain.ALARM)
        NagReceiver().onReceive(app, Intent(NagReceiver.DISMISS).putExtra("nag", out.posts.single().nag))
        assertEquals("stopRing:dismissed", out.calls.last())
        assertNull(out.showing)
    }

    /** Between steps the post showing is refreshed in place: the waiting title, the same step, face and line, never a
     * full screen; a promised dose leaves it, and the channel is then the first dose it still names. */
    @Test fun aRefreshShowsThePostWaitingWithoutAFullScreen() {
        val a = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", sound = "sininho")
        val b = NagFixtures.dose("b@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill B", sound = "kalimba")
        Engine.schedule(app, NagFixtures.plan(a, b), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:35"))
        val full = out.posts.last()
        assertEquals(Step.FULL, full.step)
        assertTrue(full.fullScreen)
        Engine.restore(app, at("2026-11-18T20:36"))
        val r = out.refreshes.last()
        assertEquals(full.nag, r.nag)
        assertEquals("Pill A, Pill B waits", r.title)
        assertEquals(Step.FULL, r.step)
        assertFalse(r.fullScreen)
        assertFalse(r.sounding)
        assertEquals(full.rung, r.rung)
        assertEquals(full.line, r.line)
        assertEquals("sininho", r.channel)
        Engine.resolve(app, listOf("a@2026-11-18T20:00"), "promised", at("2026-11-18T21:00"), at("2026-11-18T20:37"))
        assertEquals(listOf("b@2026-11-18T20:00"), out.refreshes.last().keys)
        assertEquals("kalimba", out.refreshes.last().channel)
    }

    /**
     * Glauco, 2026-11-24: the alarm's post brings the full screen every time it rings, "Só se passar de tudo" after Tela
     * cheia already showed and "Já na hora" at the dose time, and it isn't the dose's Tela cheia, which still comes once
     * (the surface attaches the intent only with the access, `ShadeTest`).
     */
    @Test fun theAlarmBringsTheFullScreenEveryTimeItRings() {
        val after = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        Engine.schedule(app, NagFixtures.plan(after), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:35"))
        assertTrue("Tela cheia", out.posts.last().let { it.step == Step.FULL && it.fullScreen })
        run(at("2026-11-18T20:50"))
        val alarm = out.posts.last()
        assertEquals(Step.ALARM, alarm.step)
        assertTrue("after Tela cheia already showed", alarm.alarm && alarm.fullScreen && !alarm.sounding)
        assertEquals("ring:${alarm.nag}", out.calls.last { it.startsWith("ring:") })
        // With Tela cheia off, too.
        NagFixtures.clear(app); out.reset()
        Engine.schedule(app, NagFixtures.plan(after, fullScreen = false), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:35"))
        assertTrue(out.posts.last().let { it.step == Step.ALARM && it.fullScreen })
        assertTrue("no Tela cheia spent", Store.state(app).doses.values.none { it.full })
        // "Já na hora": the dose time's notification folded into the ring brings it; Tela cheia still comes, once.
        NagFixtures.clear(app); out.reset()
        val onTime = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "onTime")
        Engine.schedule(app, NagFixtures.plan(onTime), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:00"))
        assertTrue(out.posts.single().let { it.step == Step.NOTIFY && it.folded && it.alarm && it.fullScreen })
        assertFalse(Store.state(app).doses.values.single().full)
        run(at("2026-11-18T20:35"))
        assertTrue("the dose's Tela cheia, still", out.posts.last().let { it.step == Step.FULL && it.fullScreen && !it.alarm })
    }

    /** The bubble at De novo, as long as its step; sent to the nest it stays away for the step, across a process death
     * (the state keeps it), and logs nothing. */
    @Test fun theBubbleShowsForDeNovoAndTheNestKeepsItAway() {
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        deliver(Chain.STEP)
        assertNull(out.bubble)
        deliver(Chain.STEP)
        assertEquals(Step.AGAIN, out.bubble!!.step)
        assertEquals(listOf("Pill A"), out.bubble!!.names)
        Engine.bubbleClosed(app, out.bubble!!.nag, at("2026-11-18T20:21"))
        assertNull(out.bubble)
        assertTrue(Store.events(app).none { it.kind == "ignored" })
        Engine.restore(app, at("2026-11-18T20:22"))
        assertNull("kept across a process death", out.bubble)
        deliver(Chain.STEP)
        assertEquals(Step.FULL, out.posts.last().step)
        assertNull(out.bubble)
    }

    /** "Abrir quando eu desbloquear": waited for from the full screen until the next step, done once the nag screen
     * shows the dose. */
    @Test fun theUnlockWaitFollowsTheFullScreen() {
        Engine.schedule(app, NagFixtures.plan(pill8, unlock = true), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:20"))
        assertNull(out.unlock)
        deliver(Chain.STEP)
        assertEquals(Step.FULL, out.posts.last().step)
        assertTrue(out.posts.last().fullScreen)
        assertEquals(at("2026-11-18T21:05"), out.unlock)
        Engine.opened(app, listOf("a@2026-11-18T20:00"), at("2026-11-18T20:40"))
        assertNull(out.unlock)
    }

    /** Once per dose: her unlock opened the nag route, so the wait is done for that step even when the route never says
     * it shows the dose (the fixture's doesn't); the app's resume after it (a refresh) and a process death wait for
     * nothing again, and nothing is logged. Found on the emulator (engine-device, 2026-11-22): the resume armed it again
     * and her next unlock opened the route a second time. */
    @Test fun theUnlockOpensOnceEvenWhenTheRouteSaysNothing() {
        Engine.schedule(app, NagFixtures.plan(pill8, unlock = true), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:20"))
        deliver(Chain.STEP)
        assertEquals(Step.FULL, out.posts.last().step)
        assertEquals(at("2026-11-18T21:05"), out.unlock)
        val events = Store.events(app).size
        Engine.unlocked(app, at("2026-11-18T20:40"))
        assertNull(out.unlock)
        Engine.restore(app, at("2026-11-18T20:41"), front = true)
        assertNull("the resume after it waits for nothing", out.unlock)
        Engine.restore(app, at("2026-11-18T20:42"))
        assertNull("nor a process death", out.unlock)
        assertEquals("nothing logged", events, Store.events(app).size)
        deliver(Chain.STEP)
        assertEquals("the next step comes at its time", Step.REPEAT, out.posts.last().step)
    }

    /** With notifications off nothing can reach her: the step waits, silently, and counts nothing; once they are back,
     * the step comes at once. */
    @Test fun withNotificationsOffTheStepWaitsUntilTheyAreBack() {
        shadowOf(nm).setNotificationsEnabled(false)
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        deliver(Chain.STEP)
        assertTrue(out.posts.isEmpty())
        assertTrue(g20().muted)
        assertEquals("the lapse", at("2026-11-19T08:00"), armed(Chain.STEP)!!.triggerAtTime)
        assertEquals(0, g20().fires)
        shadowOf(nm).setNotificationsEnabled(true)
        Engine.restore(app, at("2026-11-18T20:30"))
        assertEquals(at("2026-11-18T20:00"), armed(Chain.STEP)!!.triggerAtTime)
        deliver(Chain.STEP)
        assertEquals(Step.NOTIFY, out.posts.single().step)
    }

    @Test fun theLowStockNoticeRidesOnTheFirstPostOfEachDay() {
        val tomorrow = NagFixtures.dose("a@2026-11-19T20:00", "2026-11-19T20:00", "2026-11-20T08:00", "Pill A")
        Engine.schedule(app, NagFixtures.plan(pill8, tomorrow, stock = true), at("2026-11-18T19:00"))
        val posted = { nm.activeNotifications.any { it.id == Shade.STOCK } }
        assertFalse(posted())
        deliver(Chain.STEP)
        assertTrue(posted())
        nm.cancel(Shade.STOCK)
        deliver(Chain.STEP)
        assertFalse("once a day", posted())
        Engine.fire(app, at("2026-11-19T20:00"))
        assertTrue(posted())
    }

    @Test fun aBootATimeChangeAndAZoneChangeSetTheAlarmsAgain() {
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        for (action in listOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { op -> alarms.cancel(op) } }
            assertNull(armed(Chain.STEP))
            NagSystemReceiver().onReceive(app, Intent(action))
            assertEquals(action, at("2026-11-18T20:00"), armed(Chain.STEP)!!.triggerAtTime)
            assertEquals(action, at("2026-11-18T20:00"), armed(Chain.BACKUP)!!.triggerAtTime)
        }
        // A trip to Tokyo: 20:00 there.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        NagSystemReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals(at("2026-11-18T20:00"), armed(Chain.STEP)!!.triggerAtTime)
        assertEquals(12 * 3_600_000L, LocalDateTime.parse("2026-11-18T20:00").atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli() - armed(Chain.STEP)!!.triggerAtTime)
    }

    @Test fun theStaleNoticeCountsFromTheLastTimeTheAppWasInFront() {
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        Engine.resolve(app, listOf("a@2026-11-18T20:00"), "taken", null, at("2026-11-18T19:30"))
        assertEquals("day 6, before the plan's seven days run out", at("2026-11-24T19:00"), armed(Chain.STEP)!!.triggerAtTime)
        Engine.restore(app, at("2026-11-23T09:00"), front = true)
        assertEquals(at("2026-11-29T09:00"), armed(Chain.STEP)!!.triggerAtTime)
        Engine.restore(app, at("2026-11-24T09:00"))
        assertEquals("a boot is no opening", at("2026-11-29T09:00"), armed(Chain.STEP)!!.triggerAtTime)
    }

    @Test fun aMalformedMascotPartNeverTakesThePlanDown() {
        val o = NagFixtures.plan(pill8)
        o.getJSONObject("mascot").put("parts", JSONArray()
            .put(JSONObject().put("t", "circle").put("cx", 10).put("cy", 10).put("r", 5).put("fill", "#FF7A59"))
            .put(JSONObject().put("t", "circle").put("r", 5).put("fill", "#FF7A59").put("tf", "rotate(10deg) scale(x)")))
        Engine.schedule(app, o, at("2026-11-18T19:00"))
        assertNotNull(Store.plan(app))
        assertEquals(2, Store.plan(app)!!.mascot(0)!!.parts.size)
        assertEquals(at("2026-11-18T20:00"), armed(Chain.STEP)!!.triggerAtTime)
    }

    /** A promise: nothing until its deadline (no step, no bubble, no repeat), then everything but the alarm (Glauco,
     * 2026-11-25): her sound, the bubble and the full screen. A "Já na hora" dose's deadline is code 0's, not the alarm
     * clock's, and rings nothing. */
    @Test fun aPromisesDeadlineIsArmedAndNeverRings() {
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        deliver(Chain.STEP)
        Engine.resolve(app, listOf("a@2026-11-18T20:00"), "promised", at("2026-11-18T20:20"), at("2026-11-18T20:01"))
        assertNull("nothing shows while promised", out.showing)
        assertEquals(at("2026-11-18T20:20"), armed(Chain.STEP)!!.triggerAtTime)
        assertTrue(Engine.settled(app, listOf("a@2026-11-18T20:00")))
        deliver(Chain.STEP)
        assertEquals(Step.DEADLINE, out.posts.last().step)
        assertEquals("You promised 20:20", out.posts.last().line)
        assertTrue(out.posts.last().let { it.sounding && it.bubble && it.fullScreen && !it.alarm })
        assertEquals("the bubble lives until the next step", Step.DEADLINE, out.bubble!!.step)
        NagFixtures.clear(app); out.reset()
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "onTime")
        Engine.schedule(app, NagFixtures.plan(d), at("2026-11-18T19:00"))
        deliver(Chain.ALARM)
        Engine.resolve(app, listOf("a@2026-11-18T20:00"), "promised", at("2026-11-18T20:30"), at("2026-11-18T20:00") + 20_000)
        assertNull("no alarm clock for the deadline", armed(Chain.ALARM))
        assertEquals(at("2026-11-18T20:30"), armed(Chain.STEP)!!.triggerAtTime)
        assertTrue("the app reads no alarm ahead", Rules.doses(Store.plan(app)!!, Store.state(app), true).single().isNull("alarmAt"))
        val rings = out.calls.count { it.startsWith("ring:") }
        deliver(Chain.STEP)
        val p = out.posts.last()
        assertEquals(Step.DEADLINE, p.step)
        assertTrue("her sound, not folded", p.sounding && !p.folded && !p.alarm)
        assertTrue("the bubble and the full screen", p.bubble && p.fullScreen)
        assertEquals("it rings nothing", rings, out.calls.count { it.startsWith("ring:") })
        assertFalse("it spends no Tela cheia", Store.state(app).doses.values.single().full)
        assertNull(armed(Chain.ALARM))
        run(at("2026-11-18T21:05"))
        assertEquals("Tela cheia still comes", listOf(Step.AGAIN, Step.FULL), out.posts.takeLast(2).map { it.step })
        assertTrue(out.posts.last().fullScreen)
    }

    // MARK: the alarm without its full screen (Glauco, 2026-11-25)

    private val alarm8 = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")

    /**
     * Without full-screen access (Android 14 here) and with "Aparecer por cima", the alarm brings the bubble with its
     * ring and the opening at her unlock, Bolinha and "Abrir quando eu desbloquear" off: on her S24 it rang, she unlocked
     * to stop it and couldn't find Pinkoo. The unlock that silences it doesn't end the wait; the opening does, once; both
     * last until the next step, across a process death.
     */
    @Test fun anAlarmWithoutItsFullScreenArmsTheBubbleAndTheUnlockInstead() {
        ShadowSettings.setCanDrawOverlays(true)
        assertFalse(FullScreen.allowed(app))
        Engine.schedule(app, NagFixtures.plan(alarm8, bubble = false, unlock = false), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:35"))
        assertNull("no bubble before it, Bolinha off", out.bubble)
        assertNull(out.unlock)
        run(at("2026-11-18T20:50"))
        val ring = out.posts.last()
        assertEquals(Step.ALARM, ring.step)
        assertTrue(ring.alarm && ring.bubble)
        assertTrue(g20().standIn)
        assertEquals(Step.ALARM, out.bubble!!.step)
        assertEquals("until the next step (the first repeat)", at("2026-11-18T21:20"), out.unlock)
        Engine.silenced(app, "unlock", at("2026-11-18T20:51"))
        assertNull(out.ringing)
        assertEquals("the unlock that silences it still opens the nag screen", at("2026-11-18T21:20"), out.unlock)
        Engine.restore(app, at("2026-11-18T20:51"))
        assertNotNull("kept across a process death", out.bubble)
        assertEquals(at("2026-11-18T21:20"), out.unlock)
        Engine.unlocked(app, at("2026-11-18T20:52"))
        assertNull("opened once", out.unlock)
        assertNotNull("the bubble stays for its step", out.bubble)
        Engine.restore(app, at("2026-11-18T20:53"), front = true)
        assertNull("the resume after it waits for nothing", out.unlock)
        run(at("2026-11-18T21:20"))
        assertEquals(Step.REPEAT, out.posts.last().step)
        assertNull("gone at the next step", out.bubble)
        assertFalse(g20().standIn)
    }

    /** "Agora não" while it rings ends both, as for De novo's bubble and Tela cheia's unlock. */
    @Test fun agoraNaoEndsTheAlarmsStandIns() {
        ShadowSettings.setCanDrawOverlays(true)
        Engine.schedule(app, NagFixtures.plan(alarm8, bubble = false), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:50"))
        assertNotNull(out.bubble)
        Engine.notNowKeys(app, listOf("a@2026-11-18T20:00"), at("2026-11-18T20:51"))
        assertNull(out.bubble)
        assertNull(out.unlock)
    }

    /** Without "Aparecer por cima" nothing changes: the alarm rings with its notification alone, and no wake is added. */
    @Test fun withoutTheOverlayAnAlarmWithoutItsFullScreenChangesNothing() {
        Engine.schedule(app, NagFixtures.plan(alarm8, bubble = false), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:50"))
        assertEquals(Step.ALARM, out.posts.last().step)
        assertFalse(out.posts.last().bubble)
        assertFalse(g20().standIn)
        assertNull(out.bubble)
        assertNull(out.unlock)
        assertEquals("the next wake is the ring's cap", at("2026-11-18T20:51"), armed(Chain.STEP)!!.triggerAtTime)
    }

    /** In front the app opens the nag screen itself: nothing stands in. */
    @Test fun inFrontAnAlarmWithoutItsFullScreenNeedsNoStandIn() {
        ShadowSettings.setCanDrawOverlays(true)
        Engine.schedule(app, NagFixtures.plan(alarm8, bubble = false), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:35"))
        Engine.appInFront = true
        run(at("2026-11-18T20:50"))
        assertEquals(Step.ALARM, out.posts.last().step)
        assertFalse(g20().standIn)
        assertNull(out.bubble)
        assertNull(out.unlock)
    }

    /** With full-screen access (Android 13 allows it by default) the alarm opens its own screen: nothing stands in. */
    @Config(sdk = [33])
    @Test fun withFullScreenAccessTheAlarmNeedsNoStandIn() {
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(FullScreen.allowed(app))
        Engine.schedule(app, NagFixtures.plan(alarm8, bubble = false), at("2026-11-18T19:00"))
        run(at("2026-11-18T20:50"))
        assertTrue(out.posts.last().let { it.step == Step.ALARM && it.fullScreen })
        assertFalse(g20().standIn)
        assertNull(out.bubble)
        assertNull(out.unlock)
    }

    @Test fun eventsWaitUntilTheAppAcknowledgesThemAndTheOldestGoBeyondAThousand() {
        Store.append(app, (1L..1005L).map { Event(it, "k", "fired", null, it, Step.NOTIFY) })
        val kept = Store.events(app)
        assertEquals(Store.EVENTS, kept.size)
        assertEquals(6L, kept.first().id)
        assertEquals(Step.NOTIFY, kept.first().step)
        Engine.ack(app, 505)
        assertEquals(500, Store.events(app).size)
        assertEquals(506L, Store.events(app).first().id)
    }

    @Test fun theEventsCarryTheirStepAndRiseFromAboveAnyIdBefore() {
        clock = at("2026-11-18T19:00")
        Engine.schedule(app, NagFixtures.plan(pill8), at("2026-11-18T19:00"))
        deliver(Chain.STEP)
        Engine.opened(app, listOf("a@2026-11-18T20:00"), at("2026-11-18T20:00") + 9_000)
        assertEquals(listOf("fired", "opened"), Store.events(app).map { it.kind })
        assertEquals(listOf("notify", "notify"), Store.events(app).map { it.json().getString("step") })
        val ids = Store.events(app).map { it.json().getString("id").toLong() }
        assertTrue(ids[0] > at("2026-11-18T19:00") && ids[1] == ids[0] + 1)
        assertTrue(JSONObject(Store.events(app).last().json().toString()).isNull("reason"))
    }

    /** Her phone updates in place mid-thread: the old engine's plan (v1) and state (v2) are read at the package's
     * replacement, before the app opens: the dose is put on a step and its next one comes at its time. */
    @Test fun anUpdateMidThreadGoesOnFromTheStepItReached() {
        val dir = File(app.createDeviceProtectedStorageContext().filesDir, "pinkoo.nag").apply { mkdirs() }
        val plan = JSONObject().put("v", 1).put("made", 0).put("zone", "America/Sao_Paulo")
            .put("ladder", JSONObject().put("firstMin", 10).put("repeatMin", 5).put("ringSec", 60))
            .put("rungs", JSONArray().put(JSONObject().put("ignores", 0).put("add", JSONArray(listOf("ring", "fullScreen"))))
                .put(JSONObject().put("ignores", 2).put("add", JSONArray(listOf("bubble")))))
            .put("volume", JSONArray(listOf(0.6, 0.8, 1.0))).put("doses", JSONArray().put(JSONObject(pill8.toString()).apply { remove("sound"); remove("alarm") }))
            .put("texts", NagFixtures.texts()).put("idle", JSONObject().put("afterDays", 6))
        File(dir, "plan.json").writeText(plan.toString())
        File(dir, "state.json").writeText(JSONObject().put("v", 2).put("nag", 1).put("event", 5).put("planAt", at("2026-11-18T19:00"))
            .put("groups", JSONArray().put(JSONObject().put("group", "2026-11-18T20:00").put("state", "pending").put("ignores", 1).put("nag", 1)
                .put("ignoredNag", 1).put("nextAt", at("2026-11-18T20:11")).put("lastFiredAt", at("2026-11-18T20:00"))))
            .put("doses", JSONArray().put(JSONObject().put("key", "a@2026-11-18T20:00").put("state", "open"))).toString())
        clock = at("2026-11-18T20:05")
        NagSystemReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(3, Store.state(app).v)
        assertEquals(Step.NOTIFY, g20().step)
        assertEquals(at("2026-11-18T20:11"), armed(Chain.STEP)!!.triggerAtTime)
        deliver(Chain.STEP)
        assertEquals(Step.AGAIN, out.posts.single().step)
        assertTrue("the bubble as she had it", out.posts.single().bubble)
        assertEquals("De pertinho's gap", at("2026-11-18T20:21"), armed(Chain.STEP)!!.triggerAtTime)
    }
}
