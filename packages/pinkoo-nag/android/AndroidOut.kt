// What Android shows and plays of the core's decisions: the [Out] the engine calls ([Rules] and [Engine] decide; this
// only shows). The reminder notification is [Shade]'s, the full screen [FullScreen]'s, the bubble [Bubble]'s, the alarm
// the ring service's ([NagService]) and the unlock wait [Unlock]'s. Every call is idempotent: the core calls them at
// every refresh with the state as it is, so each compares with what shows and does only what changed.
//
// What the surface hears and the core must know goes back through [Told], the one place that names the core's entry
// points: the alarm stopped by her (the stop contract), and the bubble sent to the nest. The notification's "Agora não"
// and its dismissal are the core's own broadcasts (`NagReceiver.NOT_NOW`, `DISMISS`).
package pinkoo.nag

import android.content.Context
import android.content.Intent

internal class AndroidOut(private val c: Context, given: Plan? = null) : Out {
    companion object {
        /** The alarm's stop: "Parar", and the alarm notification's delete intent. A broadcast to `NagReceiver` (its
         * `STOP`, the same action), so it runs on the locked phone. The test's is [Rehearsal.STOP]. */
        const val STOP = "pinkoo.nag.STOP"

        /** The last reminder posted or refreshed: whose route the unlock opens. In memory; the core's refresh at a restore
         * sets it again. */
        @Volatile internal var last: Post? = null

        /**
         * The stop contract ([NagService]): the alarm of [nag] stopped by [why] (`stop` for "Parar", `swipe`, `unlock`,
         * `power`, `cap`). It goes silent at once, never starts again for that nag, and the core hears of it
         * ([Told.stopped]), which ends the ring in its state (the cap as the alarm's `timeout`) and refreshes the
         * reminder. The test's own stop ends the test and records nothing.
         */
        fun stopped(c: Context, nag: Int, why: String, test: Boolean) {
            if (test) return Rehearsal.stop(c.applicationContext, why)
            NagService.silence(c.applicationContext)
            runCatching { Told.stopped(c.applicationContext, nag, why) }.onFailure { android.util.Log.e("pinkoo.nag", "stopped", it) }
        }

        /** A [STOP] broadcast, as `NagReceiver` may hand it. */
        fun stopped(c: Context, intent: Intent) =
            stopped(c, intent.getIntExtra("nag", 0), intent.getStringExtra("why") ?: "stop", intent.action == Rehearsal.STOP)
    }

    /** The plan the core decided on (`Engine.out` makes one of these per change): read once, when a call needs it. */
    private val plan: Plan by lazy { given ?: Store.plan(c) ?: Plan.parse(org.json.JSONObject()) }

    private fun now() = Engine.clock()

    override fun post(p: Post) {
        last = p
        Shade.post(c, plan, p)
    }

    override fun refresh(p: Post) {
        last = p
        Shade.refresh(c, plan, p)
    }

    override fun clear(nag: Int) = Shade.clear(c)

    /** The alarm for [p]: her sound (the dose's, the default preset when it can't sound) at the plan's alarm volume, in
     * the ring service, up to its cap. Once per nag: a ring she stopped or that rang out never starts again. It wakes
     * the screen itself when the post it rings with carried the full-screen intent ([Shade.build]). */
    override fun ring(p: Post) {
        val now = NagService.ring
        if (now != null && !now.test && now.nag == p.nag) return
        if (NagService.rang == p.nag && now?.nag != p.nag) return
        NagService.words = plan.texts.service
        // A test's sample gives way to the real alarm.
        if (now?.test == true) Rehearsal.stop(c, "alarm")
        val wakes = last?.let { it.nag == p.nag && it.alarm && it.fullScreen && !it.front } == true && FullScreen.allowed(c)
        NagService.ring = NagService.Companion.Ring(p.nag, p.channel, plan.alarmVolume, NagService.RING_MS, test = false, wakes = wakes)
        NagService.sync(c)
    }

    override fun stopRing(why: String) {
        val now = NagService.ring ?: return
        if (now.test) return
        NagService.silence(c)
    }

    /** The bubble for [p] (its window added first, then the service holding the process), or gone with null. The test's
     * bubble keeps the window meanwhile; its end brings this one back. */
    override fun bubble(p: Post?) {
        if (Rehearsal.bubbling) return
        if (p == null || !Bubble.allowed(c)) { if (Bubble.shown) Bubble.hide(); NagService.sync(c); return }
        val lead = p.groups.firstOrNull() ?: return
        val plan = plan
        Bubble.show(c, Bubble.Face(p.nag, p.line, plan.texts.bubble, plan.made * 16 + p.rung, { MascotDrawable.of(plan, p.rung) },
            tapped = { ctx -> runCatching { ctx.startActivity(Shade.opening(ctx, plan, lead, "bubble")) } },
            nested = { ctx -> runCatching { Told.nested(ctx.applicationContext, p.nag) } }))
        NagService.words = plan.texts.service
        NagService.sync(c)
    }

    override fun waitUnlock(until: Long?) {
        val plan = plan
        Unlock.wait(c, until, now(), opened = { ctx -> Told.unlocked(ctx.applicationContext) }) {
            last?.groups?.firstOrNull()?.let { Shade.opening(c, plan, it, "unlock") }
        }
        NagService.words = plan.texts.service
        NagService.sync(c)
    }

    override fun confirm(nag: Int, text: String) = Shade.confirm(c, nag, text)
}

/**
 * What the surface tells the core: the one place that names the core's entry points, which the merge maps if the core
 * names them otherwise. Each is a function the surface's tests replace, so they never depend on the core's rules.
 */
internal object Told {
    /** The alarm of [nag] stopped by her (`Engine.silenced`: the unlock, the power key) or by its 60 s cap
     * (`Engine.ringOut`: the alarm's `timeout`); the core refreshes. "Parar" goes to the core's own receiver
     * (`NagReceiver.STOP`, `Engine.silenced`), which asks the surface to stop through [Out.stopRing]. */
    @Volatile var stopped: (Context, Int, String) -> Unit = DEFAULT_STOPPED

    /** The bubble of [nag] dragged into the nest: it stays away for its step, across a process death; no ignore. */
    @Volatile var nested: (Context, Int) -> Unit = DEFAULT_NESTED

    /** Her first unlock after the full screen opened the nag route: the wait is done for that step, across a refresh
     * and a process death (once per dose, item 12), as when the nag screen shows the dose. */
    @Volatile var unlocked: (Context) -> Unit = DEFAULT_UNLOCKED

    private val DEFAULT_STOPPED: (Context, Int, String) -> Unit get() = { c, _, why ->
        if (why == "cap") Engine.ringOut(c, Engine.clock()) else Engine.silenced(c, why)
    }
    private val DEFAULT_NESTED: (Context, Int) -> Unit get() = { c, nag -> Engine.bubbleClosed(c, nag) }
    private val DEFAULT_UNLOCKED: (Context) -> Unit get() = { c -> Engine.unlocked(c) }

    /** The core's own entry points again (after a test). */
    fun reset() { stopped = DEFAULT_STOPPED; nested = DEFAULT_NESTED; unlocked = DEFAULT_UNLOCKED }
}
