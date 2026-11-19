// The engine: every way in (a plan, the alarm, an ignore, the nag screen, a resolution, a boot) changes the state by
// [Rules], then [Engine.refresh] brings the notifications, the service and the nuisances to it and sets the one alarm.
//
// One system alarm at a time, `setAlarmClock` under one request code, so each set replaces the last: the earliest of
// every group's time, next nag and promise deadline, every open dose's lapse and the stale notice. An alarm clock leaves
// Doze and is never deferred; it shows the alarm icon and the next alarm on the lock screen. Without the exact-alarm
// access (never granted, Android 14 and later's default, or revoked: the system has then already stopped the app and
// dropped its alarms) the chain falls back to an inexact `setAndAllowWhileIdle` until the access comes back; such an
// alarm gives no start of the service from the background (measured on Android 16), so its nag shows silent, and the
// alarm at its ring's end still counts its ignore. Only the alarm starts a ring: anything found due elsewhere sets the
// alarm in the past, which fires at once, under the alarm's exemption from background start limits. A loud nag no
// service plays sounds itself instead, on the ring channel (decision 5).
//
// The alarm is set first, before the store and the notifications: whatever fails after it, the chain goes on.
package pinkoo.nag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
    /** The wall clock every way in reads; tests set it. */
    @Volatile var clock: () -> Long = System::currentTimeMillis
    private fun now() = clock()

    /** The app's activity is resumed: the module reads it when it is made and hears each resume and pause. In front,
     * the bubble and the unlock stay away, and a loud nag rings on the nag screen with no heads-up over it ([fire]). */
    @Volatile var appInFront = false

    /** What rings and waits now, or null with no plan yet. */
    @Synchronized fun live(c: Context): Live? = Store.plan(c)?.let { Live(it, Store.state(c)) }

    /** The plan and the state every way in starts from; a state lost with its file goes on from the plan
     * ([Rules.recover]). Null with no plan. */
    private fun load(c: Context, now: Long): Pair<Plan, State>? {
        val plan = Store.plan(c) ?: return null
        val st = Store.state(c)
        if (st.fresh && st.groups.isEmpty()) Rules.recover(plan, st, now)
        return plan to st
    }

    /** Whether nothing of [keys]' nags is left to do now: each of their groups resolved or promised. */
    @Synchronized fun settled(c: Context, keys: List<String>): Boolean {
        val plan = Store.plan(c) ?: return true
        val st = Store.state(c)
        return plan.doses.filter { it.key in keys }.map { it.group }.distinct()
            .all { st.groups[it]?.state.let { s -> s == null || s == "done" || s == "promised" } }
    }

    /** A plan from the app, sent at every resume and change: skipped when only its `made` changed. One that cannot be
     * read is refused, and the last one kept: it is read before it is stored. */
    @Synchronized fun schedule(c: Context, plan: JSONObject, now: Long = now()): Boolean {
        val st = Store.state(c)
        val hash = JSONObject(plan.toString()).apply { remove("made") }.toString().hashCode()
        val parsed = (if (hash == st.planHash) Store.plan(c) else null) ?: run {
            val read = runCatching { Plan.parse(plan) }.onFailure { Log.e(TAG, "plan refused", it) }.getOrNull() ?: return false
            Store.savePlan(c, plan.toString())
            read.also { Rules.merge(it, st, now); st.planHash = hash }
        }
        st.planAt = now
        guard("channels") { Shade.channels(c, parsed.texts) }
        tick(c, parsed, st, now)
        return true
    }

    /** A boot, a time or zone change, the package replaced, the module made, the app back in front: everything is set
     * again. A ring the process lost ends at its ring's end as its ignore, or rings on until then; a nag that fell due
     * meanwhile fires at once. The app in [front] counts as a plan sent for the stale notice: the app builds its plan
     * at every resume and sends it only when it changed, so an unchanged one already covers the days ahead. */
    @Synchronized fun restore(c: Context, now: Long = now(), front: Boolean = false) {
        val (plan, st) = load(c, now) ?: return
        if (front) st.planAt = maxOf(st.planAt, now)
        tick(c, plan, st, now)
    }

    private fun tick(c: Context, plan: Plan, st: State, now: Long) {
        val events = ArrayList<Event>()
        Rules.lapse(plan, st, now, events)
        Rules.ringOut(plan, st, now, events)
        if (Shade.stoppable(c)) Rules.unmute(plan, st, now)
        commit(c, plan, st, events, now)
    }

    /** The service's timer: its ring has lasted the ring time, [at] its end (the wall clock may have moved meanwhile). */
    @Synchronized fun ringOut(c: Context, at: Long, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.ringOut(plan, st, maxOf(now, at), events)
        commit(c, plan, st, events, now)
    }

    /**
     * The alarm: lapses, the nags due, the low-stock notice on a day's first ring, the stale notice.
     *
     * Decision 17: a nag that rings while the app is in front is quiet to its end ([State.quiet]): no heads-up and no
     * full-screen intent, which over the app would only cover the nag screen; the ring plays as always, and the module
     * opens the nag route itself ([NagModule.rang]), as the full-screen intent does over the lock screen. A nag that
     * rings with the app in the background is loud as it always was, whatever happens after.
     */
    @Synchronized fun fire(c: Context, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return Chain.arm(c, null)
        val events = ArrayList<Event>()
        Rules.lapse(plan, st, now, events)
        Rules.ringOut(plan, st, now, events)
        val stoppable = Shade.stoppable(c)
        if (stoppable) Rules.unmute(plan, st, now)
        val due = Rules.due(plan, st, now)
        val front = appInFront
        if (due.isNotEmpty()) {
            if (stoppable) {
                Rules.fire(plan, st, due, now, events)
                if (front) st.quiet = st.nag
                val today = WallClock.day(now)
                if (plan.stock != null && st.stockDay != today) { st.stockDay = today; guard("stock") { Shade.post(c, Shade.STOCK, Shade.stock(c, plan)) } }
            } else Rules.mute(due)
        }
        if (Rules.stale(plan, st)?.let { it <= now } == true) { st.staleShown = st.planAt; guard("stale") { Shade.post(c, Shade.STALE, Shade.stale(c, plan)) } }
        commit(c, plan, st, events, now)
        if (front && stoppable && due.isNotEmpty()) NagModule.rang(due.map { it.group }.sorted())
    }

    /** Nag [nag] ignored: by its "Not now", its ring timing out, the bubble closed, or its notification swiped. */
    @Synchronized fun ignore(c: Context, nag: Int, reason: String, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.ignore(plan, st, nag, reason, now, events)
        commit(c, plan, st, events, now)
    }

    /**
     * "Not now" on the nag screen (`ignoreNags`) for [keys]: their nag's ignore if it was not ignored yet, counted
     * from now, as the shade's "Not now" counts it. A ring that ran out unheard before it (nothing timed it out yet)
     * was that ignore, at its end, as the app's catch-up counts it. Its ring stops; the notification stays, silent.
     */
    @Synchronized fun ignoreKeys(c: Context, keys: List<String>, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.ringOut(plan, st, now, events)
        Rules.ignore(plan, st, keys, "notNow", now, events)
        commit(c, plan, st, events, now)
    }

    /** The nag notification swiped away: the nag's ignore if it was not ignored yet. It comes back at once, silent,
     * while the returning notification is on; otherwise it stays away until the next nag. */
    @Synchronized fun dismissed(c: Context, nag: Int, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.ignore(plan, st, nag, "swipe", now, events)
        st.swiped = nag
        commit(c, plan, st, events, now)
    }

    @Synchronized fun opened(c: Context, keys: List<String>, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.opened(plan, st, keys, now, events)
        commit(c, plan, st, events, now)
    }

    @Synchronized fun resolve(c: Context, keys: List<String>, kind: String, until: Long?, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        Rules.resolve(plan, st, keys, kind, until, now)
        commit(c, plan, st, emptyList(), now)
    }

    /** The bubble dragged onto the cross in nag [nag]: that nag's ignore, if it was not ignored yet, and the bubble
     * stays closed until the next nag, across a process death (decision 5). */
    @Synchronized fun bubbleClosed(c: Context, nag: Int, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        st.bubbleClosed = nag
        Rules.ignore(plan, st, nag, "bubble", now, events)
        commit(c, plan, st, events, now)
    }

    @Synchronized fun promiseDismissed(c: Context, until: Long) {
        val (plan, st) = load(c, now()) ?: return
        st.promiseDismissed = until
        commit(c, plan, st, emptyList(), now())
    }

    /** Events the app recorded, dropped: the app hears of it only when some were, so acknowledging what it reads
     * never feeds itself. */
    @Synchronized fun ack(c: Context, through: Long) { if (Store.ack(c, through)) NagModule.changed() }

    private fun commit(c: Context, plan: Plan, st: State, events: List<Event>, now: Long) {
        guard("alarm") { Chain.arm(c, Rules.next(plan, st)) }
        guard("store") { Store.save(c, st); Store.append(c, events) }
        refresh(c, plan, st, arm = false)
        NagModule.changed()
    }

    /** The alarm set, and the notifications, the service and the nuisances brought to the state, each on its own: one
     * that fails leaves the others. */
    fun refresh(c: Context, plan: Plan, st: State, arm: Boolean = true) {
        if (arm) guard("alarm") { Chain.arm(c, Rules.next(plan, st)) }
        val live = Live(plan, st)
        guard("nag") {
            val swipedAway = st.swiped != 0 && st.swiped == live.nag && live.ringing.isEmpty() && !Nuisances.returning(c, live)
            val served = NagService.sync(c, live)
            if (!served) {
                // Without the service (none needed, or its start refused) the nag notification is posted here. A ring no
                // service could start (no exact-alarm access gives no start from the background) sounds itself on the
                // ring channel, with its full-screen nag, and ends at its ring's end.
                val loud = live.ringing.isNotEmpty()
                if (live.groups.isEmpty() || swipedAway) Shade.cancelNag(c)
                else Shade.nag(c, live, loud, sounding = loud) { id, n -> Shade.notify(c, id, n) }
                Nuisances.apply(c, live)
            }
        }
        guard("promise") { Shade.promise(c, live) }
    }

    private const val TAG = "pinkoo.nag"

    private inline fun guard(what: String, f: () -> Unit) {
        try { f() } catch (e: Exception) { Log.e(TAG, what, e) }
    }
}
