// The engine: every way in (a plan, an alarm, "Agora não", a swipe, the nag screen, a resolution, a boot) changes the
// state by [Rules], then [Engine.refresh] tells Android what to show and play ([Out], the seam in `Steps.kt`) and the
// alarms are set again.
//
// The alarms (`docs/architecture.md` › Nagging on the phone, `docs/exact.md` item 6), each under its own request code,
// so each set replaces the last of its kind:
// - code 0 ([Chain.STEP]): `setExactAndAllowWhileIdle` at the earliest of every step, repeat, promise deadline, lapse,
//   the ring's cap, the bubble's and the unlock's ends, and the stale notice. It fires on time in Doze for an app with
//   exact access and, unlike an alarm clock, shows no alarm icon and tells no other app her dose times. While Android
//   holds Pinkoo's exact while-idle alarms back (background restriction, the restricted standby bucket), it is an
//   alarm clock too, which nothing holds back.
// - code 1 ([Chain.ALARM]): `setAlarmClock` at the next alarm step she turned on, armed on its own at the time it has if
//   the steps before it come on time, so a code-0 alarm Android deferred never delays it; when it comes first, the steps
//   it passed are skipped ([Rules.alarmAt]). The one alarm Doze never defers, which may start the ring from the
//   background.
// - code 2 ([Chain.BACKUP]): an inexact `setAndAllowWhileIdle` at the earliest of both. Taking "Alarmes e lembretes"
//   away stops Pinkoo and cancels its exact alarms without telling it (Android broadcasts only a grant); the inexact one
//   survives, so the next step still comes, late. Every fire re-arms all three, so the backup never fires while the
//   exact ones work, and a fire that finds nothing due does nothing. Without exact access the backup alone, and the
//   alarm step comes as a notification (item 14).
//
// The alarms are set first, before the store and the notifications: whatever fails after them, the chain goes on.
package pinkoo.nag

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject

internal object Chain {
    /** The request codes: every step and event, the alarm she turned on, the inexact backup. */
    const val STEP = 0
    const val ALARM = 1
    const val BACKUP = 2

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun exact(c: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    /** Android holds Pinkoo's exact while-idle alarms back: background restriction ("Restrito"), or the restricted
     * standby bucket (from Android 13 one alarm a day for an app not exempt). Neither holds back an alarm clock. */
    fun restricted(c: Context): Boolean {
        val background = runCatching { c.getSystemService(ActivityManager::class.java).isBackgroundRestricted }.getOrDefault(false)
        val bucket = runCatching { c.getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED }
            .getOrDefault(false)
        return background || bucket
    }

    private fun fire(c: Context, code: Int): PendingIntent = PendingIntent.getBroadcast(c, code,
        Intent(c, NagReceiver::class.java).setAction(NagReceiver.FIRE).putExtra(NagReceiver.BY, code),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** What the lock screen's next-alarm line opens: the app. */
    private fun show(c: Context): PendingIntent? = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
        PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The three alarms set to [next] ([now] the time it was worked out at): the backup first, which needs no access. */
    fun arm(c: Context, next: Rules.Arm, now: Long) {
        val am = am(c)
        // An alarm already past fires at once; the backup leaves one to its own alarm clock (an inexact alarm can't start
        // the ring), so a past alarm never spins it.
        val backup = listOfNotNull(next.step, next.alarm?.takeIf { it > now }).minOrNull()
        if (backup == null) am.cancel(fire(c, BACKUP)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backup, fire(c, BACKUP))
        if (!exact(c)) {
            am.cancel(fire(c, STEP)); am.cancel(fire(c, ALARM))
            return
        }
        val step = next.step
        when {
            step == null -> am.cancel(fire(c, STEP))
            restricted(c) -> am.setAlarmClock(AlarmManager.AlarmClockInfo(step, show(c)), fire(c, STEP))
            else -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, step, fire(c, STEP))
        }
        val alarm = next.alarm
        if (alarm == null) am.cancel(fire(c, ALARM)) else am.setAlarmClock(AlarmManager.AlarmClockInfo(alarm, show(c)), fire(c, ALARM))
    }
}

internal object Engine {
    /** The wall clock every way in reads; tests set it. */
    @Volatile var clock: () -> Long = System::currentTimeMillis
    private fun now() = clock()

    /** What Android shows and plays for a context and the plan (the seam, `Steps.kt`): `AndroidOut` by default; tests
     * set a fake. */
    @Volatile var out: (Context, Plan) -> Out = { c, plan -> AndroidOut(c, plan) }

    /** How long the notification's "Agora não" shows its confirmation ("Combinado: …") before it goes. */
    const val CONFIRM_MS = 5_000L

    /** The app's activity is resumed: the module reads it when it is made and hears each resume and pause. In front a
     * step shows no heads-up and the app acts on it ([fire], decisions 17 and 7, item 17). */
    @Volatile var appInFront = false

    /** The doses the nag route on top shows, as it said (`nagScreen`); none once it is no longer on top. In memory: the
     * route lives only as long as the process. */
    @Volatile var screen: Set<String> = emptySet()

    /** The plan and the state every way in starts from; a state lost with its file goes on from the plan
     * ([Rules.recover]). Null with no plan. */
    private fun load(c: Context, now: Long): Pair<Plan, State>? {
        val plan = Store.plan(c) ?: return null
        val st = Store.state(c)
        if (st.fresh && st.groups.isEmpty()) Rules.recover(plan, st, now)
        Rules.upgrade(plan, st)
        return plan to st
    }

    /** Whether nothing of [keys]' reminders is left to do now: each of their groups resolved, or promised whole (every
     * open dose of it under a promise, which is per dose). A promise on one dose leaves the others to answer. */
    @Synchronized fun settled(c: Context, keys: List<String>): Boolean {
        val plan = Store.plan(c) ?: return true
        val st = Store.state(c)
        return plan.doses.filter { it.key in keys }.map { it.group }.distinct()
            .all { st.groups[it]?.state.let { s -> s == null || s == "done" || s == "promised" } }
    }

    /** A plan from the app, sent at every resume and change: skipped when only its `made` changed. One that cannot be
     * read is refused, and the last one kept: it is read before it is stored. Her reminder channels follow its sounds
     * (made when a plan first names one, `docs/exact.md` item 5). */
    @Synchronized fun schedule(c: Context, plan: JSONObject, now: Long = now()): Boolean {
        val st = Store.state(c)
        Store.plan(c)?.let { Rules.upgrade(it, st) }
        val hash = JSONObject(plan.toString()).apply { remove("made") }.toString().hashCode()
        val parsed = (if (hash == st.planHash) Store.plan(c) else null) ?: run {
            val read = runCatching { Plan.parse(plan) }.onFailure { Log.e(TAG, "plan refused", it) }.getOrNull() ?: return false
            Store.savePlan(c, plan.toString())
            read.also { Rules.upgrade(it, st); Rules.merge(it, st, now); st.planHash = hash }
        }
        st.planAt = now
        guard("channels") { Shade.channels(c, parsed) }
        tick(c, parsed, st, now, "plan")
        return true
    }

    /** A boot, a time or zone change, the package replaced, the module made, the app back in front: everything is set
     * again. A ring the process lost ends at its cap, or rings on until then; a step that fell due meanwhile fires at
     * once. The app in [front] counts as a plan sent for the stale notice: the app builds its plan at every resume and
     * sends it only when it changed, so an unchanged one already covers the days ahead. */
    @Synchronized fun restore(c: Context, now: Long = now(), front: Boolean = false) {
        val (plan, st) = load(c, now) ?: return
        if (front) st.planAt = maxOf(st.planAt, now)
        tick(c, plan, st, now, "restore")
    }

    private fun tick(c: Context, plan: Plan, st: State, now: Long, why: String) {
        val events = ArrayList<Event>()
        Rules.lapse(plan, st, now, events)
        val rangOut = Rules.ringOut(plan, st, now, events)
        if (Shade.stoppable(c)) Rules.unmute(st)
        commit(c, plan, st, events, now, why = if (rangOut) "timeout" else why)
    }

    /** The ring service's cap: the alarm has rung its 60 s, [at] its end (the wall clock may have moved meanwhile). */
    @Synchronized fun ringOut(c: Context, at: Long, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.ringOut(plan, st, maxOf(now, at), events)
        commit(c, plan, st, events, now, why = "timeout")
    }

    /**
     * An alarm ([by] the request code that fired, an [Intent] extra): lapses, the steps due, the low-stock notice on a
     * day's first post, the stale notice. The inexact backup never rings the alarm while exact access holds: the alarm
     * clock does, which alone may start the ring service from the background.
     *
     * Decision 17: a step that fires while the app is in front is quiet to its end ([State.quiet]): no heads-up and no
     * full-screen intent. Decision 7 and item 17: the engine opens nothing in front. It tells the app ([State.front],
     * read as `nagState().front`, with its step), which shows the banner and plays her sound for a notification step,
     * and opens the nag screen for the full screen and the alarm; unless the nag screen already shows one of its doses
     * ("Ainda não" in Novo remédio). A post in the background clears it.
     *
     * Item 20: with notifications off nothing can reach her, but a step due with the app in front still tells it, as
     * a silent post ([Rules.silent], [State.silent]) that the app opens without a sound. In the background it is only
     * muted.
     */
    @Synchronized fun fire(c: Context, now: Long = now(), by: Int = Chain.STEP) {
        val (plan, st) = load(c, now) ?: return Chain.arm(c, Rules.Arm(null, null), now)
        val exact = Chain.exact(c)
        val events = ArrayList<Event>()
        Rules.lapse(plan, st, now, events)
        val rangOut = Rules.ringOut(plan, st, now, events)
        val stoppable = Shade.stoppable(c)
        if (stoppable) Rules.unmute(st)
        val due = Rules.due(plan, st, now, exact, alarms = by != Chain.BACKUP || !exact)
        val front = appInFront
        var post: Post? = null
        if (due.isNotEmpty()) {
            val shown = screen
            val told = front && due.none { d -> plan.doses(d.group.group).any { it.key in shown } }
            if (stoppable) {
                val fires = Rules.fire(plan, st, due, now, events, exact)
                if (fires.isNotEmpty()) {
                    if (front) st.quiet = st.nag
                    st.front = if (told) st.nag else 0
                    post = Rules.post(plan, st, st.nag, fires, front, now)
                    val today = WallClock.day(now)
                    if (plan.stock != null && st.stockDay != today) { st.stockDay = today; guard("stock") { Shade.post(c, Shade.STOCK, Shade.stock(c, plan)) } }
                }
            } else {
                val muted = Rules.mute(plan, st, due, now)
                if (told && muted.isNotEmpty()) { st.front = Rules.silent(st, muted); st.silent = st.front; st.silentAt = now }
            }
        }
        if (Rules.stale(plan, st)?.let { it <= now } == true) { st.staleShown = st.planAt; guard("stale") { Shade.post(c, Shade.STALE, Shade.stale(c, plan)) } }
        commit(c, plan, st, events, now, post, when { post != null -> "step"; rangOut -> "timeout"; else -> "wake" })
    }

    /**
     * The notification's "Agora não" on post [nag] (a broadcast, so the app doesn't open): every group it shows moves its
     * next step one gap after now, never sooner than it was due ([Rules.notNow]); the post turns, silently, into the
     * plan's confirmation with the time that comes next for 5 s, then goes, and stays away until the next step. A post
     * that no longer shows changes nothing.
     */
    @Synchronized fun notNow(c: Context, nag: Int, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val gs = Rules.of(plan, st, nag)
        if (gs.isEmpty()) return
        val exact = Chain.exact(c)
        val answers = gs.map { Rules.answer(plan, st, it, now, exact) }
        val events = ArrayList<Event>()
        Rules.ringOut(plan, st, now, events)
        Rules.notNow(plan, st, gs, now, events)
        st.confirm = nag; st.confirmUntil = now + CONFIRM_MS
        commit(c, plan, st, events, now, why = "notNow")
        guard("confirm") { out(c, plan).confirm(nag, confirmation(plan, answers)) }
    }

    /** What the notification says after its "Agora não": the earliest of what comes next, a reminder or the alarm at its
     * time, or the dose waiting on Hoje when nothing comes before it is missed. */
    fun confirmation(plan: Plan, answers: List<Rules.Answer>): String {
        val next = answers.filter { it.at != null }.minByOrNull { it.at!! }
        return when (next?.kind) {
            "alarm" -> Rules.fill(plan.texts.notNowAlarm, "", next.at)
            "remind" -> Rules.fill(plan.texts.notNowRemind, "", next.at)
            else -> Rules.fill(plan.texts.notNowWait, "", null)
        }
    }

    /** "Agora não" on the nag screen (`ignoreNags`) for [keys]: their groups' next step moves, as from the
     * notification, and the reminder stays away until it ([Rules.notNow]); the app says what comes next itself. Keys
     * the engine does not know move nothing. */
    @Synchronized fun notNowKeys(c: Context, keys: List<String>, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.ringOut(plan, st, now, events)
        Rules.notNow(plan, st, Rules.groupsOf(plan, st, keys), now, events)
        commit(c, plan, st, events, now, why = "notNow")
    }

    /** Post [nag] swiped away (its delete intent): kept, not counted ([Rules.dismissed]); nothing brings it back before
     * the next step. Its alarm, if it rang, stops. */
    @Synchronized fun dismissed(c: Context, nag: Int, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        if (!Rules.dismissed(plan, st, nag)) return
        commit(c, plan, st, emptyList(), now, why = "dismissed")
    }

    /** The alarm stopped by her, by the stop contract (`docs/exact.md` item 10: "Parar", the unlock, the power key, the
     * nag screen's touch or a volume key, its notification opened): it stops, and nothing else moves. */
    @Synchronized fun silenced(c: Context, why: String, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        if (!Rules.silence(st)) return
        commit(c, plan, st, emptyList(), now, why = why)
    }

    @Synchronized fun opened(c: Context, keys: List<String>, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        val events = ArrayList<Event>()
        Rules.opened(plan, st, keys, now, events)
        commit(c, plan, st, events, now, why = "opened")
    }

    @Synchronized fun resolve(c: Context, keys: List<String>, kind: String, until: Long?, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        Rules.resolve(plan, st, keys, kind, until, now)
        commit(c, plan, st, emptyList(), now, why = "answered")
    }

    /** The bubble sent to the nest in post [nag]: it stays away for its step, across a process death; nothing is
     * counted. */
    @Synchronized fun bubbleClosed(c: Context, nag: Int, now: Long = now()) {
        val (plan, st) = load(c, now) ?: return
        Rules.nested(plan, st, nag)
        commit(c, plan, st, emptyList(), now, why = "nest")
    }

    @Synchronized fun promiseDismissed(c: Context, until: Long) {
        val (plan, st) = load(c, now()) ?: return
        st.promiseDismissed = until
        commit(c, plan, st, emptyList(), now())
    }

    /** Events the app recorded, dropped: the app hears of it only when some were, so acknowledging what it reads
     * never feeds itself. */
    @Synchronized fun ack(c: Context, through: Long) { if (Store.ack(c, through)) NagModule.changed() }

    private fun commit(c: Context, plan: Plan, st: State, events: List<Event>, now: Long, fired: Post? = null, why: String = "refresh") {
        guard("alarm") { Chain.arm(c, Rules.next(plan, st, now, Chain.exact(c)), now) }
        guard("store") { Store.save(c, st); Store.append(c, events) }
        refresh(c, plan, st, now, fired, why)
        NagModule.changed()
    }

    /**
     * Android brought to the state, each part on its own (one that fails leaves the others): the post that fired, or
     * the one showing refreshed in place, or none; the bubble; the alarm's ring; the wait for the unlock; the promise's
     * countdown. The reminder isn't touched while the notification's "Agora não" shows its confirmation.
     */
    fun refresh(c: Context, plan: Plan, st: State, now: Long, fired: Post? = null, why: String = "refresh") {
        val out = try { out(c, plan) } catch (e: Exception) { Log.e(TAG, "out", e); return }
        guard("reminder") {
            when {
                fired != null -> out.post(fired)
                st.confirm != 0 && st.confirmUntil > now -> Unit
                else -> Rules.refresh(plan, st, now)?.let(out::refresh) ?: out.clear(st.nag)
            }
        }
        guard("bubble") { out.bubble(Rules.bubble(plan, st, now)) }
        guard("ring") { Rules.ringing(plan, st, now)?.let(out::ring) ?: out.stopRing(why) }
        guard("unlock") { out.waitUnlock(Rules.unlock(plan, st, now, Chain.exact(c))) }
        guard("promise") { Shade.promise(c, plan, st) }
    }

    private const val TAG = "pinkoo.nag"

    private inline fun guard(what: String, f: () -> Unit) {
        try { f() } catch (e: Exception) { Log.e(TAG, what, e) }
    }
}
