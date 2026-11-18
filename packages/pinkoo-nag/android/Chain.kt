// The engine: every way in (a plan, the alarm, an ignore, the nag screen, a resolution, a boot) changes the state by
// [Rules], then [Engine.refresh] brings the notifications, the service and the nuisances to it and sets the one alarm.
//
// One system alarm at a time, `setAlarmClock` under one request code, so each set replaces the last: the earliest of
// every group's time, next nag and promise deadline, every open dose's lapse and the stale notice. An alarm clock leaves
// Doze and is never deferred; it shows the alarm icon and the next alarm on the lock screen. Without the exact-alarm
// access (revoked: the system has already stopped the app and dropped its alarms) the chain falls back to an inexact
// `setAndAllowWhileIdle` until the access comes back. Only the alarm starts a ring: anything found due elsewhere sets
// the alarm in the past, which fires at once, under the alarm's exemption from background start limits.
package pinkoo.nag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

internal object Chain {
    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun exact(c: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    private fun fire(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 0,
        Intent(c, NagReceiver::class.java).setAction(NagReceiver.FIRE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** What the lock screen's next-alarm line opens: the app. */
    private fun show(c: Context): PendingIntent? = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
        PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun arm(c: Context, at: Long?) {
        if (at == null) return am(c).cancel(fire(c))
        if (exact(c)) am(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show(c)), fire(c))
        else am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire(c))
    }
}

internal object Engine {
    private fun now() = System.currentTimeMillis()

    /** What rings and waits now, or null with no plan yet. */
    @Synchronized fun live(c: Context): Live? = Store.plan(c)?.let { Live(it, Store.state(c)) }

    /** A plan from the app, sent at every resume and change: skipped when only its `made` changed. */
    @Synchronized fun schedule(c: Context, plan: JSONObject, now: Long = now()) {
        val st = Store.state(c)
        st.planAt = now
        val hash = JSONObject(plan.toString()).apply { remove("made") }.toString().hashCode()
        val parsed = if (hash != st.planHash || Store.planText(c) == null) {
            Store.savePlan(c, plan.toString())
            Plan.parse(plan).also { Rules.merge(it, st, now); st.planHash = hash }
        } else Store.plan(c) ?: return
        Shade.channels(c, parsed.texts)
        tick(c, parsed, st, now)
    }

    /** A boot, a time or zone change, the package replaced, the module made: what rang when the process died waits,
     * and everything is set again; a nag that fell due meanwhile fires at once. */
    @Synchronized fun restore(c: Context, now: Long = now()) {
        val plan = Store.plan(c) ?: return
        val st = Store.state(c)
        if (NagService.running == null) for (g in st.groups.values) if (g.state == "ringing") g.state = "pending"
        tick(c, plan, st, now)
    }

    private fun tick(c: Context, plan: Plan, st: State, now: Long) {
        val events = ArrayList<Event>()
        Rules.lapse(plan, st, now, events)
        commit(c, plan, st, events, now)
    }

    /** The alarm: lapses, the nags due, the low-stock notice on a day's first ring, the stale notice. */
    @Synchronized fun fire(c: Context, now: Long = now()) {
        val plan = Store.plan(c) ?: return Chain.arm(c, null)
        val st = Store.state(c)
        val events = ArrayList<Event>()
        Rules.lapse(plan, st, now, events)
        val due = Rules.due(plan, st, now)
        if (due.isNotEmpty()) {
            if (Shade.stoppable(c)) {
                Rules.fire(plan, st, due, now, events)
                val today = WallClock.day(now)
                if (plan.stock != null && st.stockDay != today) { st.stockDay = today; Shade.post(c, Shade.STOCK, Shade.stock(c, plan)) }
            } else Rules.mute(due)
        }
        if (Rules.stale(plan, st)?.let { it <= now } == true) { st.staleShown = st.planAt; Shade.post(c, Shade.STALE, Shade.stale(c, plan)) }
        commit(c, plan, st, events, now)
    }

    /** Nag [nag] ignored: by its "Not now", its ring timing out, the bubble closed, or its notification swiped. */
    @Synchronized fun ignore(c: Context, nag: Int, reason: String, now: Long = now()) {
        val plan = Store.plan(c) ?: return
        val st = Store.state(c)
        val events = ArrayList<Event>()
        Rules.ignore(plan, st, nag, reason, now, events)
        commit(c, plan, st, events, now)
    }

    /** The nag notification swiped away: the nag's ignore if it was not ignored yet. It comes back at once, silent,
     * while the returning notification is on; otherwise it stays away until the next nag. */
    @Synchronized fun dismissed(c: Context, nag: Int, now: Long = now()) {
        val plan = Store.plan(c) ?: return
        val st = Store.state(c)
        val events = ArrayList<Event>()
        Rules.ignore(plan, st, nag, "swipe", now, events)
        st.swiped = nag
        commit(c, plan, st, events, now)
    }

    @Synchronized fun opened(c: Context, keys: List<String>, now: Long = now()) {
        val plan = Store.plan(c) ?: return
        val st = Store.state(c)
        val events = ArrayList<Event>()
        Rules.opened(plan, st, keys, now, events)
        commit(c, plan, st, events, now)
    }

    @Synchronized fun resolve(c: Context, keys: List<String>, kind: String, until: Long?, now: Long = now()) {
        val plan = Store.plan(c) ?: return
        val st = Store.state(c)
        Rules.resolve(plan, st, keys, kind, until, now)
        commit(c, plan, st, emptyList(), now)
    }

    @Synchronized fun promiseDismissed(c: Context, until: Long) {
        val plan = Store.plan(c) ?: return
        val st = Store.state(c)
        st.promiseDismissed = until
        commit(c, plan, st, emptyList(), now())
    }

    @Synchronized fun ack(c: Context, through: Long) { Store.ack(c, through); NagModule.changed() }

    private fun commit(c: Context, plan: Plan, st: State, events: List<Event>, now: Long) {
        Store.save(c, st)
        Store.append(c, events)
        refresh(c, plan, st)
        NagModule.changed()
    }

    /** The notifications, the service and the nuisances brought to the state, and the alarm set. */
    fun refresh(c: Context, plan: Plan, st: State) {
        val live = Live(plan, st)
        val swipedAway = st.swiped != 0 && st.swiped == live.nag && live.ringing.isEmpty() && !Nuisances.returning(c, live)
        val served = NagService.sync(c, live)
        if (!served) {
            // Without the service (none needed, or its start refused) the nag notification is posted here; a ring that
            // could not start still shows loud, with its full-screen nag.
            Shade.post(c, Shade.NAG, if (live.groups.isEmpty() || swipedAway) null else Shade.nag(c, live, loud = live.ringing.isNotEmpty()))
            Nuisances.apply(c, live)
        }
        Shade.post(c, Shade.PROMISE, Shade.promise(c, live))
        Chain.arm(c, Rules.next(plan, st))
    }
}
