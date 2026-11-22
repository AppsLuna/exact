package pinkoo.nag

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
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The gentle reminder's rules over a plan and a state, in São Paulo unless a test names its zone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RulesTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private fun at(local: String, zone: ZoneId = sp) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun local(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(sp).toLocalDateTime().toString()
    private fun plan(vararg d: JSONObject, made: Long = 0, bubble: Boolean = true, fullScreen: Boolean = true, unlock: Boolean = false, repeats: Int = 3) =
        Plan.parse(NagFixtures.plan(*d, made = made, bubble = bubble, fullScreen = fullScreen, unlock = unlock, repeats = repeats))
    private val pillA = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A")
    private fun key(d: JSONObject) = d.getString("key")

    /** Every fire of [p] from [from] to [to] with nobody answering, as `time step` lines, by the alarms [Rules.next]
     * sets (exact access unless [exact] is false). */
    private fun drive(p: Plan, st: State, from: Long, to: Long, exact: Boolean = true, posts: MutableList<Post>? = null): List<String> {
        val fired = ArrayList<String>()
        var t = from
        repeat(200) {
            val arm = Rules.next(p, st, t, exact, sp)
            val wake = listOfNotNull(arm.step, arm.alarm).minOrNull() ?: return fired
            if (wake > to) return fired
            t = maxOf(t, wake)
            val ev = ArrayList<Event>()
            Rules.lapse(p, st, t, ev, sp)
            Rules.ringOut(p, st, t, ev)
            val fires = Rules.fire(p, st, Rules.due(p, st, t, exact, true, sp), t, ev, exact)
            if (fires.isNotEmpty()) {
                val post = Rules.post(p, st, st.nag, fires, false, t, sp)
                posts?.add(post)
                fired += "${local(t).substring(11)} ${Rules.word(post.step)}" + (if (post.alarm) " alarm" else "") + (if (post.fullScreen) " full" else "")
            } else if (ev.isEmpty()) t += 1
        }
        error("no end: $fired")
    }

    // MARK: the plan

    @Test fun aPlanParsesItsPaceStepsAlarmAndEachDosesSoundAndAlarm() {
        val d = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A", sound = "kalimba", alarm = "onTime")
        val p = Plan.parse(NagFixtures.plan(d, NagFixtures.dose("b", "2026-11-18T08:00", "2026-11-18T20:00").apply { remove("sound"); remove("alarm") },
            first = 10, then = 5, repeatFirst = 20, repeatEvery = 25, repeats = 2, bubble = false, fullScreen = true, unlock = true, volume = 0.35))
        assertEquals(listOf(10, 5, 20, 25, 2), p.ladder.let { listOf(it.firstMin, it.thenMin, it.repeatFirstMin, it.repeatEveryMin, it.repeats) })
        assertEquals(listOf(false, true, true), listOf(p.steps.bubble, p.steps.fullScreen, p.steps.unlock))
        assertEquals(0.35, p.alarmVolume, 0.0)
        assertEquals(listOf("kalimba", Plan.DEFAULT_SOUND), p.doses.map { it.sound })
        assertEquals(listOf(Alarm.ON_TIME, Alarm.OFF), p.doses.map { it.alarm })
        assertEquals(listOf("kalimba", "sininho"), p.sounds())
        assertEquals("Reminder · Kalimba", p.texts.channels["kalimba"])
        assertEquals("Again at {time}.", p.texts.notNowRemind)
        assertEquals("stop", p.texts.s("stop"))
        assertEquals("b0", p.line(0, 0, 1))
        assertEquals("the top rung's lines for any above", "d4", p.line(7, 5, 2))
        assertEquals("the repeats are capped at 3", 3, Plan.parse(NagFixtures.plan(repeats = 6)).ladder.repeats)
        assertEquals("the alarm's volume is 20 % at least", 0.2, Plan.parse(NagFixtures.plan(volume = 0.0)).alarmVolume, 0.0)
        assertEquals(6, p.idle.afterDays)
        // The mascot may come as its JSON text (`json<any>`).
        assertEquals(1, Plan.parse(NagFixtures.plan(pillA).put("mascot", "{\"viewBox\":[0,0,1,1],\"parts\":[{\"t\":\"circle\",\"r\":1}]}")).mascot(0)?.parts?.size)
        // A dose with a malformed time is dropped, not guessed.
        assertEquals(1, Plan.parse(NagFixtures.plan(pillA, NagFixtures.dose("x", "08:00", "2026-11-18T20:00"))).doses.size)
    }

    /** A plan stored by the old engine (shape v1), read after the update until the app sends its first new one: her
     * pace mapped to the nearest, the bubble as she had it, Tela cheia on, no unlock, no alarm, Sininho. */
    @Test fun anOldPlanIsReadWithItsPaceMappedToTheNearest() {
        fun old(first: Int, repeat: Int, bubble: Boolean) = JSONObject().put("v", 1).put("made", 0)
            .put("ladder", JSONObject().put("firstMin", first).put("repeatMin", repeat).put("ringSec", 60))
            .put("rungs", JSONArray().put(JSONObject().put("ignores", 0).put("add", JSONArray(listOf("ring", "fullScreen"))))
                .put(JSONObject().put("ignores", 2).put("add", JSONArray(if (bubble) listOf("bubble") else emptyList()))))
            .put("volume", JSONArray(listOf(0.6, 0.8, 1.0))).put("doses", JSONArray().put(pillA.apply { })).put("texts", NagFixtures.texts())
        fun pace(p: Plan) = p.ladder.let { listOf(it.firstMin, it.thenMin, it.repeatFirstMin, it.repeatEveryMin, it.repeats) }
        assertEquals("Suave: Com calma", listOf(30, 30, 60, 60, 2), pace(Plan.parse(old(30, 20, true))))
        assertEquals("Padrão: No ritmo", listOf(20, 15, 30, 60, 3), pace(Plan.parse(old(20, 15, true))))
        assertEquals("Insistente: De pertinho", listOf(10, 10, 20, 20, 3), pace(Plan.parse(old(10, 5, true))))
        assertEquals("Meu jeito keeps her two intervals with No ritmo's repeats", listOf(45, 25, 30, 60, 3), pace(Plan.parse(old(45, 25, true))))
        val p = Plan.parse(old(20, 15, false))
        assertEquals(listOf(false, true, false), listOf(p.steps.bubble, p.steps.fullScreen, p.steps.unlock))
        assertTrue(Plan.parse(old(20, 15, true)).steps.bubble)
        assertEquals(Alarm.OFF, p.doses.single().alarm)
        assertEquals(Plan.DEFAULT_SOUND, p.doses.single().sound)
    }

    @Test fun aWallClockTimeIsAnInstantInTheZoneThePhoneIsIn() {
        val ny = ZoneId.of("America/New_York")
        // The spring gap moves later by the gap: 02:30 does not exist on 2026-03-08, 03:30 EDT does.
        assertEquals(LocalDateTime.parse("2026-03-08T07:30").toInstant(ZoneOffset.UTC).toEpochMilli(), WallClock.epoch("2026-03-08T02:30", ny))
        // The autumn overlap takes the earlier offset: 01:30 EDT, not EST.
        assertEquals(LocalDateTime.parse("2026-11-01T05:30").toInstant(ZoneOffset.UTC).toEpochMilli(), WallClock.epoch("2026-11-01T01:30", ny))
        // A trip: the same local time, twelve hours apart.
        assertEquals(12 * 3_600_000L, WallClock.epoch("2026-11-18T08:00", sp) - WallClock.epoch("2026-11-18T08:00", ZoneId.of("Asia/Tokyo")))
        assertEquals("always 24 h", "20:05", WallClock.hhmm(at("2026-11-18T20:05"), sp))
    }

    // MARK: the shared thread scenarios (L121)

    /**
     * Every case of the thread scenarios both layers run (`src/domain/test/thread-scenarios.json`, copied here; the
     * integrator checks the two with `cmp`): each fire with its step, rung, sound, bubble, full screen, alarm and
     * folding, nothing else before the miss, and what "Agora não" would say at each probe.
     */
    @Test fun everyThreadScenario() {
        val doc = JSONObject(scenarios())
        val paces = doc.getJSONObject("paces")
        val cases = doc.getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val pace = c.opt("pace").let { if (it is String) paces.getJSONObject(it) else it as JSONObject }
            val steps = c.getJSONObject("steps")
            val dose = c.getJSONObject("dose")
            val d = NagFixtures.dose("d@" + dose.getString("at"), dose.getString("at"), dose.getString("miss"), "Pill", seed = dose.getLong("seed"),
                alarm = c.getString("alarm"))
            val p = Plan.parse(NagFixtures.plan(d, first = pace.getInt("firstMin"), then = pace.getInt("thenMin"), repeatFirst = pace.getInt("repeatFirstMin"),
                repeatEvery = pace.getInt("repeatEveryMin"), repeats = pace.getInt("repeats"), bubble = steps.getBoolean("bubble"),
                fullScreen = steps.getBoolean("fullScreen"), unlock = steps.getBoolean("unlock")))
            val st = State()
            val start = at(dose.getString("at")) - 3_600_000L
            Rules.merge(p, st, start, sp)
            // Her answers and the probes, in time order: at one minute, fires first, then answers, then probes.
            data class Moment(val at: Long, val order: Int, val o: JSONObject)
            val moments = (0 until c.getJSONArray("events").length()).map { c.getJSONArray("events").getJSONObject(it) }.map { Moment(at(it.getString("at")), 0, it) } +
                (0 until c.getJSONArray("notNow").length()).map { c.getJSONArray("notNow").getJSONObject(it) }.map { Moment(at(it.getString("at")), 1, it) }
            val answers = ArrayList<String>()
            val wanted = ArrayList<String>()
            var t = start
            val g = { st.groups.values.single() }
            for (m in moments.sortedWith(compareBy({ it.at }, { it.order })) + Moment(at(dose.getString("miss")) + 60_000, 2, JSONObject())) {
                drive(p, st, t, m.at)
                t = m.at
                if (m.order == 2) break
                if (m.order == 0) {
                    val ev = ArrayList<Event>()
                    when (m.o.getString("do")) {
                        "notNow" -> Rules.notNow(p, st, listOf(g()), m.at, ev, sp)
                        "promise" -> Rules.resolve(p, st, listOf(d.getString("key")), "promised", at(m.o.getString("until")), m.at, sp)
                        "swipe" -> Rules.dismissed(p, st, Rules.live(p, st))
                        "open" -> Rules.opened(p, st, listOf(d.getString("key")), m.at, ev)
                        else -> error("${m.o}")
                    }
                } else {
                    val a = Rules.answer(p, st, g(), m.at, true, sp)
                    answers += "${local(m.at)} ${a.kind} ${a.at?.let(::local)}"
                    wanted += "${m.o.getString("at")} ${m.o.getString("kind")} ${if (m.o.isNull("next")) null else m.o.getString("next")}"
                }
            }
            assertEquals("$name: 'Agora não'", wanted, answers)
        }
    }

    /** The scenarios' fires, each case run once more from the start with no probe, compared field by field. */
    @Test fun everyThreadScenariosFires() {
        val doc = JSONObject(scenarios())
        val paces = doc.getJSONObject("paces")
        val cases = doc.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val pace = c.opt("pace").let { if (it is String) paces.getJSONObject(it) else it as JSONObject }
            val steps = c.getJSONObject("steps")
            val dose = c.getJSONObject("dose")
            val d = NagFixtures.dose("d@" + dose.getString("at"), dose.getString("at"), dose.getString("miss"), "Pill", seed = dose.getLong("seed"),
                alarm = c.getString("alarm"))
            val p = Plan.parse(NagFixtures.plan(d, first = pace.getInt("firstMin"), then = pace.getInt("thenMin"), repeatFirst = pace.getInt("repeatFirstMin"),
                repeatEvery = pace.getInt("repeatEveryMin"), repeats = pace.getInt("repeats"), bubble = steps.getBoolean("bubble"),
                fullScreen = steps.getBoolean("fullScreen"), unlock = steps.getBoolean("unlock")))
            val st = State()
            var t = at(dose.getString("at")) - 3_600_000L
            Rules.merge(p, st, t, sp)
            val events = (0 until c.getJSONArray("events").length()).map { c.getJSONArray("events").getJSONObject(it) }.sortedBy { at(it.getString("at")) }
            val got = ArrayList<String>()
            fun record(posts: List<Post>, times: List<Long>) {
                posts.zip(times).forEach { (post, time) ->
                    got += "${local(time)} ${Rules.word(post.step)} rung=${post.rung} sounding=${post.sounding} bubble=${post.bubble} " +
                        "fullScreen=${post.fullScreen} alarm=${post.alarm} folded=${post.folded}"
                }
            }
            for (e in events + JSONObject().put("at", dose.getString("miss")).put("do", "end")) {
                val until = at(e.getString("at"))
                val posts = ArrayList<Post>()
                val times = ArrayList<Long>()
                // Fire up to and at the event's minute (a fire at the same minute comes first).
                var now = t
                repeat(200) {
                    val arm = Rules.next(p, st, now, true, sp)
                    val wake = listOfNotNull(arm.step, arm.alarm).minOrNull()
                    if (wake == null || wake > until) return@repeat
                    now = maxOf(now, wake)
                    val ev = ArrayList<Event>()
                    Rules.lapse(p, st, now, ev, sp)
                    Rules.ringOut(p, st, now, ev)
                    val fires = Rules.fire(p, st, Rules.due(p, st, now, true, true, sp), now, ev, true)
                    if (fires.isNotEmpty()) { posts += Rules.post(p, st, st.nag, fires, false, now, sp); times += now }
                    else if (ev.isEmpty()) now += 1
                }
                record(posts, times)
                t = until
                val ev = ArrayList<Event>()
                when (e.getString("do")) {
                    "notNow" -> Rules.notNow(p, st, st.groups.values, until, ev, sp)
                    "promise" -> Rules.resolve(p, st, listOf(d.getString("key")), "promised", at(e.getString("until")), until, sp)
                    "swipe" -> Rules.dismissed(p, st, Rules.live(p, st))
                    "open" -> Rules.opened(p, st, listOf(d.getString("key")), until, ev)
                    "end" -> {}
                }
            }
            // After the miss: nothing more.
            assertTrue("$name: the dose lapsed", Rules.next(p, st, at(dose.getString("miss")) + 1, true, sp).let { it.alarm == null })
            val expect = c.getJSONArray("expect")
            val want = (0 until expect.length()).map { expect.getJSONObject(it) }.map { x ->
                "${x.getString("at")} ${x.getString("step")} rung=${x.getInt("rung")} sounding=${x.getBoolean("sounding")} bubble=${x.getBoolean("bubble")} " +
                    "fullScreen=${x.getBoolean("fullScreen")} alarm=${x.getBoolean("alarm")} folded=${x.getBoolean("folded")}"
            }
            assertEquals(name, want.joinToString("\n"), got.joinToString("\n"))
        }
    }

    private fun scenarios(): String {
        val names = listOf("../../../packages/pinkoo-nag/test/thread-scenarios.json", "packages/pinkoo-nag/test/thread-scenarios.json")
        val dir = File(System.getProperty("user.dir"))
        return names.map { File(dir, it) }.first { it.exists() }.readText()
    }

    // MARK: the thread

    @Test fun noRitmoWithEveryStepTheAlarmAfterAllAndTheCap() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        val p = plan(d)
        val st = State()
        Rules.merge(p, st, at("2026-11-18T19:00"))
        assertEquals(listOf("20:00 notify", "20:20 again", "20:35 full full", "20:50 alarm alarm", "21:20 repeat", "22:20 repeat", "23:20 repeat"),
            drive(p, st, at("2026-11-18T19:00"), at("2026-11-19T09:00")))
        val g = st.groups.values.single()
        assertEquals(3, g.repeats)
        assertEquals("after the last repeat nothing more", null, g.nextAt)
        assertEquals("7 reminders a dose at most", 7, g.fires)
    }

    /** "Agora não" moves the next step to one gap after the tap, never sooner than it was due, and never moves the
     * step: the same steps come, in the same order, with the same rungs. */
    @Test fun agoraNaoNeverMovesTheStep() {
        val p = plan(NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A"))
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T20:00"), true, zone = sp), at("2026-11-18T20:00"), ev, true)
        val g = st.groups.values.single()
        repeat(3) { Rules.notNow(p, st, listOf(g), at("2026-11-18T20:10") + it * 60_000L, ev, sp) }
        assertEquals(Step.NOTIFY, g.step)
        assertEquals(1, g.fires)
        assertEquals("the last tap's gap", at("2026-11-18T20:32"), g.nextAt)
        assertEquals(listOf("notNow", "notNow", "notNow"), ev.filter { it.kind == "ignored" }.map { it.reason })
        assertEquals(Step.NOTIFY, ev.last().step)
        assertTrue("the post stays away until the next step", g.dismissed)
        assertTrue(Rules.due(p, st, at("2026-11-18T20:31"), true, zone = sp).isEmpty())
        val fires = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T20:32"), true, zone = sp), at("2026-11-18T20:32"), ev, true)
        assertEquals(Step.AGAIN, fires.single().step)
        assertFalse(g.dismissed)
        assertEquals(1, Rules.rung(p, st, g))
    }

    /**
     * Two dose times open at once, at different steps: each post carries only what its own firing group reaches. Med
     * B's first notification, firing while A is past its full screen, brings no full screen, and A's full screen
     * doesn't come twice. The post names both, with B's sound (the firing group's first dose's).
     */
    @Test fun twoGroupsAtDifferentStepsCarryOnlyTheirOwn() {
        val a = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A", sound = "sininho")
        val b = NagFixtures.dose("b@2026-11-18T08:40", "2026-11-18T08:40", "2026-11-18T20:00", "Pill B", sound = "kalimba")
        val p = plan(a, b)
        val st = State()
        Rules.merge(p, st, 0)
        val posts = ArrayList<Post>()
        val fired = drive(p, st, at("2026-11-18T07:00"), at("2026-11-18T08:45"), posts = posts)
        assertEquals(listOf("08:00 notify", "08:20 again", "08:35 full full", "08:40 notify"), fired)
        val bFirst = posts.last()
        assertEquals(listOf("2026-11-18T08:40"), bFirst.groups)
        assertFalse("B's first notification brings no full screen", bFirst.fullScreen)
        assertTrue(bFirst.sounding)
        assertEquals("kalimba", bFirst.channel)
        assertEquals(listOf("Pill A", "Pill B"), bFirst.names)
        assertEquals("Time for Pill A, Pill B", bFirst.title)
        assertEquals(0, bFirst.rung)
        assertFalse("no bubble: A left De novo, B isn't in it", bFirst.bubble)
        // A's next (a repeat at 09:05) brings no second full screen, nor B's step.
        val more = ArrayList<Post>()
        assertEquals(listOf("09:00 again", "09:05 repeat"), drive(p, st, at("2026-11-18T08:45"), at("2026-11-18T09:06"), posts = more))
        assertFalse(more.any { it.fullScreen })
        assertTrue("B's De novo shows the bubble", more[0].bubble)
        assertEquals("sininho", more[1].channel)
        assertEquals(Step.REPEAT, more[1].step)
    }

    /** "Já na hora" at the dose time, "Só se passar de tudo" as its last step: a group rings at the earliest choice
     * among its doses, with its first dose's sound. */
    @Test fun aGroupRingsAtTheEarliestChoiceAmongItsDosesWithItsFirstDosesSound() {
        val a = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A", sound = "kalimba", alarm = "afterAll")
        val b = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill B", sound = "sininho", alarm = "onTime")
        val p = plan(a, b)
        val st = State()
        Rules.merge(p, st, 0)
        val g = st.groups.values.single()
        assertEquals(Alarm.ON_TIME, Rules.alarm(p, st, g))
        assertEquals(at("2026-11-18T08:00"), Rules.next(p, st, at("2026-11-18T07:00"), true, sp).alarm)
        assertEquals("code 0 leaves the dose time to the alarm: the lapse", at("2026-11-18T20:00"), Rules.next(p, st, at("2026-11-18T07:00"), true, sp).step)
        val posts = ArrayList<Post>()
        drive(p, st, at("2026-11-18T07:00"), at("2026-11-18T08:00"), posts = posts)
        assertEquals("kalimba", posts.single().channel)
        assertTrue(posts.single().alarm && posts.single().folded && !posts.single().sounding)
        // B taken: A's own choice, its last step.
        Rules.resolve(p, st, listOf(key(b)), "taken", null, at("2026-11-18T08:01"), sp)
        assertEquals(Alarm.AFTER_ALL, Rules.alarm(p, st, g))
        assertEquals(at("2026-11-18T08:50"), Rules.alarmAt(p, st, g, true, sp))
    }

    /**
     * Code 1 is armed on its own at the alarm's on-time time: a code-0 alarm Android deferred (De novo, due 20:20,
     * held 40 min) never delays it; the alarm rings at 20:50 and the steps it passed are skipped, and the late code-0
     * wake then finds nothing due. A step that really fires late moves it later.
     */
    @Test fun theAlarmComesOnTimeAfterADeferredStepAndTheStepsItPassedAreSkipped() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        val p = plan(d)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T20:00"), true, zone = sp), at("2026-11-18T20:00"), ev, true)
        val arm = Rules.next(p, st, at("2026-11-18T20:00"), true, sp)
        assertEquals(at("2026-11-18T20:20"), arm.step)
        assertEquals(at("2026-11-18T20:50"), arm.alarm)
        // The 20:20 wake never came; the alarm clock does.
        val due = Rules.due(p, st, at("2026-11-18T20:50"), true, zone = sp)
        assertEquals(Step.ALARM, due.single().step)
        val fires = Rules.fire(p, st, due, at("2026-11-18T20:50"), ev, true)
        assertTrue(fires.single().rings)
        assertFalse(fires.single().fullScreen)
        val g = st.groups.values.single()
        assertEquals(Step.ALARM, g.step)
        assertEquals("two reminders reached her", 2, g.fires)
        assertFalse("the full screen it passed never came", st.doses.values.single().full)
        assertEquals("the repeats count from the alarm", at("2026-11-18T21:20"), g.nextAt)
        // The deferred code-0 wake at 21:00: nothing due.
        Rules.ringOut(p, st, at("2026-11-18T21:00"), ev)
        assertTrue(Rules.due(p, st, at("2026-11-18T21:00"), true, zone = sp).isEmpty())
        // Another dose: De novo really fires late (20:25), and the alarm moves with it.
        val st2 = State()
        Rules.merge(p, st2, 0)
        Rules.fire(p, st2, Rules.due(p, st2, at("2026-11-18T20:00"), true, zone = sp), at("2026-11-18T20:00"), ev, true)
        Rules.fire(p, st2, Rules.due(p, st2, at("2026-11-18T20:25"), true, zone = sp), at("2026-11-18T20:25"), ev, true)
        assertEquals(at("2026-11-18T20:55"), Rules.alarmAt(p, st2, st2.groups.values.single(), true, sp))
    }

    /** Without exact access: no alarm clock, the alarm step comes as a notification step, sounding, and "Já na hora"
     * leaves the dose time's notification unfolded (item 14). The inexact backup's wake leaves an alarm to code 1. */
    @Test fun withoutExactAccessTheAlarmStepIsANotification() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        val p = plan(d, fullScreen = false)
        val st = State()
        Rules.merge(p, st, 0)
        val posts = ArrayList<Post>()
        assertEquals(listOf("20:00 notify", "20:20 again", "20:35 alarm", "21:05 repeat"), drive(p, st, 0, at("2026-11-18T21:06"), exact = false, posts = posts))
        assertNull(Rules.next(p, State().also { Rules.merge(p, it, 0) }, 0, false, sp).alarm)
        assertFalse(posts[2].alarm)
        assertTrue(posts[2].sounding)
        val onTime = plan(NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "onTime"))
        val st2 = State()
        Rules.merge(onTime, st2, 0)
        val first = ArrayList<Post>()
        drive(onTime, st2, 0, at("2026-11-18T20:00"), exact = false, posts = first)
        assertTrue(first.single().sounding && !first.single().folded && !first.single().alarm)
        // With exact access, the backup's wake at the alarm's time leaves it to the alarm clock.
        val st3 = State()
        Rules.merge(onTime, st3, 0)
        assertTrue(Rules.due(onTime, st3, at("2026-11-18T20:00"), true, alarms = false, zone = sp).isEmpty())
        assertEquals(1, Rules.due(onTime, st3, at("2026-11-18T20:00"), false, alarms = false, zone = sp).size)
    }

    /** The bubble lives one step: from De novo to the next step, or one gap after it came when none follows; sent to
     * the nest it stays away for the step and logs nothing. */
    @Test fun theBubbleLivesOneStep() {
        val p = plan(NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A"), fullScreen = false, repeats = 0)
        val st = State()
        Rules.merge(p, st, 0)
        drive(p, st, 0, at("2026-11-18T20:20"))
        val g = st.groups.values.single()
        assertEquals(Step.AGAIN, g.step)
        assertNotNull(Rules.bubble(p, st, at("2026-11-18T20:21"), sp))
        assertEquals("no step follows: one gap after it came", at("2026-11-18T20:35"), Rules.bubbleEnd(p, st, g, sp))
        assertEquals("its end wakes the engine", at("2026-11-18T20:35"), Rules.next(p, st, at("2026-11-18T20:21"), true, sp).step)
        assertNull(Rules.bubble(p, st, at("2026-11-18T20:35"), sp))
        assertEquals("an end that passed is no wake: the lapse is next", at("2026-11-19T08:00"), Rules.next(p, st, at("2026-11-18T20:36"), true, sp).step)
        // Nested: away for the step.
        val st2 = State()
        val p2 = plan(NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A"))
        Rules.merge(p2, st2, 0)
        drive(p2, st2, 0, at("2026-11-18T20:20"))
        val ev = ArrayList<Event>()
        Rules.nested(p2, st2, st2.groups.values.single().nag)
        assertNull(Rules.bubble(p2, st2, at("2026-11-18T20:21"), sp))
        assertTrue(ev.isEmpty())
        drive(p2, st2, at("2026-11-18T20:21"), at("2026-11-18T20:35"))
        assertEquals(Step.FULL, st2.groups.values.single().step)
        assertNull(Rules.bubble(p2, st2, at("2026-11-18T20:36"), sp))
    }

    /** "Abrir quando eu desbloquear": after the full screen, until the next step, at most 2 h, once per dose; done when
     * the nag screen opened, on "Agora não", or when she promised. */
    @Test fun theUnlockWaitsFromTheFullScreenUntilTheNextStepAtMostTwoHours() {
        val p = plan(NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A"), unlock = true)
        val st = State()
        Rules.merge(p, st, 0)
        drive(p, st, 0, at("2026-11-18T20:20"))
        assertNull("not before the full screen", Rules.unlock(p, st, at("2026-11-18T20:21"), true, sp))
        drive(p, st, at("2026-11-18T20:21"), at("2026-11-18T20:35"))
        assertEquals("until the next step", at("2026-11-18T21:05"), Rules.unlock(p, st, at("2026-11-18T20:36"), true, sp))
        val ev = ArrayList<Event>()
        Rules.opened(p, st, listOf("a@2026-11-18T20:00"), at("2026-11-18T20:40"), ev)
        assertNull("opened: done", Rules.unlock(p, st, at("2026-11-18T20:41"), true, sp))
        // Meu jeito with long gaps: at most 2 h.
        val slow = Plan.parse(NagFixtures.plan(NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A"),
            first = 5, then = 5, repeatFirst = 120, repeatEvery = 120, unlock = true))
        val st2 = State()
        Rules.merge(slow, st2, 0)
        drive(slow, st2, 0, at("2026-11-18T20:10"))
        assertEquals(at("2026-11-18T22:00").let { at("2026-11-18T20:10") + Rules.UNLOCK_MS }, Rules.unlock(slow, st2, at("2026-11-18T20:11"), true, sp))
        Rules.notNow(slow, st2, st2.groups.values, at("2026-11-18T20:12"), ev, sp)
        assertNull("'Agora não': done", Rules.unlock(slow, st2, at("2026-11-18T20:13"), true, sp))
    }

    /** A swipe is kept, not counted: the post stays away until the group's next step, and another group's post
     * doesn't name it meanwhile. */
    @Test fun aSwipeKeepsItsGroupAwayUntilItsNextStep() {
        val a = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A")
        val b = NagFixtures.dose("b@2026-11-18T08:10", "2026-11-18T08:10", "2026-11-18T20:00", "Pill B")
        val p = plan(a, b)
        val st = State()
        Rules.merge(p, st, 0)
        drive(p, st, 0, at("2026-11-18T08:00"))
        val nag = Rules.live(p, st)
        assertTrue(Rules.dismissed(p, st, nag))
        assertNull("nothing to refresh", Rules.refresh(p, st, at("2026-11-18T08:05"), sp))
        assertFalse("an old post's swipe changes nothing", Rules.dismissed(p, st, nag))
        val posts = ArrayList<Post>()
        drive(p, st, at("2026-11-18T08:05"), at("2026-11-18T08:10"), posts = posts)
        assertEquals("B's post doesn't bring A back", listOf("Pill B"), posts.single().names)
        assertEquals(listOf("Pill B"), Rules.refresh(p, st, at("2026-11-18T08:11"), sp)!!.names)
        drive(p, st, at("2026-11-18T08:11"), at("2026-11-18T08:20"), posts = posts)
        assertEquals("A's next step brings it back", listOf("Pill A", "Pill B"), posts.last().names)
        assertEquals(Step.AGAIN, posts.last().step)
        assertEquals(listOf("2026-11-18T08:00"), posts.last().groups)
    }

    // MARK: promises

    /**
     * "Several medications at the same time: one notification, one pinky promise per medication." A promise holds its
     * dose alone: the other keeps the group's thread, the promised one sits those reminders out and keeps its own count,
     * and comes back at its deadline, which is a fire for both and not one of the repeats.
     */
    @Test fun aPromiseIsOneDosesAndTheOtherKeepsTheThread() {
        val pillB = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill B")
        val a = key(pillA)
        val b = key(pillB)
        val p = plan(pillA, pillB)
        val st = State()
        Rules.merge(p, st, 0)
        val g = st.groups.values.single()
        drive(p, st, 0, at("2026-11-18T08:00"))
        Rules.resolve(p, st, listOf(a), "promised", at("2026-11-18T08:30"), at("2026-11-18T08:01"), sp)
        assertEquals("the group still reminds the other", "pending", g.state)
        assertEquals(listOf("promised", "pending"), Rules.doses(p, st, true, sp).map { it.getString("state") })
        assertEquals(at("2026-11-18T08:30"), Rules.doses(p, st, true, sp).first().getLong("nextAt"))
        assertEquals("deadline", Rules.doses(p, st, true, sp).first().getString("nextStep"))
        val posts = ArrayList<Post>()
        assertEquals(listOf("08:20 again"), drive(p, st, at("2026-11-18T08:01"), at("2026-11-18T08:29"), posts = posts))
        assertEquals("the other alone", listOf("Pill B"), posts.single().names)
        assertEquals(listOf(1, 2), Rules.doses(p, st, true, sp).map { it.getInt("fires") })
        // The deadline comes before the full screen (08:35): one post for both, and the thread goes on one gap later.
        assertEquals(listOf("08:30 deadline"), drive(p, st, at("2026-11-18T08:29"), at("2026-11-18T08:31"), posts = posts))
        assertEquals(listOf("Pill A", "Pill B"), posts.last().names)
        assertEquals("the rung is the most any dose it reminds had", 2, posts.last().rung)
        assertEquals("You promised 08:30", posts.last().line)
        assertEquals(Step.AGAIN, g.step)
        assertEquals(0, g.repeats)
        assertEquals("Tela cheia one gap after the deadline", at("2026-11-18T08:45"), g.nextAt)
        assertEquals(listOf(2, 3), Rules.doses(p, st, true, sp).map { it.getInt("fires") })
        // The other taken: the promised one is back at its own count.
        Rules.resolve(p, st, listOf(b), "taken", null, at("2026-11-18T08:31"), sp)
        assertEquals(1, Rules.rung(p, st, g))
    }

    /** Every open dose promised: the group waits for the earliest deadline, which brings back only its own dose. */
    @Test fun aGroupWithEveryOpenDosePromisedWaitsForTheEarliestDeadline() {
        val pillB = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill B")
        val p = plan(pillA, pillB)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        val g = st.groups.values.single()
        drive(p, st, 0, at("2026-11-18T08:00"))
        Rules.resolve(p, st, listOf(key(pillA)), "promised", at("2026-11-18T08:30"), at("2026-11-18T08:01"), sp)
        Rules.resolve(p, st, listOf(key(pillB)), "promised", at("2026-11-18T08:20"), at("2026-11-18T08:01"), sp)
        assertEquals("promised", g.state)
        assertEquals(at("2026-11-18T08:20"), g.until)
        assertFalse("nothing to move", Rules.notNow(p, st, listOf(g), at("2026-11-18T08:02"), ev, sp))
        assertEquals(at("2026-11-18T08:20"), Rules.next(p, st, at("2026-11-18T08:02"), true, sp).step)
        val posts = ArrayList<Post>()
        drive(p, st, at("2026-11-18T08:02"), at("2026-11-18T08:20"), posts = posts)
        assertEquals(listOf("Pill B"), posts.single().names)
        assertEquals(listOf("promised", "pending"), Rules.doses(p, st, true, sp).map { it.getString("state") })
        Rules.resolve(p, st, listOf(key(pillB)), "taken", null, at("2026-11-18T08:21"), sp)
        assertEquals("promised", g.state)
        assertEquals(at("2026-11-18T08:30"), Rules.next(p, st, at("2026-11-18T08:21"), true, sp).step)
    }

    @Test fun aPromiseNeverGoesPastTheMissAndTheLastCounts() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, 0)
        drive(p, st, 0, at("2026-11-18T08:00"))
        Rules.resolve(p, st, listOf(key(pillA)), "promised", at("2026-11-18T08:20"), at("2026-11-18T08:01"), sp)
        Rules.resolve(p, st, listOf(key(pillA)), "promised", at("2026-11-18T23:00"), at("2026-11-18T08:05"), sp)
        assertEquals(at("2026-11-18T20:00"), st.groups.values.single().until)
    }

    /** A plan carries the app's promises per dose (a lost state, a state from before promises were per dose). */
    @Test fun aPlansPromiseIsItsDosesAloneAndAnOldGroupPromiseIsEachDoses() {
        val pillB = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill B")
        val promisedA = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A", promiseUntil = at("2026-11-18T08:30"))
        val st = State()
        Rules.merge(plan(promisedA, pillB), st, at("2026-11-18T08:05"))
        assertEquals(at("2026-11-18T08:30"), st.doses.getValue(key(pillA)).until)
        assertNull(st.doses.getValue(key(pillB)).until)
        assertEquals("upcoming", st.groups.values.single().state)
        assertEquals("the other is reminded at once", 1, Rules.due(plan(promisedA, pillB), st, at("2026-11-18T08:05"), true, zone = sp).size)
        // A state from before promises were per dose (version 1), whose group held the promise: each open dose takes it.
        val written = JSONObject().put("v", 1).put("nag", 0).put("event", 0)
            .put("groups", JSONArray().put(JSONObject().put("group", "2026-11-18T08:00").put("state", "promised").put("until", at("2026-11-18T08:40"))))
            .put("doses", JSONArray().put(JSONObject().put("key", key(pillA)).put("state", "open")).put(JSONObject().put("key", key(pillB)).put("state", "open")))
        val old = State.from(JSONObject(written.toString()))
        assertEquals(1, old.v)
        Rules.upgrade(plan(pillA, pillB), old)
        assertEquals(State.VERSION, old.v)
        Rules.merge(plan(pillA, pillB, made = 1), old, at("2026-11-18T08:10"))
        assertEquals("promised", old.groups.values.single().state)
        assertEquals(listOf(at("2026-11-18T08:40"), at("2026-11-18T08:40")), old.doses.values.map { it.until })
    }

    // MARK: the plan and the state

    /**
     * An in-flight dose of the old engine (state version 2: a ladder of ignores) is put on a step: two nags and one
     * ignore are two reminders, De novo; the next one keeps its time; a ring in progress is over; its doses have had
     * their full screen; a swiped notification stays away for that step.
     */
    @Test fun anInFlightOldDoseIsPutOnAStep() {
        val p = plan(pillA)
        val written = JSONObject().put("v", 2).put("nag", 2).put("event", 3).put("swiped", 2)
            .put("groups", JSONArray().put(JSONObject().put("group", "2026-11-18T08:00").put("state", "ringing").put("ignores", 1)
                .put("nag", 2).put("ignoredNag", 1).put("nextAt", at("2026-11-18T08:37")).put("lastFiredAt", at("2026-11-18T08:21"))))
            .put("doses", JSONArray().put(JSONObject().put("key", key(pillA)).put("state", "open")))
        val st = State.from(JSONObject(written.toString()))
        Rules.upgrade(p, st)
        val g = st.groups.values.single()
        assertEquals(State.VERSION, st.v)
        assertEquals(Step.AGAIN, g.step)
        assertEquals(2, g.fires)
        assertEquals("pending", g.state)
        assertEquals(at("2026-11-18T08:37"), g.nextAt)
        assertTrue(st.doses.values.single().full)
        assertTrue(g.dismissed)
        // It goes on from there: Tela cheia at 08:37 brings no second full screen.
        val ev = ArrayList<Event>()
        val fires = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:37"), true, zone = sp), at("2026-11-18T08:37"), ev, true)
        assertEquals(Step.FULL, fires.single().step)
        assertFalse(fires.single().fullScreen)
        // Saved and read back, version 3 stays.
        val again = State.from(JSONObject(st.json().toString()))
        assertEquals(Step.FULL, again.groups.values.single().step)
        assertEquals(3, again.groups.values.single().fires)
        assertEquals(3, again.v)
    }

    @Test fun aNewPlanKeepsTheThreadDropsWhatItNoLongerNamesAndReopensAnUndo() {
        val p = plan(pillA, made = at("2026-11-18T07:00"))
        val st = State()
        Rules.merge(p, st, at("2026-11-18T07:00"))
        drive(p, st, at("2026-11-18T07:00"), at("2026-11-18T08:00"))
        // A new pace keeps the next step already set: it applies from the one after.
        val close = Plan.parse(NagFixtures.plan(pillA, made = at("2026-11-18T08:05"), first = 10, then = 10, repeatFirst = 20, repeatEvery = 20))
        Rules.merge(close, st, at("2026-11-18T08:05"))
        val g = st.groups.values.single()
        assertEquals(Step.NOTIFY, g.step)
        assertEquals("the step already set stays", at("2026-11-18T08:20"), Rules.next(close, st, at("2026-11-18T08:05"), true, sp).step)
        drive(close, st, at("2026-11-18T08:05"), at("2026-11-18T08:20"))
        assertEquals("then the new pace's", at("2026-11-18T08:30"), g.nextAt)
        Rules.resolve(close, st, listOf(key(pillA)), "taken", null, at("2026-11-18T08:21"), sp)
        assertEquals("done", g.state)
        // A plan made before the resolution leaves it resolved; one made after it reopens the dose (an undo), reminded
        // at once from where its thread was.
        Rules.merge(plan(pillA, made = at("2026-11-18T08:20")), st, at("2026-11-18T08:22"))
        assertEquals("resolved", st.doses.values.single().state)
        val undo = plan(pillA, made = at("2026-11-18T08:23"))
        Rules.merge(undo, st, at("2026-11-18T08:23"))
        assertEquals("open", st.doses.values.single().state)
        assertEquals("pending", g.state)
        assertEquals(Step.FULL, Rules.due(undo, st, at("2026-11-18T08:30"), true, zone = sp).single().step)
        // Removing the medication drops its open dose.
        Rules.merge(plan(made = at("2026-11-18T08:31")), st, at("2026-11-18T08:31"))
        assertTrue(st.groups.isEmpty() && st.doses.isEmpty())
    }

    @Test fun aDoseLapsesAtItsMissAndItsGroupEnds() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, 0)
        drive(p, st, 0, at("2026-11-18T08:00"))
        val ev = ArrayList<Event>()
        Rules.lapse(p, st, at("2026-11-18T19:59"), ev, sp)
        assertEquals("open", st.doses.values.single().state)
        Rules.lapse(p, st, at("2026-11-18T20:00"), ev, sp)
        assertEquals("lapsed", st.doses.values.single().state)
        assertEquals("done", st.groups.values.single().state)
        assertEquals("lapsed", ev.last().kind)
        val arm = Rules.next(p, st, at("2026-11-18T20:00"), true, sp)
        assertNull(arm.step)
        assertNull(arm.alarm)
    }

    @Test fun theStaleNoticeOfAHugeIdleSpanIsStillAheadAndNoneWithoutASchedule() {
        val o = NagFixtures.plan(pillA)
        o.getJSONObject("idle").put("afterDays", 1_500_000)
        val st = State(planAt = at("2026-11-18T07:00"))
        assertTrue(Rules.stale(Plan.parse(o), st)!! > st.planAt)
        val none = NagFixtures.plan()
        none.getJSONObject("idle").put("afterDays", 0)
        val p = Plan.parse(none)
        val st2 = State(planAt = at("2026-11-18T07:00"))
        assertNull(Rules.stale(p, st2))
        Rules.merge(p, st2, 0)
        assertNull("no alarm at all", Rules.next(p, st2, 0, true, sp).step)
    }

    @Test fun theNextAlarmIsTheEarliestOfStepsDeadlinesLapsesAndTheStaleNotice() {
        val noon = NagFixtures.dose("b@2026-11-18T12:00", "2026-11-18T12:00", "2026-11-18T13:00")
        val p = plan(pillA, noon)
        val st = State()
        Rules.merge(p, st, 0)
        assertEquals(at("2026-11-18T08:00"), Rules.next(p, st, 0, true, sp).step)
        drive(p, st, 0, at("2026-11-18T08:00"))
        assertEquals(at("2026-11-18T08:20"), Rules.next(p, st, at("2026-11-18T08:00"), true, sp).step)
        st.groups.getValue("2026-11-18T08:00").nextAt = at("2026-11-18T12:30")
        assertEquals(at("2026-11-18T12:00"), Rules.next(p, st, at("2026-11-18T08:00"), true, sp).step)
        st.planAt = at("2026-11-12T07:00")
        assertEquals("the stale notice, six days after the last plan", at("2026-11-18T07:00"), Rules.next(p, st, at("2026-11-18T08:00"), true, sp).step)
    }

    /** The one rule the nag screen shares: the rung is the reminders before the one showing, up to 4, and the line
     * `lines[rung][(seed + n) mod count]`, the seed its first open dose's. Another group's posts leave it as it is. */
    @Test fun aRemindersLineIsItsFirstOpenDosesSeedPlusTheRemindersBeforeIt() {
        val a = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-19T07:00", "Pill A", seed = 4_294_967_295)
        val b = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-19T07:00", "Pill B", seed = 1)
        val early = NagFixtures.dose("c@2026-11-18T07:31", "2026-11-18T07:31", "2026-11-19T07:00", "Pill C", seed = 9)
        val p = plan(a, b, early)
        val st = State()
        Rules.merge(p, st, 0)
        val posts = ArrayList<Post>()
        drive(p, st, 0, at("2026-11-19T06:00"), posts = posts)
        val mine = posts.filter { it.groups.contains("2026-11-18T08:00") && it.groups.size == 1 }
        assertEquals(6, mine.size)
        mine.forEachIndexed { n, post ->
            val rung = minOf(4, n)
            val lines = NagFixtures.lines[rung]
            assertEquals("reminder $n", rung, post.rung)
            assertEquals("reminder $n", lines[((4_294_967_295L + n) % lines.size).toInt()], post.line)
        }
        val g = st.groups.getValue("2026-11-18T08:00")
        assertEquals(mine.last().line, Rules.line(p, st, g, sp))
        // Its first dose resolved, the seed is the next open one's.
        Rules.resolve(p, st, listOf(key(a)), "taken", null, at("2026-11-19T06:00"), sp)
        assertEquals(NagFixtures.lines[4][((1L + 5) % 4).toInt()], Rules.line(p, st, g, sp))
    }

    /** What the app reads per dose (`NagDoseState`): its step, its own reminders, its rung, what comes next and when the
     * alarm rings. */
    @Test fun theStateTheAppReadsCarriesTheStep() {
        val d = NagFixtures.dose("a@2026-11-18T20:00", "2026-11-18T20:00", "2026-11-19T08:00", "Pill A", alarm = "afterAll")
        val p = plan(d)
        val st = State()
        Rules.merge(p, st, 0)
        val before = Rules.doses(p, st, true, sp).single()
        assertEquals("upcoming", before.getString("state"))
        assertTrue(before.isNull("step"))
        assertEquals(0, before.getInt("fires"))
        assertEquals("notify", before.getString("nextStep"))
        assertEquals(at("2026-11-18T20:00"), before.getLong("nextAt"))
        assertEquals(at("2026-11-18T20:50"), before.getLong("alarmAt"))
        drive(p, st, 0, at("2026-11-18T20:20"))
        val after = Rules.doses(p, st, true, sp).single()
        assertEquals("pending", after.getString("state"))
        assertEquals("again", after.getString("step"))
        assertEquals(2, after.getInt("fires"))
        assertEquals(1, after.getInt("rung"))
        assertEquals("full", after.getString("nextStep"))
        assertEquals(at("2026-11-18T20:35"), after.getLong("nextAt"))
        assertEquals(at("2026-11-18T20:50"), after.getLong("alarmAt"))
        assertEquals(at("2026-11-18T20:20"), after.getLong("lastFiredAt"))
        assertTrue("no alarm without exact access", Rules.doses(p, st, false, sp).single().isNull("alarmAt"))
        drive(p, st, at("2026-11-18T20:20"), at("2026-11-18T20:50"))
        assertEquals("ringing", Rules.doses(p, st, true, sp).single().getString("state"))
    }

    /**
     * A miss in a daylight-saving overlap: New York's fall-back night has two 01:00s. The app writes its offset
     * (`2026-11-01T01:00-05:00`, the second), which the engine keeps while the phone is in the plan's zone; elsewhere
     * (a trip) the local time, as without one.
     */
    @Test fun aMissInAFallBackOverlapIsTheInstantItsOffsetNames() {
        val ny = ZoneId.of("America/New_York")
        val second = java.time.Instant.parse("2026-11-01T06:00:00Z").toEpochMilli()
        val d = NagFixtures.dose("a@2026-10-31T02:00", "2026-10-31T02:00", "2026-11-01T01:00-05:00", "Pill A")
        val o = NagFixtures.plan(d).put("zone", "America/New_York")
        val p = Plan.parse(o)
        assertEquals(second, p.miss(p.doses.single(), ny))
        val st = State()
        Rules.merge(p, st, 0, ny)
        val ev = ArrayList<Event>()
        Rules.lapse(p, st, second - 30 * 60_000, ev, ny)
        assertEquals("open an hour after the first 01:00", "open", st.doses.values.single().state)
        Rules.lapse(p, st, second, ev, ny)
        assertEquals("lapsed", st.doses.values.single().state)
        // Without it, the earlier 01:00; in another zone, the local time there.
        val old = Plan.parse(NagFixtures.plan(NagFixtures.dose("a@2026-10-31T02:00", "2026-10-31T02:00", "2026-11-01T01:00")).put("zone", "America/New_York"))
        assertEquals(second - 60 * 60_000, old.miss(old.doses.single(), ny))
        assertEquals(LocalDateTime.parse("2026-11-01T01:00").atZone(sp).toInstant().toEpochMilli(), p.miss(p.doses.single(), sp))
    }
}
