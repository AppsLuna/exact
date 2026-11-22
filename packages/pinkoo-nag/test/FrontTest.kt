package pinkoo.nag

import android.content.Intent
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

/**
 * Decision 17: with Pinkoo in front no step shows Android's heads-up over it: the post is quiet to its end (the waiting
 * channel, no full-screen intent), even if she leaves or the process dies. Decision 7 and item 17: the engine opens
 * nothing in front; it tells the app what came and at which step (`nagState().front`, [Rules.front] and
 * [Rules.frontStep]): for a notification step the app shows the banner and plays her sound itself, for the full screen
 * and the alarm it opens the nag screen, the alarm ringing there. The front lasts until the nag screen shows one of its
 * doses, the dose is answered, promised or missed, or a post comes in the background; a post the nag screen already
 * shows has none.
 *
 * Item 20: with notifications off nothing can reach her, but a step due with Pinkoo in front still tells the app,
 * which opens the nag screen without a sound (`?via=silent`); its "Agora não" moves nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class FrontTest : CoreCase() {
    private val key = pillA.getString("key")

    /** What the app reads in front: the lead group's time and its step, or null. */
    private fun front(): Pair<String, Step?>? {
        val plan = Store.plan(app)!!
        val st = Store.state(app)
        return Rules.front(plan, st)?.let { it.group to Rules.frontStep(plan, st, it) }
    }

    private fun schedule(vararg doses: JSONObject, alarm: Boolean = false, unlock: Boolean = false) =
        Engine.schedule(app, NagFixtures.plan(*(if (doses.isEmpty()) arrayOf(if (alarm) NagFixtures.dose(key, "2026-11-18T08:00", "2026-11-18T20:00",
            "Pill A", alarm = "afterAll") else pillA) else doses), unlock = unlock), at("2026-11-18T07:00"))

    private fun fire(local: String) { clock = at(local); Engine.fire(app, clock) }

    @Test fun aStepInFrontIsQuietAndTellsTheAppItsStep() {
        Engine.appInFront = true
        schedule()
        assertNull(front())
        fire("2026-11-18T08:00")
        val p = out.posts.single()
        assertTrue("no heads-up", p.front)
        assertFalse("the app plays her sound itself", p.sounding)
        assertEquals("the post keeps its words", "Time for Pill A", p.title)
        assertEquals("2026-11-18T08:00" to Step.NOTIFY, front())
        // Quiet to its end: leaving the app and a refresh keep it so.
        Engine.appInFront = false
        Engine.restore(app, at("2026-11-18T08:05"))
        assertTrue(out.refreshes.last().front)
        assertEquals("still there for the app until the nag screen shows it", "2026-11-18T08:00" to Step.NOTIFY, front())
    }

    /** The full screen in front: no full-screen intent over the app, the app opens the nag screen, and the dose's full
     * screen is spent all the same. */
    @Test fun theFullScreenInFrontIsTheAppsAndIsSpent() {
        schedule()
        fire("2026-11-18T08:00")
        fire("2026-11-18T08:20")
        Engine.appInFront = true
        fire("2026-11-18T08:35")
        val p = out.posts.last()
        assertEquals(Step.FULL, p.step)
        assertFalse("no full-screen intent in front", p.fullScreen)
        assertTrue(p.front)
        assertEquals(Step.FULL, front()!!.second)
        assertTrue(Store.state(app).doses.values.single().full)
    }

    /** The alarm in front rings all the same (the app opens the nag screen, which it rings on). */
    @Test fun theAlarmInFrontRingsAndTellsTheApp() {
        schedule(alarm = true)
        fire("2026-11-18T08:00"); fire("2026-11-18T08:20"); fire("2026-11-18T08:35")
        Engine.appInFront = true
        clock = at("2026-11-18T08:50")
        Engine.fire(app, clock, Chain.ALARM)
        assertEquals(Step.ALARM, out.posts.last().step)
        assertTrue(out.posts.last().front)
        assertEquals("ring:${out.posts.last().nag}", out.calls.last { it.startsWith("ring") })
        assertEquals(Step.ALARM, front()!!.second)
        // Her first touch on the nag screen silences it and ends the front.
        Engine.screen = setOf(key)
        Engine.opened(app, listOf(key), at("2026-11-18T08:50") + 3_000)
        assertNull(front())
        assertTrue(out.calls.last().startsWith("stopRing"))
    }

    @Test fun inTheBackgroundAStepIsUnchangedAndHasNoFront() {
        schedule()
        fire("2026-11-18T08:00")
        assertFalse(out.posts.single().front)
        assertTrue(out.posts.single().sounding)
        assertNull(front())
    }

    /** "Ainda não" in Novo remédio opens the nag route itself and the route says what it shows: no front. */
    @Test fun aStepTheNagScreenAlreadyShowsHasNoFront() {
        Engine.appInFront = true
        Engine.screen = setOf(key)
        schedule()
        fire("2026-11-18T08:00")
        assertTrue(out.posts.single().front)
        assertNull(front())
    }

    @Test fun theNagScreenShowingTheDoseEndsTheFrontAndTheNextStepInFrontTellsAgain() {
        Engine.appInFront = true
        schedule()
        fire("2026-11-18T08:00")
        Engine.screen = setOf(key)
        Engine.opened(app, listOf(key), at("2026-11-18T08:01"))
        assertNull(front())
        Engine.screen = emptySet()
        fire("2026-11-18T08:20")
        assertEquals(Step.AGAIN, front()!!.second)
    }

    @Test fun anAnsweredOrMissedDoseEndsTheFrontAndABackgroundPostClearsIt() {
        Engine.appInFront = true
        schedule()
        fire("2026-11-18T08:00")
        Engine.resolve(app, listOf(key), "taken", null, at("2026-11-18T08:02"))
        assertNull(front())
        NagFixtures.clear(app)
        schedule()
        fire("2026-11-18T08:00")
        Engine.restore(app, at("2026-11-18T20:00"))
        assertNull("missed", front())
        NagFixtures.clear(app)
        schedule()
        fire("2026-11-18T08:00")
        Engine.appInFront = false
        fire("2026-11-18T08:20")
        assertNull("a post in the background clears it", front())
        assertFalse(out.posts.last().front)
    }

    /** A promise holds it (no front); its deadline, come in front, tells the app again, at the deadline's step. */
    @Test fun aPromiseDeadlineInFrontTellsTheAppAgain() {
        Engine.appInFront = true
        schedule()
        fire("2026-11-18T08:00")
        Engine.resolve(app, listOf(key), "promised", at("2026-11-18T08:15"), at("2026-11-18T08:01"))
        assertNull(front())
        fire("2026-11-18T08:15")
        assertEquals(Step.DEADLINE, front()!!.second)
    }

    /** Two groups in one post (a late wake): the front names the earliest. */
    @Test fun twoGroupsInOnePostLeadWithTheEarliest() {
        val early = NagFixtures.dose("e@2026-11-18T07:59", "2026-11-18T07:59", "2026-11-18T20:00", "Pill E")
        Engine.appInFront = true
        schedule(pillA, early)
        fire("2026-11-18T08:00")
        assertEquals("2026-11-18T07:59", front()!!.first)
        assertEquals(listOf("2026-11-18T07:59", "2026-11-18T08:00"), out.posts.single().groups)
    }

    /** Item 20: notifications off, a step due in front is told as a silent post that nothing can move; in the
     * background, nothing is told. Notifications back, the next step in front posts and replaces it. */
    @Test fun withNotificationsOffAStepDueInFrontIsToldSilently() {
        shadowOf(nm).setNotificationsEnabled(false)
        Engine.appInFront = true
        schedule()
        fire("2026-11-18T08:00")
        assertTrue(out.posts.isEmpty())
        val st = Store.state(app)
        assertEquals(st.front, st.silent)
        assertEquals(at("2026-11-18T08:00"), st.silentAt)
        assertEquals("2026-11-18T08:00" to Step.NOTIFY, front())
        Engine.notNowKeys(app, listOf(key), at("2026-11-18T08:01"))
        assertTrue("nothing could reach her again: nothing moves", Store.events(app).none { it.kind == "ignored" })
        shadowOf(nm).setNotificationsEnabled(true)
        Engine.restore(app, at("2026-11-18T08:05"))
        Engine.fire(app, at("2026-11-18T08:05"))
        assertEquals(Step.NOTIFY, out.posts.single().step)
        assertEquals(Store.state(app).nag, Store.state(app).front)
        assertFalse(Store.state(app).front == Store.state(app).silent)
        // In the background, a muted step tells nothing.
        NagFixtures.clear(app)
        shadowOf(nm).setNotificationsEnabled(false)
        Engine.appInFront = false
        schedule()
        fire("2026-11-18T08:00")
        assertNull(front())
    }

    /** Silenced with notifications off, then promised from the nag screen: once they are back, the deadline comes. */
    @Test fun aPromiseMadeWhileSilencedComesAtItsDeadlineOnceNotificationsAreBack() {
        shadowOf(nm).setNotificationsEnabled(false)
        Engine.appInFront = true
        schedule()
        fire("2026-11-18T08:00")
        Engine.resolve(app, listOf(key), "promised", at("2026-11-18T08:20"), at("2026-11-18T08:01"))
        shadowOf(nm).setNotificationsEnabled(true)
        Engine.restore(app, at("2026-11-18T08:02"), front = true)
        fire("2026-11-18T08:20")
        assertEquals(Step.DEADLINE, out.posts.single().step)
    }

    /** The notification's "Agora não" in front: the same move, and the front ends with the post gone. */
    @Test fun theNotificationsAgoraNaoEndsTheFrontWithThePost() {
        Engine.appInFront = true
        schedule()
        fire("2026-11-18T08:00")
        clock = at("2026-11-18T08:03")
        NagReceiver().onReceive(app, Intent(NagReceiver.NOT_NOW).putExtra("nag", out.posts.single().nag))
        assertNotNull(out.confirms.singleOrNull())
        assertEquals(at("2026-11-18T08:23"), Store.state(app).groups.values.single().nextAt)
        assertNull(front())
    }
}
