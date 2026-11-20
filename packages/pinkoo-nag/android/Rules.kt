// The ladder's rules over the plan and the state, with no Android in them: what is due, what a fire, an ignore, an
// opening and a resolution change, and when the next alarm is. Every outcome a dose has is an event the app records.
//
// A group (the doses due at one time) shares one ladder: one nag, one ring, one ignore count. A pinky promise is per
// dose ("one pinky promise per medication"): a promised dose sits the group's nags out until its deadline, while the
// group's other open doses keep its ladder; the group itself is `promised` only while every open dose of it is.
package pinkoo.nag

import java.time.ZoneId

internal object Rules {
    /** The plan's doses of [g] still open (promised or not). */
    fun open(plan: Plan, st: State, g: Group): List<Dose> = plan.doses(g.group).filter { st.doses[it.key]?.state == "open" }

    /** The open doses of [g] its nags are for: those under no pinky promise. */
    fun nagged(plan: Plan, st: State, g: Group): List<Dose> = open(plan, st, g).filter { st.doses[it.key]?.until == null }

    /** Every open dose under a pinky promise, in the plan's order. */
    fun promised(plan: Plan, st: State): List<Dose> = plan.doses.filter { d -> st.doses[d.key]?.let { it.state == "open" && it.until != null } == true }

    /** [d]'s promise deadline, or null with none. */
    fun until(st: State, d: Dose): Long? = st.doses[d.key]?.until

    /** A dose's own ignores: its group's, less the ones it sat out under a promise. */
    private fun ownIgnores(st: State, g: Group, d: Dose): Int = maxOf(0, g.ignores - (st.doses[d.key]?.behind ?: 0))

    /**
     * [g]'s ignores as the nag screen counts them (`src/app/views.ts` nagView): the most any of its open doses has.
     * Without a promise in the group, its own count; a dose back from a promise while the others climbed comes back at
     * its own count once they are resolved (a promise never resets the ladder, nor climbs it).
     */
    fun ignores(plan: Plan, st: State, g: Group): Int = open(plan, st, g).maxOfOrNull { ownIgnores(st, g, it) } ?: g.ignores

    /** [g]'s rung: one per ignore, up to the plan's top (4, the mascot's own top face). */
    fun rung(plan: Plan, st: State, g: Group): Int = plan.rung(ignores(plan, st, g))

    /**
     * [g]'s mascot line, by the rule the nag screen shares (`src/app/plan.ts` nagLineIndex): its first open dose's
     * seed plus its ignores, on its rung. Under decision A each nag ends in at most one ignore, so the ignores number
     * its nags; both sides hold them, so the notification, the bubble and the nag screen never disagree.
     */
    fun line(plan: Plan, st: State, g: Group): String =
        plan.line(rung(plan, st, g), open(plan, st, g).firstOrNull()?.lineSeed ?: 0, ignores(plan, st, g))

    private fun event(st: State, key: String, kind: String, reason: String?, at: Long) = Event(++st.event, key, kind, reason, at)

    /**
     * [g]'s state brought to its doses: `done` with none open; `promised` while every open dose is under a promise, to
     * the earliest deadline; otherwise its ladder goes on for the doses under none. A dose under no promise that joins
     * a promised group (a new medication at that time, an undo) nags from the group's time, at once when it is past.
     */
    private fun settle(plan: Plan, st: State, g: Group) {
        val open = open(plan, st, g)
        if (open.isEmpty()) { g.state = "done"; return }
        if (g.state == "done") g.state = "upcoming"
        val deadlines = open.map { until(st, it) }
        if (deadlines.all { it != null }) {
            g.state = "promised"; g.until = deadlines.minOf { it!! }; g.nextAt = null
        } else if (g.state == "promised") {
            g.state = "upcoming"; g.until = null
        }
    }

    /** A state written before promises were per dose (version 1): a promised group's deadline becomes each of its open
     * doses' promise. */
    fun upgrade(plan: Plan, st: State) {
        if (st.v >= State.VERSION) return
        for (g in st.groups.values) {
            val u = g.until
            if (g.state == "promised" && u != null) for (d in open(plan, st, g)) st.doses[d.key]?.until = u
        }
        st.v = State.VERSION
    }

    /** The promises of [g] whose deadline came: those doses are nagged again, at the rung they had. */
    private fun release(plan: Plan, st: State, g: Group, now: Long) {
        for (d in open(plan, st, g)) st.doses[d.key]?.let { if ((it.until ?: Long.MAX_VALUE) <= now) it.until = null }
    }

    /** The earliest promise deadline among [g]'s open doses, or null with none. */
    private fun deadline(plan: Plan, st: State, g: Group): Long? = open(plan, st, g).mapNotNull { until(st, it) }.minOrNull()

    /**
     * A new plan merged into the state. A dose the plan names is open, but one resolved after the plan was made stays
     * resolved (the plan is older than the resolution); a dose or group the plan no longer names is dropped, so removing
     * a medication drops its open dose. A group keeps its place on the ladder; a promise the plan carries (restored from
     * the app's records) is taken by a dose that has none.
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

    /** How long before its time a dose may be confirmed (the app's early window, `src/domain` EARLY_WINDOW_MS). */
    const val EARLY_MS = 2 * 60 * 60_000L

    /**
     * The state lost (its file unreadable) but not the plan: the doses still more than [EARLY_MS] ahead go on, since
     * none of them could have been confirmed yet. The ones before are left out, as she may have taken them, until the
     * app's next plan, which a lost state always merges (its hash is unknown). The ladder of those ahead starts anew.
     */
    fun recover(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val ahead = plan.doses.filter { WallClock.epoch(it.group, zone) > now + EARLY_MS }
        for (d in ahead) st.doses.getOrPut(d.key) { DoseState(d.key) }
        for (name in ahead.map { it.group }.toSet()) st.groups.getOrPut(name) { Group(name) }
    }

    /** Doses past their `miss` lapse; a group with nothing open is done, and one with only promises left is promised. */
    fun lapse(plan: Plan, st: State, now: Long, out: MutableList<Event>, zone: ZoneId = ZoneId.systemDefault()) {
        for (d in plan.doses) {
            val s = st.doses[d.key] ?: continue
            if (s.state != "open" || plan.miss(d, zone) > now) continue
            s.state = "lapsed"; s.at = now; s.until = null
            out += event(st, d.key, "lapsed", null, now)
        }
        for (g in st.groups.values) if (g.state != "done") settle(plan, st, g)
    }

    /** The groups due a loud nag now: at their time, at their next nag, or at a promise's deadline of theirs. */
    fun due(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Group> = st.groups.values.filter { g ->
        open(plan, st, g).isNotEmpty() && (when (g.state) {
            "upcoming" -> WallClock.epoch(g.group, zone) <= now
            "pending", "ringing" -> g.nextAt?.let { it <= now } ?: false
            "promised" -> (g.until ?: 0) <= now
            else -> false
        } || (g.state != "done" && (deadline(plan, st, g) ?: Long.MAX_VALUE) <= now))
    }

    /** A loud nag for [groups], one number for all of them, for their doses under no promise (a promise whose deadline
     * came is over). Each rings until its ring's end ([ringOut]); the next nag is set as if it rang out unanswered, a
     * fallback should nothing end the ring. */
    fun fire(plan: Plan, st: State, groups: List<Group>, now: Long, out: MutableList<Event>): Int {
        if (groups.isEmpty()) return st.nag
        val nag = ++st.nag
        for (g in groups) {
            release(plan, st, g, now)
            if (nagged(plan, st, g).isEmpty()) { settle(plan, st, g); continue }
            g.state = "ringing"; g.nag = nag; g.lastFiredAt = now; g.until = null
            g.nextAt = now + plan.ladder.ringSec * 1000L + plan.interval(ignores(plan, st, g) + 1)
            for (d in nagged(plan, st, g)) out += event(st, d.key, "fired", null, now)
        }
        st.swiped = 0
        return nag
    }

    /** No ring can be stopped (notifications off): the groups wait silently, with no next nag, until notifications are
     * back ([unmute]) or they lapse. A promise whose deadline came is over: its dose waits with them. */
    fun mute(plan: Plan, st: State, groups: List<Group>, now: Long) {
        for (g in groups) {
            release(plan, st, g, now)
            if (nagged(plan, st, g).isEmpty()) { settle(plan, st, g); continue }
            g.state = "pending"; g.nextAt = null; g.until = null
        }
    }

    /** Notifications are back: every group [mute] silenced nags at once. */
    fun unmute(plan: Plan, st: State, now: Long) {
        for (g in st.groups.values) if (g.state == "pending" && g.nextAt == null && nagged(plan, st, g).isNotEmpty()) g.nextAt = now
    }

    /** When [g]'s ring ends unanswered: its fire plus the ladder's ring time. */
    fun ringEnd(plan: Plan, g: Group): Long? = g.lastFiredAt?.let { it + plan.ladder.ringSec * 1000L }

    /**
     * Every group still ringing at its ring's end ends unanswered: the nag's one ignore (`timeout`), at that end, as the
     * app's ladder counts it. The service's timer ends a ring it plays, and the alarm, set at the ring's end, ends one
     * nothing played: a start the system refused, a process that died mid-ring. A group of the same nag that was
     * opened meanwhile stopped ringing, and counts nothing here.
     */
    fun ringOut(plan: Plan, st: State, now: Long, out: MutableList<Event>) {
        for (g in st.groups.values) {
            if (g.state != "ringing") continue
            val end = ringEnd(plan, g) ?: continue
            if (end <= now) ignored(plan, st, g, "timeout", end, out)
        }
    }

    /**
     * Nag [nag] ignored by [reason]: once per nag, whatever ignores it after the first. Each of its groups climbs one
     * rung, and its next loud nag is counted from now.
     */
    fun ignore(plan: Plan, st: State, nag: Int, reason: String, now: Long, out: MutableList<Event>): Boolean {
        var any = false
        for (g in st.groups.values) if (g.nag == nag && ignored(plan, st, g, reason, now, out)) any = true
        return any
    }

    /**
     * The nags of [keys]' groups ignored by [reason]: the nag screen's "Not now", which shows one group, as the app's
     * `ignoreDose(group)` counts it. Each group's nag takes one ignore, whatever ignored it first; another group the
     * same nag rang for is left as it is.
     */
    fun ignore(plan: Plan, st: State, keys: Collection<String>, reason: String, now: Long, out: MutableList<Event>): Boolean {
        var any = false
        for (g in groupsOf(plan, st, keys)) if (ignored(plan, st, g, reason, now, out)) any = true
        return any
    }

    /**
     * [g]'s nag ignored at [at], unless it was already: its doses under no promise climb one rung, and its next loud nag
     * is counted from [at]. A promised dose sat it out: it keeps its rung (the app's ladder counts nothing during a
     * promise).
     */
    private fun ignored(plan: Plan, st: State, g: Group, reason: String, at: Long, out: MutableList<Event>): Boolean {
        if (g.nag == 0 || g.ignoredNag >= g.nag || (g.state != "ringing" && g.state != "pending")) return false
        g.ignores++; g.ignoredNag = g.nag; g.state = "pending"
        for (d in open(plan, st, g)) st.doses[d.key]?.let { if (it.until != null) it.behind++ }
        g.nextAt = at + plan.interval(ignores(plan, st, g))
        for (d in nagged(plan, st, g)) out += event(st, d.key, "ignored", reason, at)
        return true
    }

    /** The nag screen opened on [keys]: their ring stops, and nothing climbs; the next nag counts from the opening, as
     * the app's ladder does (`nagAfterOpen`). A nag of theirs that rang in front has been shown ([front]). */
    fun opened(plan: Plan, st: State, keys: Collection<String>, now: Long, out: MutableList<Event>) {
        for (g in groupsOf(plan, st, keys)) {
            if (g.state == "ringing") { g.state = "pending"; g.nextAt = now + plan.interval(ignores(plan, st, g) + 1) }
            for (d in nagged(plan, st, g)) if (d.key in keys) out += event(st, d.key, "opened", null, now)
            if (st.front != 0 && g.nag == st.front) st.front = 0
        }
    }

    /**
     * Decision 7: the lead group (the earliest) of the nag that rang with the app in front and the nag screen has not
     * shown yet, which the app reads (`nagState().front`); null once shown, answered, promised or missed, or after a loud
     * nag in the background.
     */
    fun front(plan: Plan, st: State): Group? = if (st.front == 0) null else st.groups.values
        .filter { it.nag == st.front && (it.state == "ringing" || it.state == "pending") && nagged(plan, st, it).isNotEmpty() }
        .minByOrNull { it.group }

    /**
     * [keys] resolved in the app: `taken` or `skipped` closes them; `promised` holds each of them, and only them, until
     * [until], capped at the moment that dose is missed; the last promise counts. Their groups follow: done with nothing
     * open, promised once every open dose is, and otherwise still nagging the doses under no promise.
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
        for (g in groupsOf(plan, st, keys)) if (g.state != "done") settle(plan, st, g)
    }

    private fun groupsOf(plan: Plan, st: State, keys: Collection<String>): List<Group> =
        plan.doses.filter { it.key in keys }.map { it.group }.distinct().mapNotNull { st.groups[it] }

    /** The stale notice's time, the plan's days after the app last sent one, or null once shown for that plan. */
    fun stale(plan: Plan, st: State): Long? =
        if (st.planAt == 0L || st.staleShown == st.planAt) null else st.planAt + plan.idle.afterDays * 24L * 60 * 60_000

    /** The one alarm to set: the earliest of every group's time, ring's end and next nag, every open dose's promise
     * deadline and lapse, and the stale notice. A time already past fires at once. */
    fun next(plan: Plan, st: State, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val times = ArrayList<Long>()
        for (g in st.groups.values) {
            if (open(plan, st, g).isEmpty()) continue
            when (g.state) {
                "upcoming" -> times += WallClock.epoch(g.group, zone)
                "ringing" -> (ringEnd(plan, g) ?: g.nextAt)?.let { times += it }
                "pending" -> g.nextAt?.let { times += it }
                "promised" -> g.until?.let { times += it }
            }
        }
        for (d in plan.doses) st.doses[d.key]?.takeIf { it.state == "open" }?.let { s ->
            times += plan.miss(d, zone)
            if (st.groups[d.group].let { it != null && it.state != "done" }) s.until?.let { times += it }
        }
        stale(plan, st)?.let { times += it }
        return times.minOrNull()
    }

    /** Each planned dose's state as the app reads it (`nagState`): a dose under a promise is `promised` to its own
     * deadline, while the others of its group show the group's ladder; its ignores and rung are its own. */
    fun doses(plan: Plan, st: State): List<org.json.JSONObject> = plan.doses.map { d ->
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
        val ignores = g?.let { ownIgnores(st, it, d) } ?: 0
        org.json.JSONObject().put("key", d.key).put("state", state).put("ignores", ignores).put("rung", plan.rung(ignores))
            .put("nextAt", (if (state == "promised") promise ?: g?.until else g?.nextAt.takeIf { state == "pending" || state == "ringing" }) ?: org.json.JSONObject.NULL)
            .put("lastFiredAt", g?.lastFiredAt ?: org.json.JSONObject.NULL)
    }
}
