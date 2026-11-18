// The ladder's rules over the plan and the state, with no Android in them: what is due, what a fire, an ignore, an
// opening and a resolution change, and when the next alarm is. Every outcome a dose has is an event the app records.
package pinkoo.nag

import java.time.ZoneId

internal object Rules {
    /** The plan's doses of [g] still open. */
    fun open(plan: Plan, st: State, g: Group): List<Dose> = plan.doses(g.group).filter { st.doses[it.key]?.state == "open" }

    fun rung(plan: Plan, g: Group): Int = plan.rung(g.ignores)

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

    /** A loud nag for [groups], one number for all of them. The next nag is set as if it rang out unanswered, which is
     * what happens should the process die mid-ring. */
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

    /** No ring can be stopped (notifications off): the groups wait silently, with no next nag, until they lapse or the app
     * sends a plan again. */
    fun mute(groups: List<Group>) { for (g in groups) { g.state = "pending"; g.nextAt = null } }

    /**
     * Nag [nag] ignored by [reason]: once per nag, whatever ignores it after the first. Each of its groups climbs one
     * rung, and its next loud nag is counted from now.
     */
    fun ignore(plan: Plan, st: State, nag: Int, reason: String, now: Long, out: MutableList<Event>): Boolean {
        var any = false
        for (g in st.groups.values) {
            if (g.nag != nag || g.ignoredNag >= nag || (g.state != "ringing" && g.state != "pending")) continue
            g.ignores++; g.ignoredNag = nag; g.state = "pending"
            g.nextAt = now + plan.interval(g.ignores)
            for (d in open(plan, st, g)) out += event(st, d.key, "ignored", reason, now)
            any = true
        }
        return any
    }

    /** The nag screen opened on [keys]: their ring stops, and nothing climbs. */
    fun opened(plan: Plan, st: State, keys: Collection<String>, now: Long, out: MutableList<Event>) {
        for (g in groupsOf(plan, st, keys)) {
            if (g.state == "ringing") g.state = "pending"
            for (d in open(plan, st, g)) if (d.key in keys) out += event(st, d.key, "opened", null, now)
        }
    }

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
        if (st.planAt == 0L || st.staleShown == st.planAt) null else st.planAt + plan.idle.afterDays * 24 * 60 * 60_000L

    /** The one alarm to set: the earliest of every group's time, next nag or deadline, every open dose's lapse, and the
     * stale notice. A time already past fires at once. */
    fun next(plan: Plan, st: State, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val times = ArrayList<Long>()
        for (g in st.groups.values) {
            if (open(plan, st, g).isEmpty()) continue
            when (g.state) {
                "upcoming" -> times += WallClock.epoch(g.group, zone)
                "pending", "ringing" -> g.nextAt?.let { times += it }
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
