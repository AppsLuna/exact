// The ladder's rules over the plan and the state, with no Android in them: what is due, what a fire, an ignore, an
// opening and a resolution change, and when the next alarm is. Every outcome a dose has is an event the app records.
package pinkoo.nag

import java.time.ZoneId

internal object Rules {
    /** The plan's doses of [g] still open. */
    fun open(plan: Plan, st: State, g: Group): List<Dose> = plan.doses(g.group).filter { st.doses[it.key]?.state == "open" }

    /** [g]'s rung: one per ignore, up to the plan's top (4, the mascot's own top face). */
    fun rung(plan: Plan, g: Group): Int = plan.rung(g.ignores)

    /**
     * [g]'s mascot line, by the rule the nag screen shares (`src/app/plan.ts` nagLineIndex): its first open dose's
     * seed plus its ignores, on its rung. Under decision A each nag ends in at most one ignore, so the ignores number
     * its nags; both sides hold them, so the notification, the bubble and the nag screen never disagree.
     */
    fun line(plan: Plan, st: State, g: Group): String = plan.line(rung(plan, g), open(plan, st, g).firstOrNull()?.lineSeed ?: 0, g.ignores)

    private fun event(st: State, key: String, kind: String, reason: String?, at: Long) = Event(++st.event, key, kind, reason, at)

    /**
     * A new plan merged into the state. A dose the plan names is open, but one resolved after the plan was made stays
     * resolved (the plan is older than the resolution); a dose or group the plan no longer names is dropped, so removing
     * a medication drops its open dose. A group keeps its place on the ladder; a promise the plan carries (restored from
     * the app's records) is taken when the group has none.
     */
    fun merge(plan: Plan, st: State, now: Long) {
        val keys = plan.doses.map { it.key }.toSet()
        st.doses.keys.retainAll(keys)
        for (d in plan.doses) {
            val s = st.doses.getOrPut(d.key) { DoseState(d.key) }
            if (s.state == "resolved" && (s.at ?: 0) < plan.made) { s.state = "open"; s.at = null }
        }
        val groups = plan.doses.map { it.group }.toSet()
        st.groups.keys.retainAll(groups)
        for (name in groups) {
            val g = st.groups.getOrPut(name) { Group(name) }
            val promised = plan.doses(name).mapNotNull { it.promiseUntil }.maxOrNull()
            if (promised != null && promised > now && g.state != "promised" && g.state != "done") {
                g.state = "promised"; g.until = promised; g.nextAt = null
            }
            if (open(plan, st, g).isEmpty()) g.state = "done" else if (g.state == "done") g.state = "upcoming"
        }
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

    /** Doses past their `miss` lapse; a group with nothing open is done. */
    fun lapse(plan: Plan, st: State, now: Long, out: MutableList<Event>, zone: ZoneId = ZoneId.systemDefault()) {
        for (d in plan.doses) {
            val s = st.doses[d.key] ?: continue
            if (s.state != "open" || WallClock.epoch(d.miss, zone) > now) continue
            s.state = "lapsed"; s.at = now
            out += event(st, d.key, "lapsed", null, now)
        }
        for (g in st.groups.values) if (g.state != "done" && open(plan, st, g).isEmpty()) g.state = "done"
    }

    /** The groups due a loud nag now: at their time, at their next nag, or at their promise's deadline. */
    fun due(plan: Plan, st: State, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Group> = st.groups.values.filter { g ->
        open(plan, st, g).isNotEmpty() && when (g.state) {
            "upcoming" -> WallClock.epoch(g.group, zone) <= now
            "pending", "ringing" -> g.nextAt?.let { it <= now } ?: false
            "promised" -> (g.until ?: 0) <= now
            else -> false
        }
    }

    /** A loud nag for [groups], one number for all of them. Each rings until its ring's end ([ringOut]); the next nag
     * is set as if it rang out unanswered, a fallback should nothing end the ring. */
    fun fire(plan: Plan, st: State, groups: List<Group>, now: Long, out: MutableList<Event>): Int {
        if (groups.isEmpty()) return st.nag
        val nag = ++st.nag
        for (g in groups) {
            g.state = "ringing"; g.nag = nag; g.lastFiredAt = now; g.until = null
            g.nextAt = now + plan.ladder.ringSec * 1000L + plan.interval(g.ignores + 1)
            for (d in open(plan, st, g)) out += event(st, d.key, "fired", null, now)
        }
        st.swiped = 0
        return nag
    }

    /** No ring can be stopped (notifications off): the groups wait silently, with no next nag, until notifications are
     * back ([unmute]) or they lapse. */
    fun mute(groups: List<Group>) { for (g in groups) { g.state = "pending"; g.nextAt = null } }

    /** Notifications are back: every group [mute] silenced nags at once. */
    fun unmute(plan: Plan, st: State, now: Long) {
        for (g in st.groups.values) if (g.state == "pending" && g.nextAt == null && open(plan, st, g).isNotEmpty()) g.nextAt = now
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

    /** [g]'s nag ignored at [at], unless it was already: it climbs one rung, its next loud nag counted from [at]. */
    private fun ignored(plan: Plan, st: State, g: Group, reason: String, at: Long, out: MutableList<Event>): Boolean {
        if (g.nag == 0 || g.ignoredNag >= g.nag || (g.state != "ringing" && g.state != "pending")) return false
        g.ignores++; g.ignoredNag = g.nag; g.state = "pending"
        g.nextAt = at + plan.interval(g.ignores)
        for (d in open(plan, st, g)) out += event(st, d.key, "ignored", reason, at)
        return true
    }

    /** The nag screen opened on [keys]: their ring stops, and nothing climbs; the next nag counts from the opening, as
     * the app's ladder does (`nagAfterOpen`). A nag of theirs that rang in front has been shown ([front]). */
    fun opened(plan: Plan, st: State, keys: Collection<String>, now: Long, out: MutableList<Event>) {
        for (g in groupsOf(plan, st, keys)) {
            if (g.state == "ringing") { g.state = "pending"; g.nextAt = now + plan.interval(g.ignores + 1) }
            for (d in open(plan, st, g)) if (d.key in keys) out += event(st, d.key, "opened", null, now)
            if (st.front != 0 && g.nag == st.front) st.front = 0
        }
    }

    /**
     * Decision 7: the lead group (the earliest) of the nag that rang with the app in front and the nag screen has not
     * shown yet, which the app reads (`nagState().front`); null once shown, answered, promised or missed, or after a loud
     * nag in the background.
     */
    fun front(plan: Plan, st: State): Group? = if (st.front == 0) null else st.groups.values
        .filter { it.nag == st.front && (it.state == "ringing" || it.state == "pending") && open(plan, st, it).isNotEmpty() }
        .minByOrNull { it.group }

    /**
     * [keys] resolved in the app: `taken` or `skipped` closes them; `promised` holds their groups until [until], capped
     * at the earliest moment one of their doses is missed, and the last promise counts.
     */
    fun resolve(plan: Plan, st: State, keys: Collection<String>, kind: String, until: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val groups = groupsOf(plan, st, keys)
        if (kind == "promised") {
            for (g in groups) {
                if (g.state == "done") continue
                val miss = open(plan, st, g).minOfOrNull { WallClock.epoch(it.miss, zone) } ?: continue
                g.state = "promised"; g.until = minOf(until ?: now, miss); g.nextAt = null
            }
            return
        }
        for (k in keys) st.doses[k]?.takeIf { it.state == "open" }?.let { it.state = "resolved"; it.at = now }
        for (g in groups) if (open(plan, st, g).isEmpty()) g.state = "done"
    }

    private fun groupsOf(plan: Plan, st: State, keys: Collection<String>): List<Group> =
        plan.doses.filter { it.key in keys }.map { it.group }.distinct().mapNotNull { st.groups[it] }

    /** The stale notice's time, the plan's days after the app last sent one, or null once shown for that plan. */
    fun stale(plan: Plan, st: State): Long? =
        if (st.planAt == 0L || st.staleShown == st.planAt) null else st.planAt + plan.idle.afterDays * 24L * 60 * 60_000

    /** The one alarm to set: the earliest of every group's time, ring's end, next nag or deadline, every open dose's
     * lapse, and the stale notice. A time already past fires at once. */
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
        for (d in plan.doses) if (st.doses[d.key]?.state == "open") times += WallClock.epoch(d.miss, zone)
        stale(plan, st)?.let { times += it }
        return times.minOrNull()
    }

    /** Each planned dose's state as the app reads it (`nagState`). */
    fun doses(plan: Plan, st: State): List<org.json.JSONObject> = plan.doses.map { d ->
        val g = st.groups[d.group]
        val own = st.doses[d.key]?.state ?: "open"
        val state = when {
            own != "open" -> own
            g == null || g.state == "done" -> "upcoming"
            else -> g.state
        }
        org.json.JSONObject().put("key", d.key).put("state", state).put("ignores", g?.ignores ?: 0).put("rung", g?.let { rung(plan, it) } ?: 0)
            .put("nextAt", (if (state == "promised") g?.until else g?.nextAt.takeIf { state == "pending" || state == "ringing" }) ?: org.json.JSONObject.NULL)
            .put("lastFiredAt", g?.lastFiredAt ?: org.json.JSONObject.NULL)
    }
}
