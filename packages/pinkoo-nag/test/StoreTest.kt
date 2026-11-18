package pinkoo.nag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

/** What the engine keeps, when its state file breaks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class StoreTest : EngineCase() {
    private val pillB = NagFixtures.dose("b@2026-11-18T12:00", "2026-11-18T12:00", "2026-11-18T20:00", "Pill B")
    private fun breakState() = File(File(app.createDeviceProtectedStorageContext().filesDir, "pinkoo.nag"), "state.json").writeText("{\"groups\": [")
    private fun ids() = Store.events(app).map { it.id }

    /**
     * The app records each event once by its id (`nag_log`, `ON CONFLICT(id) DO NOTHING`): ids must never repeat, not
     * even when the state that counts them cannot be read. A state made anew starts above every id it could have
     * handed out: the wall clock in milliseconds, and any id still waiting in the events file.
     */
    @Test fun eventIdsNeverRepeatWhenTheStateFileBreaks() {
        Engine.schedule(app, NagFixtures.plan(pillA, pillB), at("2026-11-18T07:00"))
        Engine.fire(app, at("2026-11-18T08:00"))
        Engine.ignore(app, group().nag, "notNow", at("2026-11-18T08:00") + 5_000)
        val before = ids()
        assertEquals(2, before.size)
        assertTrue("one after the other", before[1] == before[0] + 1)
        Engine.ack(app, before.last())
        breakState()
        clock = at("2026-11-18T08:30")
        Engine.restore(app, clock)
        clock = at("2026-11-18T12:00")
        Engine.fire(app, clock)
        val after = ids()
        assertEquals(listOf("fired"), Store.events(app).map { it.kind })
        assertTrue("$after above $before", after.single() > before.last())
        // An id still waiting to be read counts too, even with the wall clock set back meanwhile.
        val high = after.single() + 5_000_000_000_000
        Engine.ack(app, after.single())
        Store.append(app, listOf(Event(high, "k", "fired", null, 0)))
        breakState()
        clock = at("2026-11-17T12:00")
        Engine.restore(app, clock)
        Engine.fire(app, at("2026-11-18T08:00"))
        val last = ids()
        assertEquals(high, last.first())
        assertTrue("$last", last.size == 2 && last[1] > high)
    }

    /**
     * The state lost with its file, the ladder goes on from the plan kept: the doses still more than two hours ahead
     * nag on time (none could have been confirmed yet, which opens two hours before), while the ones before, which
     * she may have confirmed, wait for the app's next plan rather than nag for a dose already taken.
     */
    @Test fun aBrokenStateKeepsNaggingOnlyTheDosesStillAhead() {
        Engine.schedule(app, NagFixtures.plan(pillA, pillB), at("2026-11-18T07:00"))
        Engine.resolve(app, listOf(pillA.getString("key")), "taken", null, at("2026-11-18T07:30"))
        breakState()
        clock = at("2026-11-18T07:40")
        NagSystemReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertEquals("pill B's time, not pill A's again", at("2026-11-18T12:00"), alarms.nextAlarmClock.triggerTime)
        Engine.fire(app, at("2026-11-18T08:00"))
        assertEquals(null, nag())
        Engine.fire(app, at("2026-11-18T12:00"))
        assertEquals("ringing", Store.state(app).groups.getValue("2026-11-18T12:00").state)
        // The app's next plan takes over: a dose it names again opens.
        Engine.schedule(app, NagFixtures.plan(pillB, made = at("2026-11-18T12:01")), at("2026-11-18T12:01"))
        assertEquals(listOf("b@2026-11-18T12:00"), Store.state(app).doses.keys.toList())
    }
}
