// The gentle reminder's rules over the plan and the state, with no Android in them (`docs/product.md` › Nagging, The
// pace; `docs/exact.md` › The gentle reminder, items 1, 2, 6, 14, 15 and 17): which step of its thread a dose
// time is at, what is due, what a fire, "Agora não", a swipe, an opening and a resolution change, what a post carries,
// and when the next alarms are. Time alone moves a thread along its steps; nothing she does makes it louder. Every
// outcome a dose has is an event the app records.
//
// A group (the doses due at one time) shares one thread: one step, one count of reminders. A pinky promise is per dose:
// a promised dose sits the group's reminders out until its deadline, while the group's other open doses keep the thread;
// the group itself is `promised` only while every open dose of it is.
package pinkoo.nag

import org.json.JSONObject
import java.time.ZoneId

internal object Rules {
    /** The alarm rings at most this long (`docs/product.md` › Alarme). */
    const val RING_MS = 60_000L
    /** "Abrir quando eu desbloquear" waits at most this long after the full screen. */
    const val UNLOCK_MS = 2 * 60 * 60_000L

    /** A step's word in the app's shapes (`notify`, `again`, `full`, `alarm`, `repeat`, `deadline`). */
    fun word(s: Step): String = s.name.lowercase()
    fun step(w: String): Step? = Step.entries.firstOrNull { it.name.lowercase() == w }

    // MARK: the doses

    /** The plan's doses of [g] still open (promised or not). */
    fun open(plan: Plan, st: State, g: Group): List<Dose> = plan.doses(g.group).filter { st.doses[it.key]?.state == "open" }

    /** The open doses of [g] its reminders are for: those under no pinky promise. */
    fun nagged(plan: Plan, st: State, g: Group): List<Dose> = open(plan, st, g).filter { st.doses[it.key]?.until == null }

    /** Every open dose under a pinky promise, in the plan's order. */
    fun promised(plan: Plan, st: State): List<Dose> = plan.doses.filter { d -> st.doses[d.key]?.let { it.state == "open" && it.until != null } == true }

    /** [d]'s promise deadline, or null with none. */
    fun until(st: State, d: Dose): Long? = st.doses[d.key]?.until

    /** A dose's own reminders: its group's, less the ones it sat out under a promise. */
    fun fires(st: State, g: Group, d: Dose): Int = maxOf(0, g.fires - (st.doses[d.key]?.behind ?: 0))

    /** The reminders that reached the doses [g] reminds now: the most any of its doses under no promise has (its open
     * ones while every one is promised). */
    fun reached(plan: Plan, st: State, g: Group): Int =
        nagged(plan, st, g).ifEmpty { open(plan, st, g) }.maxOfOrNull { fires(st, g, it) } ?: g.fires

    /** The rung of [g]'s last reminder, the one showing: the reminders that reached her before it, up to the top (4).
     * The mascot's face and lines (`src/app/plan.ts` nagRung). */
    fun rung(plan: Plan, st: State, g: Group): Int = minOf(maxOf(reached(plan, st, g) - 1, 0), Plan.TOP)

    private fun seed(plan: Plan, st: State, g: Group): Long = open(plan, st, g).firstOrNull()?.lineSeed ?: 0

    /**
     * [g]'s mascot line, the one its last reminder showed (`src/app/plan.ts` nagLineIndex): `lines[rung][(seed + n) mod
     * count]`, n the reminders before it and the seed its first open dose's; at a promise's deadline, the plan's line for
     * it. The notification, the bubble and the nag screen never disagree.
     */
    fun line(plan: Plan, st: State, g: Group, zone: ZoneId = ZoneId.systemDefault()): String {
        if (g.last == Step.DEADLINE && plan.texts.deadline.isNotEmpty()) {
            return fill(plan.texts.deadline, names(plan, st, listOf(g)), g.lastFiredAt, zone)
        }
        val n = maxOf(reached(plan, st, g) - 1, 0)
        return plan.line(minOf(n, Plan.TOP), seed(plan, st, g), n)
    }

    private fun names(plan: Plan, st: State, gs: List<Group>): String =
        gs.flatMap { nagged(plan, st, it) }.map { it.name }.distinct().joinToString(", ")

    /** A text of the plan with its `{names}` and `{time}` filled. */
    fun fill(template: String, names: String, at: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        template.replace("{names}", names).replace("{time}", at?.let { WallClock.hhmm(it, zone) } ?: "")

    private fun event(st: State, key: String, kind: String, reason: String?, at: Long, step: Step?) = Event(++st.event, key, kind, reason, at, step)

    // MARK: the thread

    /** [g]'s alarm: the earliest choice among its doses under no promise ("Já na hora" before "Só se passar de tudo"),
     * its open ones while every one is promised. */
    fun alarm(plan: Plan, st: State, g: Group): Alarm {
        val ds = nagged(plan, st, g).ifEmpty { open(plan, st, g) }
        return when {
            ds.any { it.alarm == Alarm.ON_TIME } -> Alarm.ON_TIME
            ds.any { it.alarm == Alarm.AFTER_ALL } -> Alarm.AFTER_ALL
            else -> Alarm.OFF
        }
    }

    /**
     * The step after [step] ([repeats] of them fired) on [g]'s thread: the dose time's notification, De novo, Tela cheia
     * when on, the alarm when its doses chose "Só se passar de tudo", then the plan's repeats (0 to 3); null with none
     * left. A step that's off takes no place.
     */
    fun after(plan: Plan, st: State, g: Group, step: Step?, repeats: Int): Step? {
        val repeat = if (repeats < plan.ladder.repeats) Step.REPEAT else null
        val afterFull = if (alarm(plan, st, g) == Alarm.AFTER_ALL) Step.ALARM else repeat
        return when (step) {
            null -> Step.NOTIFY
            Step.NOTIFY -> Step.AGAIN
            Step.AGAIN -> if (plan.steps.fullScreen) Step.FULL else afterFull
            Step.FULL -> afterFull
            Step.ALARM, Step.REPEAT, Step.DEADLINE -> repeat
        }
    }

    /** The gap before [s] from the step before it ([repeats] repeats fired): her pace. */
    fun gap(plan: Plan, s: Step, repeats: Int): Long = 60_000L * when (s) {
        Step.AGAIN -> plan.ladder.firstMin
        Step.FULL, Step.ALARM -> plan.ladder.thenMin
        Step.REPEAT -> if (repeats == 0) plan.ladder.repeatFirstMin else plan.ladder.repeatEveryMin
        Step.NOTIFY, Step.DEADLINE -> 0
    }

    /**
     * [g]'s next step and when it is due: the dose time's notification at its time, then each step one gap after the
     * step before it that happened, or after her "Agora não" when that is later ([Group.nextAt]). Null with no step
     * left, or nothing to remind now (done, every open dose promised, muted).
     */
    fun upcoming(plan: Plan, st: State, g: Group, zone: ZoneId = ZoneId.systemDefault()): Pair<Step, Long>? {
        if (g.state == "done" || g.state == "promised" || g.muted || nagged(plan, st, g).isEmpty()) return null
        val s = after(plan, st, g, g.step, g.repeats) ?: return null
        val at = if (g.step == null) WallClock.epoch(g.group, zone) else g.nextAt ?: g.lastFiredAt ?: WallClock.epoch(g.group, zone)
        return s to at
    }

    /** Whether [s] is the step that rings [g]'s alarm, given its alarm choice. */
    private fun ringing(s: Step, mode: Alarm): Boolean = s == Step.ALARM || (s == Step.NOTIFY && mode == Alarm.ON_TIME)

    /**
     * When [g]'s alarm rings (`setAlarmClock`, code 1): at the dose time for "Já na hora", before its notification;
     * for "Só se passar de tudo", at the time it has if the steps before it come on time, from its next step's. So a
     * step Android delays never delays it, and it moves later only when a step really fires late or "Agora não" moves
     * the thread. Null with no alarm ahead, and without exact access, where the alarm step is a notification (item 14).
     */
    fun alarmAt(plan: Plan, st: State, g: Group, exact: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!exact) return null
        val (next, at) = upcoming(plan, st, g, zone) ?: return null
        val mode = alarm(plan, st, g)
        if (mode == Alarm.OFF) return null
        if (ringing(next, mode)) return at
        if (mode != Alarm.AFTER_ALL) return null
        var s = next
        var t = at
        while (s != Step.ALARM) {
            val n = after(plan, st, g, s, g.repeats) ?: return null
            if (n == Step.REPEAT) return null
            t += gap(plan, n, g.repeats)
            s = n
        }
        return t
    }

    /** The earliest promise deadline among [g]'s open doses, or null with none. It never rings the alarm, "Já na hora"
     * included (Glauco, 2026-11-25: "como já tá atrasado… ativar tudo exceto alarme pra não ficar tão chato"): it
     * brings her sound, the bubble and the full screen ([fire]), and the alarm stays a step of the thread. */
    private fun deadline(plan: Plan, st: State, g: Group): Long? = open(plan, st, g).mapNotNull { until(st, it) }.minOrNull()

    // MARK: the plan and the state

    /**
     * [g]'s state brought to its doses: `done` with none open; `promised` while every open dose is under a promise, to
     * the earliest deadline; otherwise its thread goes on for the doses under none. A dose under no promise that joins
     * a promised group (a new medication at that time, an undo) is reminded at once when its step is past.
     */
    private fun settle(plan: Plan, st: State, g: Group) {
        val open = open(plan, st, g)
        if (open.isEmpty()) { g.state = "done"; return }
        if (g.state == "done") g.state = if (g.step == null) "upcoming" else "pending"
        val deadlines = open.map { until(st, it) }
        if (deadlines.all { it != null }) {
            g.state = "promised"; g.until = deadlines.minOf { it!! }; g.nextAt = null
        } else if (g.state == "promised") {
            g.state = if (g.step == null) "upcoming" else "pending"; g.until = null
        }
    }

    /**
     * A state written by an older engine. Version 1 (promises per group): a promised group's deadline becomes each of
     * its open doses' promise. Version 2 (the ladder of ignores): a group that rang is put on the step its reminders
     * reach (one per nag, under decision A at most one ignore each), its doses have had their full screen (the old
     * ladder showed one at every loud nag), a ring in progress is over, and a swiped notification or a closed bubble
     * stays away for that step.
     */
    fun upgrade(plan: Plan, st: State) {
        if (st.v >= State.VERSION) return
        if (st.v < 2) for (g in st.groups.values) {
            val u = g.until
            if (g.state == "promised" && u != null) for (d in open(plan, st, g)) st.doses[d.key]?.until = u
        }
        for (g in st.groups.values) {
            if (g.nag == 0 || g.state == "upcoming" || g.lastFiredAt == null) continue
            val nags = maxOf(1, g.ignores + if (g.ignoredNag >= g.nag) 0 else 1)
            var s: Step? = null
            var repeats = 0
            for (i in 0 until nags) {
                val n = after(plan, st, g, s, repeats) ?: break
                if (n == Step.REPEAT) repeats++
                s = n
            }
            g.step = s; g.last = s; g.repeats = repeats; g.fires = nags
            if (g.state == "ringing") g.state = "pending"
            if (g.state == "pending" && g.nextAt == null) {
                after(plan, st, g, s, repeats)?.let { n -> g.nextAt = g.lastFiredAt!! + gap(plan, n, repeats) }
            }
            for (d in open(plan, st, g)) st.doses[d.key]?.full = true
            g.dismissed = st.swiped != 0 && st.swiped == g.nag
            g.nested = st.bubbleClosed != 0 && st.bubbleClosed == g.nag
            g.unlocked = true
        }
        st.v = State.VERSION
    }

    /** The promises of [g] whose deadline came: those doses are reminded again, on the thread. Returns them. */
    private fun release(plan: Plan, st: State, g: Group, now: Long): List<Dose> = open(plan, st, g).filter { d ->
        st.doses[d.key]?.let { s -> (s.until ?: Long.MAX_VALUE) <= now && run { s.until = null; true } } == true
    }

    /**
     * A new plan merged into the state. A dose the plan names is open, but one resolved after the plan was made stays
     * resolved (the plan is older than the resolution); a dose or group the plan no longer names is dropped, so removing
     * a medication drops its open dose. A group keeps its place on its thread, so a new pace applies from the step after
     * the next one, which is already set; a promise the plan carries (restored from the app's records) is taken by a
     * dose that has none.
     */
    fun merge(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val keys = plan.doses.map { it.key }.toSet()
        st.doses.keys.retainAll(keys)
        for (d in plan.doses) {
            val s = st.doses.getOrPut(d.key) { DoseState(d.key) }
            if (s.state == "resolved" && (s.at ?: 0) < plan.made) { s.state = "open"; s.at = null }
            val promised = d.promiseUntil
            if (s.state == "open" && s.until == null && promised != null && promised > now) s.until = minOf(promised, plan.miss(d, zone))
        }
        val groups = plan.doses.map { it.group }.toSet()
        st.groups.keys.retainAll(groups)
        for (name in groups) settle(plan, st, st.groups.getOrPut(name) { Group(name) })
    }

    /** A dose just after midnight may be confirmed this long before its time, the day before ([earlyFrom]). */
    const val EARLY_MS = 2 * 60 * 60_000L

    /**
     * From when the dose at [group] may be confirmed early, as the app has it (`src/domain` `earlyFrom`): the start of
     * its own local day, or [EARLY_MS] before it when that is earlier (a dose just after midnight).
     */
    fun earlyFrom(group: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        minOf(WallClock.epoch(group.take(10) + "T00:00", zone), WallClock.epoch(group, zone) - EARLY_MS)

    /**
     * The state lost (its file unreadable) but not the plan: the doses that can't be confirmed yet ([earlyFrom] still
     * ahead, so in practice those of the days ahead) go on. The ones before are left out, as she may have taken them,
     * until the app's next plan, which a lost state always merges (its hash is unknown). The threads of those ahead start
     * anew.
     */
    fun recover(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val ahead = plan.doses.filter { earlyFrom(it.group, zone) > now }
        for (d in ahead) st.doses.getOrPut(d.key) { DoseState(d.key) }
        for (name in ahead.map { it.group }.toSet()) st.groups.getOrPut(name) { Group(name) }
    }

    /** Doses past their `miss` lapse; a group with nothing open is done, and one with only promises left is promised. */
    fun lapse(plan: Plan, st: State, now: Long, out: MutableList<Event>, zone: ZoneId = ZoneId.systemDefault()) {
        for (d in plan.doses) {
            val s = st.doses[d.key] ?: continue
            if (s.state != "open" || plan.miss(d, zone) > now) continue
            s.state = "lapsed"; s.at = now; s.until = null
            out += event(st, d.key, "lapsed", null, now, st.groups[d.group]?.last)
        }
        for (g in st.groups.values) if (g.state != "done") settle(plan, st, g)
    }

    // MARK: firing

    /** A group due now, and the step it fires. */
    class Due(val group: Group, val step: Step)

    /**
     * The groups due a reminder at [now], each with its step: the alarm when its time came (and the steps it passed are
     * skipped), else its next step when due (without exact access the alarm step comes as a notification), else a
     * promise's deadline, which never rings. With [alarms] false (the inexact backup's wake while exact access holds) an
     * alarm is left to its own alarm clock, which alone may start the ring from the background.
     */
    fun due(plan: Plan, st: State, now: Long, exact: Boolean, alarms: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): List<Due> =
        st.groups.values.mapNotNull { g ->
            if (g.state == "done" || g.muted || open(plan, st, g).isEmpty()) return@mapNotNull null
            val deadline = deadline(plan, st, g)?.takeIf { it <= now }
            val up = upcoming(plan, st, g, zone)
            val ring = alarmAt(plan, st, g, exact, zone)?.takeIf { it <= now }
            val due = when {
                ring != null -> if (up?.first == Step.NOTIFY) Step.NOTIFY else Step.ALARM
                up != null && up.second <= now -> up.first
                deadline != null -> Step.DEADLINE
                else -> null
            } ?: return@mapNotNull null
            if (ring != null && !alarms) null else Due(g, due)
        }

    /** What one group's fire was: its step, whether it brings the full screen (Tela cheia once per dose; a promise's
     * deadline whenever Tela cheia is on), and whether it rings the alarm (whose post brings the full screen too, every
     * time it rings: [post]). */
    class Fire(val group: Group, val step: Step, val fullScreen: Boolean, val rings: Boolean)

    /**
     * One post for [dues], one number for all of them, for their doses under no promise (a promise whose deadline came is
     * over). Each group moves to its step, counts one more reminder (a promised dose sits it out), and sets its next one
     * gap after now. Tela cheia comes once per dose; a promise's deadline brings the full screen whenever Tela cheia is
     * on, even after the dose had it, and doesn't spend the dose's (Glauco, 2026-11-25); the alarm rings only with exact
     * access, never at a deadline. With [standIn] (the alarm can't open its screen: no full-screen access, the app not in
     * front; and Pinkoo may draw over other apps), an alarm that rings brings the bubble and the opening at her unlock
     * in its place ([Group.standIn]). Returns the fires, the post's number being [State.nag].
     */
    fun fire(plan: Plan, st: State, dues: List<Due>, now: Long, out: MutableList<Event>, exact: Boolean, standIn: Boolean = false): List<Fire> {
        if (dues.isEmpty()) return emptyList()
        val nag = st.nag + 1
        val fires = ArrayList<Fire>()
        for (due in dues) {
            val g = due.group
            val rings = exact && when (due.step) {
                Step.ALARM -> true
                Step.NOTIFY -> alarm(plan, st, g) == Alarm.ON_TIME
                else -> false
            }
            release(plan, st, g, now)
            val nagged = nagged(plan, st, g)
            if (nagged.isEmpty()) { settle(plan, st, g); continue }
            when (due.step) {
                Step.DEADLINE -> if (g.step == null) g.step = Step.NOTIFY
                Step.REPEAT -> { g.step = Step.REPEAT; g.repeats++ }
                else -> g.step = due.step
            }
            g.last = due.step
            val full = when (due.step) {
                Step.FULL -> nagged.any { st.doses[it.key]?.full != true }
                Step.DEADLINE -> plan.steps.fullScreen
                else -> false
            }
            g.standIn = rings && standIn
            if (due.step == Step.FULL) for (d in nagged) st.doses[d.key]?.full = true
            if (due.step == Step.FULL || g.standIn) g.unlocked = false
            g.fires++
            for (d in open(plan, st, g)) st.doses[d.key]?.let { if (it.until != null) it.behind++ }
            g.state = if (rings) "ringing" else "pending"
            g.nag = nag; g.lastFiredAt = now; g.until = null
            g.dismissed = false; g.nested = false; g.muted = false
            // The next step one gap after this one; a deadline never brings the others' next step sooner than it was.
            val next = after(plan, st, g, g.step, g.repeats)?.let { now + gap(plan, it, g.repeats) }
            g.nextAt = if (due.step == Step.DEADLINE && next != null) maxOf(next, g.nextAt ?: 0) else next
            for (d in nagged) out += event(st, d.key, "fired", null, now, due.step)
            fires += Fire(g, due.step, full, rings)
        }
        if (fires.isNotEmpty()) { st.nag = nag; st.confirm = 0; st.confirmUntil = 0 }
        return fires
    }

    /** No notification can reach her (notifications off): the groups due wait, silently, until they are back
     * ([unmute]) or they lapse; a step that never reached her counts nothing. A promise whose deadline came is over: its
     * dose waits with them. Returns the groups it silenced (those with a dose left to remind). */
    fun mute(plan: Plan, st: State, dues: List<Due>, now: Long): List<Group> {
        val muted = ArrayList<Group>()
        for (due in dues) {
            val g = due.group
            release(plan, st, g, now)
            settle(plan, st, g)
            if (nagged(plan, st, g).isEmpty()) continue
            g.muted = true
            if (g.state == "upcoming") g.state = "pending"
            muted += g
        }
        return muted
    }

    /**
     * Item 20: groups [mute] silenced while the app is in front still come up there, as a post of their own number that
     * the app opens without a sound (`nagState().front`). Nothing reached her, so nothing counts, and its "Agora não"
     * moves nothing. Its number comes from the posts' own count, so each post after it is still a new notification.
     * Returns it, or 0 with no group.
     */
    fun silent(st: State, groups: List<Group>): Int {
        if (groups.isEmpty()) return 0
        val nag = ++st.nag
        for (g in groups) g.nag = nag
        return nag
    }

    /** Notifications are back: every group [mute] silenced is reminded at once (its step's time is past), or at its
     * deadline when she promised meanwhile. */
    fun unmute(st: State) {
        for (g in st.groups.values) g.muted = false
    }

    /** When [g]'s alarm stops by itself, ringing: its cap after it began. */
    fun ringEnd(g: Group): Long? = g.lastFiredAt?.takeIf { g.state == "ringing" }?.let { it + RING_MS }

    /** Every alarm still ringing at its cap rings out (`timeout`, at that end): it stops; the next step was set at its
     * fire and doesn't move. Whether one did. */
    fun ringOut(plan: Plan, st: State, now: Long, out: MutableList<Event>): Boolean {
        var any = false
        for (g in st.groups.values) {
            val end = ringEnd(g) ?: continue
            if (end > now) continue
            g.state = "pending"; any = true
            for (d in nagged(plan, st, g)) out += event(st, d.key, "ignored", "timeout", end, g.last)
        }
        return any
    }

    /** The alarm silenced by her ("Parar", the unlock, the power key, a touch or a volume key on the nag screen, its
     * notification swiped or opened): it stops, and nothing else moves. Whether one rang. */
    fun silence(st: State): Boolean {
        var any = false
        for (g in st.groups.values) if (g.state == "ringing") { g.state = "pending"; any = true }
        return any
    }

    // MARK: her answers

    /** What "Agora não" says it brings next: `remind` or `alarm` at [at], or `wait` (nothing before the dose is
     * missed, [at] null). */
    class Answer(val kind: String, val at: Long?)

    /**
     * What "Agora não" tapped at [now] would say for [g]: its next step moved to one gap after the tap, never sooner
     * than it was due; `wait` when no step is left, or the moved one would come at or past the moment its doses are
     * missed. The alarm only with exact access.
     */
    fun answer(plan: Plan, st: State, g: Group, now: Long, exact: Boolean, zone: ZoneId = ZoneId.systemDefault()): Answer {
        val (next, due) = upcoming(plan, st, g, zone) ?: return Answer("wait", null)
        val at = if (g.step == null) due else maxOf(due, now + gap(plan, next, g.repeats))
        val miss = nagged(plan, st, g).maxOfOrNull { plan.miss(it, zone) } ?: return Answer("wait", null)
        if (at >= miss) return Answer("wait", null)
        return Answer(if (exact && next == Step.ALARM) "alarm" else "remind", at)
    }

    /**
     * "Agora não" for [groups] at [now] (the nag screen's, or the notification's): each one's next step moves to one gap
     * after the tap, never sooner than it was due; it never skips, adds or changes a step, and a second tap moves it
     * again by the same rule. The reminder then stays away until the next step, as after a swipe: its post, its bubble,
     * the opening at the unlock and the app's front. Logged `notNow`, counting nothing. Groups silenced with notifications off move
     * nothing: nothing could reach her again.
     */
    fun notNow(plan: Plan, st: State, groups: Collection<Group>, now: Long, out: MutableList<Event>, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        var any = false
        for (g in groups) {
            if (g.muted || g.step == null || g.state == "done" || g.state == "promised") continue
            val nagged = nagged(plan, st, g)
            if (nagged.isEmpty()) continue
            upcoming(plan, st, g, zone)?.let { (next, due) -> g.nextAt = maxOf(due, now + gap(plan, next, g.repeats)) }
            if (g.state == "ringing") g.state = "pending"
            g.dismissed = true; g.nested = true; g.unlocked = true
            if (st.front != 0 && g.nag == st.front) st.front = 0
            for (d in nagged) out += event(st, d.key, "ignored", "notNow", now, g.last)
            any = true
        }
        return any
    }

    /** The groups the reminder notification shows now: those whose step fired, under no promise, not dismissed. */
    fun shown(plan: Plan, st: State): List<Group> = st.groups.values
        .filter { (it.state == "pending" || it.state == "ringing") && it.lastFiredAt != null && !it.dismissed && !it.muted && nagged(plan, st, it).isNotEmpty() }
        .sortedBy { it.group }

    /** The post the reminder notification shows: the latest number among [shown], 0 with none. */
    fun live(plan: Plan, st: State): Int = shown(plan, st).maxOfOrNull { it.nag } ?: 0

    /** The groups of post [nag] while it is the one showing: what its "Agora não" or its swipe answers. */
    fun of(plan: Plan, st: State, nag: Int): List<Group> = if (nag == 0 || nag != live(plan, st)) emptyList() else shown(plan, st)

    /**
     * Post [nag] swiped away (its delete intent): every group it showed stays away until its next step, through a
     * refresh, a plan, a receiver or another group's fire, and the app's front ends; nothing is counted. An alarm ringing
     * in it stops (her swipe on its notification, once unlocked). A post that no longer shows changes nothing.
     */
    fun dismissed(plan: Plan, st: State, nag: Int): Boolean {
        val gs = of(plan, st, nag)
        for (g in gs) {
            g.dismissed = true
            if (g.state == "ringing") g.state = "pending"
            if (st.front != 0 && g.nag == st.front) st.front = 0
        }
        return gs.isNotEmpty()
    }

    /** The bubble sent to the nest in post [nag]: it stays away for the step of every group whose step brings it
     * ([bubbles]), across a process death; it logs nothing. */
    fun nested(plan: Plan, st: State, nag: Int) {
        for (g in st.groups.values) if (bubbles(plan, g) && (g.nag == nag || nag == 0)) g.nested = true
    }

    /** The nag route opened at her unlock: every group waiting for it (after its full screen, or an alarm that rang
     * without its screen) is done waiting (once per step, item 12). Whether one was. */
    fun unlocked(st: State): Boolean {
        var any = false
        for (g in st.groups.values) if ((g.last == Step.FULL || g.standIn) && !g.unlocked) { g.unlocked = true; any = true }
        return any
    }

    /** The nag screen opened on [keys]: an alarm ringing for them stops, the opening at the unlock is done, and nothing
     * else moves: the next step comes at its time. A post of theirs that fired in front has been shown ([front]). */
    fun opened(plan: Plan, st: State, keys: Collection<String>, now: Long, out: MutableList<Event>) {
        for (g in groupsOf(plan, st, keys)) {
            if (g.state == "ringing") g.state = "pending"
            g.unlocked = true
            for (d in nagged(plan, st, g)) if (d.key in keys) out += event(st, d.key, "opened", null, now, g.last)
            if (st.front != 0 && g.nag == st.front) st.front = 0
        }
    }

    /**
     * Decision 7 and item 17: the lead group (the earliest) of the post that fired with the app in front and the nag
     * screen has not shown yet, which the app reads (`nagState().front`, with [frontStep]); null once shown, answered,
     * promised or missed, or after a post in the background.
     */
    fun front(plan: Plan, st: State): Group? = if (st.front == 0) null else st.groups.values
        .filter { it.nag == st.front && (it.state == "ringing" || it.state == "pending") && nagged(plan, st, it).isNotEmpty() }
        .minByOrNull { it.group }

    /** The step [front]'s post came with: its fire's; for one silenced with notifications off, the step that came due. */
    fun frontStep(plan: Plan, st: State, g: Group, zone: ZoneId = ZoneId.systemDefault()): Step? =
        if (st.front != 0 && st.front == st.silent) after(plan, st, g, g.step, g.repeats) ?: g.last else g.last

    /**
     * [keys] resolved in the app: `taken` or `skipped` closes them; `promised` holds each of them, and only them, until
     * [until], capped at the moment that dose is missed; the last promise counts. Their groups follow: done with nothing
     * open, promised once every open dose is, and otherwise still reminding the doses under no promise.
     */
    fun resolve(plan: Plan, st: State, keys: Collection<String>, kind: String, until: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        if (kind == "promised") {
            for (d in plan.doses) {
                if (d.key !in keys) continue
                val s = st.doses[d.key]?.takeIf { it.state == "open" } ?: continue
                s.until = minOf(until ?: now, plan.miss(d, zone))
            }
        } else {
            for (k in keys) st.doses[k]?.takeIf { it.state == "open" }?.let { it.state = "resolved"; it.at = now; it.until = null }
        }
        for (g in groupsOf(plan, st, keys)) if (g.state != "done") {
            settle(plan, st, g)
            if (g.state == "promised" && (g.last == Step.FULL || g.standIn)) g.unlocked = true
        }
    }

    fun groupsOf(plan: Plan, st: State, keys: Collection<String>): List<Group> =
        plan.doses.filter { it.key in keys }.map { it.group }.distinct().mapNotNull { st.groups[it] }

    // MARK: what shows

    /** The stale notice's time, the plan's days after the app last sent one, or null once shown for that plan. A plan
     * whose idle days are 0 has no stale notice: no medication left whose reminders could run out. */
    fun stale(plan: Plan, st: State): Long? =
        if (st.planAt == 0L || st.staleShown == st.planAt || plan.idle.afterDays == 0) null
        else st.planAt + plan.idle.afterDays * 24L * 60 * 60_000

    /** Whether [g]'s last post brings the bubble: De novo and a promise's deadline with Bolinha on, and an alarm that
     * rang without its screen ([Group.standIn]), Bolinha on or off. */
    private fun bubbles(plan: Plan, g: Group): Boolean =
        (plan.steps.bubble && (g.last == Step.AGAIN || g.last == Step.DEADLINE)) || g.standIn

    /** When the bubble of [g] leaves by itself: at its next step, or one gap after it came when none follows. Null
     * when it isn't showing for [g] (its step brings none, [bubbles]; sent to the nest; nothing to remind). */
    fun bubbleEnd(plan: Plan, st: State, g: Group, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!bubbles(plan, g) || g.nested || g.muted || (g.state != "pending" && g.state != "ringing")) return null
        if (nagged(plan, st, g).isEmpty()) return null
        val fired = g.lastFiredAt ?: return null
        return upcoming(plan, st, g, zone)?.second ?: (fired + gap(plan, Step.FULL, g.repeats))
    }

    /** Until when the nag screen opens at her first unlock for [g]: from its full screen with "Abrir quando eu
     * desbloquear" on, or from an alarm that rang without its screen ([Group.standIn], the switch on or off), until its
     * next step, at most [UNLOCK_MS]; null when not waiting. */
    fun unlockEnd(plan: Plan, st: State, g: Group, exact: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val waits = (plan.steps.unlock && g.last == Step.FULL) || g.standIn
        if (!waits || g.unlocked || g.muted || (g.state != "pending" && g.state != "ringing")) return null
        if (nagged(plan, st, g).isEmpty()) return null
        val fired = g.lastFiredAt ?: return null
        return listOfNotNull(upcoming(plan, st, g, zone)?.second, alarmAt(plan, st, g, exact, zone), fired + UNLOCK_MS).min()
    }

    /** The two alarms to set: code 0 at [step] (every step, repeat, deadline, lapse, the ring's cap, the bubble's and
     * the unlock's ends, and the stale notice), code 1 at [alarm] (the next alarm step, with exact access only). */
    class Arm(val step: Long?, val alarm: Long?)

    /**
     * The alarms to set after [now]: the earliest of every event but the alarm's, and the earliest alarm separately, so
     * a deferred code-0 alarm never delays it ([alarmAt]). Without exact access the alarm steps are notification steps
     * among the others. A time already past fires at once; an end that already passed (the bubble's, the unlock's)
     * isn't one.
     */
    fun next(plan: Plan, st: State, now: Long, exact: Boolean, zone: ZoneId = ZoneId.systemDefault()): Arm {
        val steps = ArrayList<Long>()
        val alarms = ArrayList<Long>()
        for (g in st.groups.values) {
            // A group silenced with notifications off waits for them, its deadlines too: only its lapse wakes it.
            if (g.state == "done" || g.muted || open(plan, st, g).isEmpty()) continue
            ringEnd(g)?.let { steps += it }
            for (d in open(plan, st, g)) until(st, d)?.let { steps += it }
            val up = upcoming(plan, st, g, zone)
            val ring = alarmAt(plan, st, g, exact, zone)
            ring?.let { alarms += it }
            if (up != null && (ring == null || !ringing(up.first, alarm(plan, st, g)))) steps += up.second
            bubbleEnd(plan, st, g, zone)?.takeIf { it > now }?.let { steps += it }
            unlockEnd(plan, st, g, exact, zone)?.takeIf { it > now }?.let { steps += it }
        }
        for (d in plan.doses) st.doses[d.key]?.takeIf { it.state == "open" }?.let { steps += plan.miss(d, zone) }
        stale(plan, st)?.let { steps += it }
        return Arm(steps.minOrNull(), alarms.minOrNull())
    }

    /**
     * The post for [fires] (their number [nag]): the earliest firing group's step, channel (its first dose's sound),
     * face and line; every open dose not under a promise and not dismissed named; the full screen when a firing group
     * enters Tela cheia or reaches a promise's deadline with Tela cheia on, and whenever a firing group rings the alarm
     * (Glauco, 2026-11-24: the alarm wakes the screen and opens the nag route ringing, every time; neither is the dose's
     * Tela cheia, which stays once per dose); the alarm while one rings, any step with it folded in, silent; her sound
     * otherwise, but in [front] (the app plays it) and for Tela cheia (silent over the lock screen). A deadline's full
     * screen sounds: it brings everything but the alarm (Glauco, 2026-11-25).
     */
    fun post(plan: Plan, st: State, nag: Int, fires: List<Fire>, front: Boolean, now: Long, zone: ZoneId = ZoneId.systemDefault()): Post {
        val lead = fires.minBy { it.group.group }
        val rings = st.groups.values.any { it.state == "ringing" && nagged(plan, st, it).isNotEmpty() }
        val sounds = fires.any { f -> !f.rings && (f.step != Step.FULL || !f.fullScreen) }
        return view(plan, st, nag, lead.group, lead.step, fires.map { it.group.group }.sorted(), now, zone).copy(
            sounding = sounds && !rings && !front, fullScreen = fires.any { it.fullScreen || it.rings } && !front, front = front,
            title = fill(plan.texts.title, names(plan, st, shown(plan, st)), WallClock.epoch(lead.group.group, zone), zone),
        )
    }

    /**
     * What the reminder notification shows between steps (a silent refresh in place): the post showing ([live]) with
     * the waiting title, the names and line as they are now, the alarm while it rings; null with nothing to show (no
     * step fired, all answered, promised or dismissed).
     */
    fun refresh(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()): Post? {
        val shown = shown(plan, st)
        val nag = shown.maxOfOrNull { it.nag } ?: return null
        val lead = shown.filter { it.nag == nag }.minBy { it.group }
        val title = plan.texts.pending.ifEmpty { plan.texts.title }
        return view(plan, st, nag, lead, lead.last ?: Step.NOTIFY, shown.filter { it.nag == nag }.map { it.group }, now, zone)
            .copy(title = fill(title, names(plan, st, shown), WallClock.epoch(lead.group, zone), zone))
    }

    /** The bubble's post: the earliest group whose step brings it (De novo or a deadline with Bolinha on, an alarm that
     * rang without its screen), not sent to the nest, before its end ([bubbleEnd]); null with none. */
    fun bubble(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()): Post? {
        val g = st.groups.values.filter { g -> bubbleEnd(plan, st, g, zone)?.let { it > now } == true }.minByOrNull { it.group } ?: return null
        return view(plan, st, g.nag, g, g.last ?: Step.AGAIN, listOf(g.group), now, zone)
            .copy(title = fill(plan.texts.title, names(plan, st, listOf(g)), WallClock.epoch(g.group, zone), zone))
    }

    /** Until when the nag screen opens at her first unlock: the latest wait of the groups after their full screen;
     * null with none. */
    fun unlock(plan: Plan, st: State, now: Long, exact: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long? =
        st.groups.values.mapNotNull { unlockEnd(plan, st, it, exact, zone)?.takeIf { e -> e > now } }.maxOrNull()

    /** The alarm's post while one rings: its group's (the earliest ringing); null with none. */
    fun ringing(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()): Post? {
        val g = st.groups.values.filter { it.state == "ringing" && nagged(plan, st, it).isNotEmpty() }.minByOrNull { it.group } ?: return null
        return view(plan, st, g.nag, g, g.last ?: Step.ALARM, listOf(g.group), now, zone)
            .copy(title = fill(plan.texts.title, names(plan, st, listOf(g)), WallClock.epoch(g.group, zone), zone))
    }

    /** The parts every post shares, for [lead] at [step]: what is named (every group [shown], but dismissed ones), the
     * channel, the line and the face; the alarm and its folding while one rings; the bubble while it shows. */
    private fun view(plan: Plan, st: State, nag: Int, lead: Group, step: Step, groups: List<String>, now: Long, zone: ZoneId): Post {
        val shown = (shown(plan, st) + st.groups.values.filter { it.group in groups }).distinct()
        val doses = plan.doses.filter { d -> shown.any { it.group == d.group } && st.doses[d.key]?.let { it.state == "open" && it.until == null } == true }
        val rings = st.groups.values.any { it.state == "ringing" && nagged(plan, st, it).isNotEmpty() }
        val bubble = st.groups.values.any { g -> bubbleEnd(plan, st, g, zone)?.let { it > now } == true }
        return Post(nag, groups, step, doses.map { it.key }, doses.map { it.name }.distinct(),
            nagged(plan, st, lead).firstOrNull()?.sound ?: Plan.DEFAULT_SOUND, sounding = false, fullScreen = false,
            alarm = rings, bubble = bubble, folded = rings && step != Step.ALARM, front = st.quiet != 0 && st.quiet == nag,
            title = plan.texts.title, line = line(plan, st, lead, zone), rung = rung(plan, st, lead))
    }

    // MARK: what the app reads

    /**
     * Each planned dose's state as the app reads it (`nagState`, `NagDoseState`): a dose under a promise is `promised`
     * to its own deadline, while the others of its group show the group's thread. `step` is its group's last post's
     * (a deadline's included), `fires` its own reminders (a promise sat out excluded), `rung` its last reminder's (the
     * ones before it, up to 4), `nextAt` and `nextStep` what comes next, and `alarmAt` when the alarm rings.
     */
    fun doses(plan: Plan, st: State, exact: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<JSONObject> = plan.doses.map { d ->
        val g = st.groups[d.group]
        val s = st.doses[d.key]
        val own = s?.state ?: "open"
        val promise = s?.until.takeIf { own == "open" }
        val state = when {
            own != "open" -> own
            g == null || g.state == "done" -> "upcoming"
            promise != null -> "promised"
            else -> g.state
        }
        val fires = g?.let { fires(st, it, d) } ?: 0
        val live = own == "open" && promise == null && g != null
        val up = if (live) upcoming(plan, st, g!!, zone) else null
        // A promise's deadline never rings: the dose's alarm is a step of its thread again after it.
        val alarmAt = if (live) alarmAt(plan, st, g!!, exact, zone) else null
        JSONObject().put("key", d.key).put("state", state).put("step", g?.last?.let(::word) ?: JSONObject.NULL)
            .put("fires", fires).put("rung", minOf(maxOf(fires - 1, 0), Plan.TOP))
            .put("nextAt", (if (promise != null) promise else up?.second) ?: JSONObject.NULL)
            .put("nextStep", (if (promise != null) word(Step.DEADLINE) else up?.first?.let(::word)) ?: JSONObject.NULL)
            .put("alarmAt", alarmAt ?: JSONObject.NULL)
            .put("lastFiredAt", g?.lastFiredAt ?: JSONObject.NULL)
    }
}
