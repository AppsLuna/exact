package pinkoo.nag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The ladder's rules over a plan and a state, in São Paulo unless a test names its zone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RulesTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private fun at(local: String, zone: ZoneId = sp) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun plan(vararg d: JSONObject, first: Int = 20, repeat: Int = 15, made: Long = 0) = Plan.parse(NagFixtures.plan(*d, first = first, repeat = repeat, made = made))
    private val pillA = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A")

    @Test fun aPlanParsesItsLadderAndSortsItsRungs() {
        val o = NagFixtures.plan(pillA)
        val reversed = org.json.JSONArray((o.getJSONArray("rungs").length() - 1 downTo 0).map { o.getJSONArray("rungs").get(it) })
        val p = Plan.parse(o.put("rungs", reversed))
        assertEquals(listOf(0, 1, 2, 3, 4), p.rungs.map { it.ignores })
        assertEquals(setOf("ring", "fullScreen", "returning", "bubble"), p.words(2))
        assertEquals("the top rung adds nothing of its own", p.words(3), p.words(4))
        assertEquals(listOf("a@2026-11-18T08:00"), p.doses.map { it.key })
        assertEquals("one rung per ignore up to the top, 4, as the mascot's", listOf(0, 1, 2, 3, 4, 4, 4), (0..6).map(p::rung))
        assertEquals(20 * 60_000L, p.interval(1))
        assertEquals(15 * 60_000L, p.interval(2))
        assertEquals(1.0, p.volume(5), 0.0)
        assertEquals("b0", p.line(0, 0, 1))
        assertEquals("the top rung's lines for any above", "d4", p.line(7, 5, 2))
        assertEquals(6, p.idle.afterDays)
        assertEquals("the notice comes on day 6 when the plan names no day", 6, Plan.parse(NagFixtures.plan(pillA).apply { remove("idle") }).idle.afterDays)
        // The mascot may come as its JSON text (`json<any>`).
        assertEquals(1, Plan.parse(NagFixtures.plan(pillA).put("mascot", "{\"viewBox\":[0,0,1,1],\"parts\":[{\"t\":\"circle\",\"r\":1}]}")).mascot(0)?.parts?.size)
        // A dose with a malformed time is dropped, not guessed.
        assertEquals(1, Plan.parse(NagFixtures.plan(pillA, NagFixtures.dose("x", "08:00", "2026-11-18T20:00"))).doses.size)
    }

    @Test fun aWallClockTimeIsAnInstantInTheZoneThePhoneIsIn() {
        val ny = ZoneId.of("America/New_York")
        // The spring gap moves later by the gap: 02:30 does not exist on 2026-03-08, 03:30 EDT does.
        assertEquals(LocalDateTime.parse("2026-03-08T07:30").toInstant(ZoneOffset.UTC).toEpochMilli(), WallClock.epoch("2026-03-08T02:30", ny))
        // The autumn overlap takes the earlier offset: 01:30 EDT, not EST.
        assertEquals(LocalDateTime.parse("2026-11-01T05:30").toInstant(ZoneOffset.UTC).toEpochMilli(), WallClock.epoch("2026-11-01T01:30", ny))
        // A trip: the same local time, twelve hours apart.
        assertEquals(12 * 3_600_000L, WallClock.epoch("2026-11-18T08:00", sp) - WallClock.epoch("2026-11-18T08:00", ZoneId.of("Asia/Tokyo")))
    }

    @Test fun standardRingsAt0800IgnoredAt0801RingsAt0821ThenEvery15FromEachIgnore() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, at("2026-11-18T07:00"))
        assertEquals(at("2026-11-18T08:00"), Rules.next(p, st, sp))
        val ev = ArrayList<Event>()
        assertTrue(Rules.due(p, st, at("2026-11-18T07:59"), sp).isEmpty())
        val nag = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        assertTrue(Rules.ignore(p, st, nag, "timeout", at("2026-11-18T08:01"), ev))
        assertEquals(at("2026-11-18T08:21"), Rules.next(p, st, sp))
        val second = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:21"), sp), at("2026-11-18T08:21"), ev)
        Rules.ignore(p, st, second, "swipe", at("2026-11-18T08:22"), ev)
        assertEquals(at("2026-11-18T08:37"), Rules.next(p, st, sp))
        assertEquals(2, st.groups.values.single().ignores)
        assertEquals(listOf("fired", "ignored", "fired", "ignored"), ev.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L, 4L), ev.map { it.id })
    }

    @Test fun gentleInsistentAndCustomLadders() {
        for ((first, repeat) in listOf(30 to 20, 10 to 5, 5 to 120)) {
            val p = plan(pillA, first = first, repeat = repeat)
            val st = State()
            Rules.merge(p, st, 0)
            val ev = ArrayList<Event>()
            var t = at("2026-11-18T08:00")
            for (ignores in 1..3) {
                val nag = Rules.fire(p, st, Rules.due(p, st, t, sp), t, ev)
                Rules.ignore(p, st, nag, "notNow", t + 1_000, ev)
                val wait = (if (ignores == 1) first else repeat) * 60_000L
                assertEquals("$first/$repeat after $ignores", t + 1_000 + wait, Rules.next(p, st, sp))
                t += 1_000 + wait
            }
        }
    }

    @Test fun aNagCountsOneIgnoreWhateverIgnoresItAfterTheFirst() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        val nag = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        assertTrue(Rules.ignore(p, st, nag, "timeout", at("2026-11-18T08:01"), ev))
        assertFalse("a swipe after the timeout counts nothing", Rules.ignore(p, st, nag, "swipe", at("2026-11-18T08:02"), ev))
        assertFalse(Rules.ignore(p, st, nag, "bubble", at("2026-11-18T08:03"), ev))
        assertEquals(1, st.groups.values.single().ignores)
        assertEquals("the next nag is still counted from the first ignore", at("2026-11-18T08:21"), Rules.next(p, st, sp))
        assertEquals(listOf("timeout"), ev.filter { it.kind == "ignored" }.map { it.reason })
    }

    /**
     * The nag screen's "Not now" names its doses (`ignoreNags`): the nag of their group takes its one ignore, as the
     * app's `ignoreDose(group)` does; a group the same nag rang for but the screen does not show is left ringing.
     */
    @Test fun theNagScreensNotNowIgnoresItsOwnGroupOncePerNag() {
        val other = NagFixtures.dose("b@2026-11-18T07:59", "2026-11-18T07:59", "2026-11-18T20:00", "Pill B")
        val p = plan(pillA, other)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        val nag = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        val a = st.groups.getValue("2026-11-18T08:00")
        val b = st.groups.getValue("2026-11-18T07:59")
        assertTrue(Rules.ignore(p, st, listOf(pillA.getString("key")), "notNow", at("2026-11-18T08:00") + 30_000, ev))
        assertEquals(1, a.ignores)
        assertEquals("pending", a.state)
        assertEquals("the next nag counts from the ignore", at("2026-11-18T08:20") + 30_000, a.nextAt)
        assertEquals("the other group rings on", "ringing", b.state)
        assertEquals(0, b.ignores)
        assertFalse("once per nag", Rules.ignore(p, st, listOf(pillA.getString("key")), "notNow", at("2026-11-18T08:00") + 40_000, ev))
        assertFalse("a key the plan does not name counts nothing", Rules.ignore(p, st, listOf("x@2026-11-18T08:00"), "notNow", at("2026-11-18T08:00") + 45_000, ev))
        assertEquals(at("2026-11-18T08:20") + 30_000, a.nextAt)
        // The shade's "Not now" for the same nag then counts only for the group not yet ignored.
        assertTrue(Rules.ignore(p, st, nag, "notNow", at("2026-11-18T08:00") + 50_000, ev))
        assertEquals(1, a.ignores)
        assertEquals(1, b.ignores)
        assertEquals(listOf("a@2026-11-18T08:00" to "notNow", "b@2026-11-18T07:59" to "notNow"),
            ev.filter { it.kind == "ignored" }.map { it.key to it.reason })
    }

    @Test fun openingTheNagSilencesItWithoutClimbing() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        val nag = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        Rules.opened(p, st, listOf(pillA.getString("key")), at("2026-11-18T08:00") + 20_000, ev)
        val g = st.groups.values.single()
        assertEquals("pending", g.state)
        assertEquals(0, g.ignores)
        assertEquals("opened", ev.last().kind)
        assertEquals("the next nag counts from the opening, as the app's ladder does", at("2026-11-18T08:20") + 20_000, Rules.next(p, st, sp))
        // Left without resolving, the swipe that follows is the nag's ignore.
        assertTrue(Rules.ignore(p, st, nag, "swipe", at("2026-11-18T08:05"), ev))
        assertEquals(at("2026-11-18T08:25"), Rules.next(p, st, sp))
    }

    @Test fun aRingEndsAtItsRingTimeAsTheIgnoreOfWhatStillRingsAlone() {
        val other = NagFixtures.dose("b@2026-11-18T07:59", "2026-11-18T07:59", "2026-11-18T20:00", "Pill B")
        val p = plan(pillA, other)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        assertEquals("the alarm is the ring's end", at("2026-11-18T08:01"), Rules.next(p, st, sp))
        // One of the two groups the nag rang for was opened: only the other one rings out.
        Rules.opened(p, st, listOf("b@2026-11-18T07:59"), at("2026-11-18T08:00") + 10_000, ev)
        Rules.ringOut(p, st, at("2026-11-18T08:00") + 59_999, ev)
        assertEquals(0, st.groups.getValue("2026-11-18T08:00").ignores)
        Rules.ringOut(p, st, at("2026-11-18T08:03"), ev)
        assertEquals(1, st.groups.getValue("2026-11-18T08:00").ignores)
        assertEquals(0, st.groups.getValue("2026-11-18T07:59").ignores)
        assertEquals(at("2026-11-18T08:21"), st.groups.getValue("2026-11-18T08:00").nextAt)
        assertEquals("timeout", ev.last().reason)
        assertEquals(at("2026-11-18T08:01"), ev.last().at)
        Rules.ringOut(p, st, at("2026-11-18T08:04"), ev)
        assertEquals("once", 1, st.groups.getValue("2026-11-18T08:00").ignores)
    }

    @Test fun theStaleNoticeOfAHugeIdleSpanIsStillAhead() {
        val o = NagFixtures.plan(pillA)
        o.getJSONObject("idle").put("afterDays", 1_500_000)
        val st = State(planAt = at("2026-11-18T07:00"))
        assertTrue(Rules.stale(Plan.parse(o), st)!! > st.planAt)
    }

    @Test fun aPromiseMovesTheNextNagToItsDeadlineCappedAtTheMiss() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        Rules.resolve(p, st, listOf(pillA.getString("key")), "promised", at("2026-11-18T08:20"), at("2026-11-18T08:01"), sp)
        assertEquals("promised", st.groups.values.single().state)
        assertEquals(at("2026-11-18T08:20"), Rules.next(p, st, sp))
        assertTrue(Rules.due(p, st, at("2026-11-18T08:19"), sp).isEmpty())
        assertEquals(1, Rules.due(p, st, at("2026-11-18T08:20"), sp).size)
        // Promised again: the last counts; never past the miss.
        Rules.resolve(p, st, listOf(pillA.getString("key")), "promised", at("2026-11-18T23:00"), at("2026-11-18T08:05"), sp)
        assertEquals(at("2026-11-18T20:00"), st.groups.values.single().until)
    }

    @Test fun aDoseLapsesAtItsMissAndItsGroupEnds() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        Rules.lapse(p, st, at("2026-11-18T19:59"), ev, sp)
        assertEquals("open", st.doses.values.single().state)
        Rules.lapse(p, st, at("2026-11-18T20:00"), ev, sp)
        assertEquals("lapsed", st.doses.values.single().state)
        assertEquals("done", st.groups.values.single().state)
        assertEquals("lapsed", ev.last().kind)
        assertNull(Rules.next(p, st, sp))
    }

    @Test fun theAlarmIsTheEarliestOfTimesNextNagsDeadlinesAndLapses() {
        val noon = NagFixtures.dose("b@2026-11-18T12:00", "2026-11-18T12:00", "2026-11-18T13:00")
        val p = plan(pillA, noon)
        val st = State()
        Rules.merge(p, st, 0)
        assertEquals(at("2026-11-18T08:00"), Rules.next(p, st, sp))
        val ev = ArrayList<Event>()
        val nag = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        Rules.ignore(p, st, nag, "timeout", at("2026-11-18T08:01"), ev)
        assertEquals(at("2026-11-18T08:21"), Rules.next(p, st, sp))
        st.groups.getValue("2026-11-18T08:00").nextAt = at("2026-11-18T12:30")
        assertEquals(at("2026-11-18T12:00"), Rules.next(p, st, sp))
        st.planAt = at("2026-11-12T07:00")
        assertEquals("the stale notice, six days after the last plan", at("2026-11-18T07:00"), Rules.next(p, st, sp))
        st.staleShown = st.planAt
        assertEquals(at("2026-11-18T12:00"), Rules.next(p, st, sp))
    }

    @Test fun aNewPlanKeepsTheLadderDropsWhatItNoLongerNamesAndReopensAnUndo() {
        val p = plan(pillA, made = at("2026-11-18T07:00"))
        val st = State()
        Rules.merge(p, st, at("2026-11-18T07:00"))
        val ev = ArrayList<Event>()
        val nag = Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        Rules.ignore(p, st, nag, "timeout", at("2026-11-18T08:01"), ev)
        Rules.merge(plan(pillA, made = at("2026-11-18T08:05")), st, at("2026-11-18T08:05"))
        assertEquals(1, st.groups.values.single().ignores)
        Rules.resolve(p, st, listOf(pillA.getString("key")), "taken", null, at("2026-11-18T08:10"), sp)
        assertEquals("done", st.groups.values.single().state)
        // A plan made before the resolution leaves it resolved; one made after it reopens the dose (an undo).
        Rules.merge(plan(pillA, made = at("2026-11-18T08:09")), st, at("2026-11-18T08:11"))
        assertEquals("resolved", st.doses.values.single().state)
        Rules.merge(plan(pillA, made = at("2026-11-18T08:12")), st, at("2026-11-18T08:12"))
        assertEquals("open", st.doses.values.single().state)
        assertEquals("upcoming", st.groups.values.single().state)
        assertEquals("its time has passed: it nags at once, a rung up", 1, Rules.due(p, st, at("2026-11-18T08:12"), sp).single().ignores)
        // Removing the medication drops its open dose.
        Rules.merge(plan(made = at("2026-11-18T08:13")), st, at("2026-11-18T08:13"))
        assertTrue(st.groups.isEmpty() && st.doses.isEmpty())
    }

    /**
     * The one rule the nag screen shares (`src/app/plan.ts` nagRung and nagLineIndex): the rung is the group's ignores
     * up to the top, 4, and its line is `lines[rung][(seed + ignores) mod count]`, the seed being the group's first open
     * dose's. No other counter moves it: another group's nags leave it as it is.
     */
    @Test fun aNagsLineIsItsFirstOpenDosesSeedPlusItsIgnoresOnItsRung() {
        val a = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T23:00", "Pill A", seed = 4_294_967_295)
        val b = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T23:00", "Pill B", seed = 1)
        val noon = NagFixtures.dose("c@2026-11-18T07:30", "2026-11-18T07:30", "2026-11-18T23:00", "Pill C", seed = 9)
        val p = plan(a, b, noon)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        val g = st.groups.getValue("2026-11-18T08:00")
        var t = at("2026-11-18T08:00")
        for (ignores in 0..6) {
            assertEquals(ignores, g.ignores)
            val rung = minOf(4, ignores)
            val lines = NagFixtures.lines[rung]
            assertEquals("ignores $ignores", lines[((4_294_967_295L + ignores) % lines.size).toInt()], Rules.line(p, st, g))
            // The other group rings in between: the global nag number moves, the line does not.
            Rules.fire(p, st, listOf(st.groups.getValue("2026-11-18T07:30")), t, ev)
            assertEquals(lines[((4_294_967_295L + ignores) % lines.size).toInt()], Rules.line(p, st, g))
            val nag = Rules.fire(p, st, listOf(g), t, ev)
            Rules.ignore(p, st, nag, "notNow", t + 1_000, ev)
            t = g.nextAt!!
        }
        // Its first dose resolved, the seed is the next open one's.
        Rules.resolve(p, st, listOf(a.getString("key")), "taken", null, t, sp)
        assertEquals(NagFixtures.lines[4][((1L + 7) % 4).toInt()], Rules.line(p, st, g))
    }

    /** Decision 5: a new pace keeps the next nag already set; it applies from the one after. */
    @Test fun aNewPaceAppliesFromTheNagAfterTheNext() {
        val st = State()
        Rules.merge(plan(pillA), st, 0)
        val ev = ArrayList<Event>()
        val nag = Rules.fire(plan(pillA), st, Rules.due(plan(pillA), st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        Rules.ignore(plan(pillA), st, nag, "timeout", at("2026-11-18T08:01"), ev)
        val insistent = plan(pillA, first = 10, repeat = 5, made = at("2026-11-18T08:05"))
        Rules.merge(insistent, st, at("2026-11-18T08:05"))
        assertEquals("the nag already set stays", at("2026-11-18T08:21"), Rules.next(insistent, st, sp))
        val second = Rules.fire(insistent, st, Rules.due(insistent, st, at("2026-11-18T08:21"), sp), at("2026-11-18T08:21"), ev)
        Rules.ignore(insistent, st, second, "swipe", at("2026-11-18T08:22"), ev)
        assertEquals("then the new pace's", at("2026-11-18T08:27"), Rules.next(insistent, st, sp))
    }

    /** Decision 4: a promise never resets the ladder. Its deadline's nag rings at the rung she had, and an ignore of it
     * climbs from there. */
    @Test fun aPromisesDeadlineNagsAtTheSameRung() {
        val p = plan(pillA)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        var t = at("2026-11-18T08:00")
        repeat(3) { val nag = Rules.fire(p, st, Rules.due(p, st, t, sp), t, ev); Rules.ignore(p, st, nag, "notNow", t + 1_000, ev); t = Rules.next(p, st, sp)!! }
        val g = st.groups.values.single()
        assertEquals(3, Rules.rung(p, g))
        Rules.resolve(p, st, listOf(pillA.getString("key")), "promised", t + 20 * 60_000L, t, sp)
        assertEquals("promised", g.state)
        assertEquals(3, Rules.rung(p, g))
        assertEquals("promised", Rules.doses(p, st).single().getString("state"))
        assertEquals(3, Rules.doses(p, st).single().getInt("rung"))
        val deadline = t + 20 * 60_000L
        assertTrue(Rules.due(p, st, deadline - 1, sp).isEmpty())
        val nag = Rules.fire(p, st, Rules.due(p, st, deadline, sp), deadline, ev)
        assertEquals("ringing", g.state)
        assertEquals("the same rung", 3, Rules.rung(p, g))
        Rules.ignore(p, st, nag, "timeout", deadline + 60_000, ev)
        assertEquals(4, Rules.rung(p, g))
    }

    @Test fun dosesAtTheSameTimeAreOneNagAndOneResolvedLeavesTheOther() {
        val pillB = NagFixtures.dose("b@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill B")
        val p = plan(pillA, pillB)
        val st = State()
        Rules.merge(p, st, 0)
        val ev = ArrayList<Event>()
        Rules.fire(p, st, Rules.due(p, st, at("2026-11-18T08:00"), sp), at("2026-11-18T08:00"), ev)
        assertEquals(1, st.groups.size)
        assertEquals(2, ev.count { it.kind == "fired" })
        Rules.resolve(p, st, listOf(pillA.getString("key")), "taken", null, at("2026-11-18T08:01"), sp)
        assertEquals("ringing", st.groups.values.single().state)
        assertEquals(listOf("resolved", "ringing"), Rules.doses(p, st).map { it.getString("state") })
    }
}
