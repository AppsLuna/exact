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
        assertEquals(listOf(0, 1, 2, 3), p.rungs.map { it.ignores })
        assertEquals(setOf("ring", "fullScreen", "returning", "bubble"), p.words(2))
        assertEquals(listOf("a@2026-11-18T08:00"), p.doses.map { it.key })
        assertEquals(listOf(0, 1, 2, 3, 3), (0..4).map(p::rung))
        assertEquals(20 * 60_000L, p.interval(1))
        assertEquals(15 * 60_000L, p.interval(2))
        assertEquals(1.0, p.volume(5), 0.0)
        assertEquals("b0", p.line(0, 0, 1))
        assertEquals("the top rung's lines for any above", "a3", p.line(7, 5, 2))
        assertEquals(7, p.idle.afterDays)
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
        // Left without resolving, the swipe that follows is the nag's ignore.
        assertTrue(Rules.ignore(p, st, nag, "swipe", at("2026-11-18T08:05"), ev))
        assertEquals(at("2026-11-18T08:25"), Rules.next(p, st, sp))
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
        st.planAt = at("2026-11-11T07:00")
        assertEquals("the stale notice, a week after the last plan", at("2026-11-18T07:00"), Rules.next(p, st, sp))
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
