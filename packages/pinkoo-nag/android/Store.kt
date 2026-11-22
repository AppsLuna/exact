// What the engine keeps, in device-protected storage so the receivers read it before the first unlock after a boot: the
// last plan, each dose time's place on its thread of steps, and the events the app has not acknowledged yet.
package pinkoo.nag

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * A dose time's thread (`docs/product.md` › Nagging): one notification, one step and one count of reminders for every
 * dose due at the same time. Only time moves it along its steps; nothing she does makes it louder.
 */
internal class Group(
    val group: String,
    /** `upcoming` (its time not come), `pending` (a step fired, the next one waits), `ringing` (its alarm rings),
     * `promised` (every open dose of it under a promise, [until] the earliest deadline) or `done`. */
    var state: String = "upcoming",
    /** The last step of the thread that fired (`NOTIFY` to `ALARM`, then `REPEAT`); a promise's deadline doesn't move
     * it. Null before the dose time's notification. */
    var step: Step? = null,
    /** The last post's own step: [step], or `DEADLINE` at a promise's deadline. */
    var last: Step? = null,
    /** The repeats that fired, up to the plan's (3 at most). */
    var repeats: Int = 0,
    /** The reminders that reached her: steps, repeats and deadlines that fired. */
    var fires: Int = 0,
    /** The post (a fire's number) the group last fired in. */
    var nag: Int = 0,
    /** When its next step is due: one gap after the step before it, or after her "Agora não" when that is later. Null
     * before the dose time (its time is the group's) and with no step left. */
    var nextAt: Long? = null,
    var until: Long? = null,
    var lastFiredAt: Long? = null,
    /** She swiped its post away, or said "Agora não": no post brings it back until its next step. */
    var dismissed: Boolean = false,
    /** The bubble was sent to the nest during this step: it stays away until the next one, across a process death. */
    var nested: Boolean = false,
    /** "Abrir quando eu desbloquear" is done for this dose time: the nag screen opened, or "Agora não". */
    var unlocked: Boolean = false,
    /** A step came due with no notification able to reach her: it waits, silently, for them to come back. */
    var muted: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("group", group).put("state", state).put("step", step?.name ?: JSONObject.NULL)
        .put("last", last?.name ?: JSONObject.NULL).put("repeats", repeats).put("fires", fires).put("nag", nag)
        .put("nextAt", nextAt ?: JSONObject.NULL).put("until", until ?: JSONObject.NULL).put("lastFiredAt", lastFiredAt ?: JSONObject.NULL)
        .put("dismissed", dismissed).put("nested", nested).put("unlocked", unlocked).put("muted", muted)

    companion object {
        fun from(o: JSONObject) = Group(o.getString("group"), o.optString("state", "upcoming"), step(o, "step"), step(o, "last"),
            o.optInt("repeats"), o.optInt("fires"), o.optInt("nag"), long(o, "nextAt"), long(o, "until"), long(o, "lastFiredAt"),
            o.optBoolean("dismissed"), o.optBoolean("nested"), o.optBoolean("unlocked"), o.optBoolean("muted")).also {
                // A state of version 2 (the ladder of ignores): its counts, which [Rules.upgrade] maps to a step.
                it.ignores = o.optInt("ignores"); it.ignoredNag = o.optInt("ignoredNag")
            }

        private fun step(o: JSONObject, k: String): Step? = if (o.isNull(k)) null else runCatching { Step.valueOf(o.optString(k)) }.getOrNull()
    }

    /** Version 2's ignore count and last ignored nag, read only to upgrade it. Not kept. */
    var ignores = 0
    var ignoredNag = 0
}

/**
 * A dose's own outcome: `open`, `resolved` (taken, skipped) or `lapsed` (missed). While open, [until] is its pinky
 * promise's deadline (one per medication: the group's other doses keep their thread), [behind] the group's reminders it
 * sat out under promises, so it keeps its own count, and [full] whether it has had its full screen (once per dose).
 */
internal class DoseState(val key: String, var state: String = "open", var at: Long? = null, var until: Long? = null, var behind: Int = 0,
    var full: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("key", key).put("state", state).put("at", at ?: JSONObject.NULL)
        .put("until", until ?: JSONObject.NULL).put("behind", behind).put("full", full)
    companion object {
        fun from(o: JSONObject) = DoseState(o.getString("key"), o.optString("state", "open"), long(o, "at"), long(o, "until"), o.optInt("behind"),
            o.optBoolean("full"))
    }
}

internal class State(
    val groups: LinkedHashMap<String, Group> = LinkedHashMap(),
    val doses: LinkedHashMap<String, DoseState> = LinkedHashMap(),
    /** The last post's number. */
    var nag: Int = 0,
    var event: Long = 0,
    /** The plan's text the state was merged from, which a plan sent again unchanged skips. */
    var planHash: Int = 0,
    /** When the app last sent a plan or came to the front: the stale notice comes the plan's idle days after it. */
    var planAt: Long = 0,
    var staleShown: Long = 0,
    /** The local date the low-stock notice was last posted on. */
    var stockDay: String = "",
    /** The post the notification's "Agora não" answered, and when its confirmation ("Combinado: …") goes: until then no
     * refresh touches it. */
    var confirm: Int = 0,
    var confirmUntil: Long = 0,
    /** The promise deadline whose notification the person dismissed: not shown again for it. */
    var promiseDismissed: Long = 0,
    /** The last post that fired with the app in front: no heads-up and no full-screen intent to its end, across a
     * process death, so leaving the app or a restart never pops it up (decision 17). */
    var quiet: Int = 0,
    /** The post that fired with the app in front and that the nag screen has not shown yet, or 0: the app reads it
     * (`nagState().front`) and shows the banner, or opens the nag screen for the full screen and the alarm (decision
     * 7, item 17). Kept across a process death; a later post replaces it, and one in the background clears it. */
    var front: Int = 0,
    /** The last post that came due with the app in front while no notification could reach her (notifications off):
     * it showed nothing, and the app opens the nag screen for it all the same (item 20, `?via=silent`). */
    var silent: Int = 0,
    /** When [silent] came due: the front's `at`, as a post that fired has its fire's. */
    var silentAt: Long = 0,
    /** Made anew, not read: there was no state file, or it could not be read. Not kept. */
    val fresh: Boolean = false,
    /** The state's version: 3 since the thread of steps ([Rules.upgrade] brings a 1 or a 2 up). */
    var v: Int = VERSION,
) {
    /** Version 2's swiped nag and closed bubble, read only to upgrade them. Not kept. */
    var swiped = 0
    var bubbleClosed = 0

    fun json(): JSONObject = JSONObject().put("v", v)
        .put("groups", JSONArray(groups.values.map { it.json() })).put("doses", JSONArray(doses.values.map { it.json() }))
        .put("nag", nag).put("event", event).put("planHash", planHash).put("planAt", planAt).put("staleShown", staleShown)
        .put("stockDay", stockDay).put("confirm", confirm).put("confirmUntil", confirmUntil).put("promiseDismissed", promiseDismissed)
        .put("quiet", quiet).put("front", front).put("silent", silent).put("silentAt", silentAt)

    companion object {
        const val VERSION = 3

        fun from(o: JSONObject): State {
            val s = State(nag = o.optInt("nag"), event = o.optLong("event"), planHash = o.optInt("planHash"), planAt = o.optLong("planAt"),
                staleShown = o.optLong("staleShown"), stockDay = o.optString("stockDay"), confirm = o.optInt("confirm"),
                confirmUntil = o.optLong("confirmUntil"), promiseDismissed = o.optLong("promiseDismissed"), quiet = o.optInt("quiet"),
                front = o.optInt("front"), silent = o.optInt("silent"), silentAt = o.optLong("silentAt"), v = o.optInt("v", 1))
            s.swiped = o.optInt("swiped"); s.bubbleClosed = o.optInt("bubbleClosed")
            o.optJSONArray("groups")?.let { a -> for (i in 0 until a.length()) Group.from(a.getJSONObject(i)).let { s.groups[it.group] = it } }
            o.optJSONArray("doses")?.let { a -> for (i in 0 until a.length()) DoseState.from(a.getJSONObject(i)).let { s.doses[it.key] = it } }
            return s
        }
    }
}

/** What happened to a dose that the app records in its history: `fired` (with its [step]), `ignored` (with its
 * reason: `notNow`, her "Agora não", which moves the next step and counts nothing; `timeout`, the alarm rang out),
 * `opened`, `lapsed`. */
internal class Event(val id: Long, val key: String, val kind: String, val reason: String?, val at: Long, val step: Step? = null) {
    fun json(): JSONObject = JSONObject().put("id", id.toString()).put("key", key).put("kind", kind)
        .put("reason", reason ?: JSONObject.NULL).put("step", step?.let { Rules.word(it) } ?: JSONObject.NULL).put("at", at)
    companion object {
        fun from(o: JSONObject) = Event(o.optString("id").toLongOrNull() ?: 0, o.optString("key"), o.optString("kind"),
            if (o.isNull("reason")) null else o.optString("reason"), o.optLong("at"),
            if (o.isNull("step")) null else Rules.step(o.optString("step")))
    }
}

private fun long(o: JSONObject, k: String): Long? = if (!o.has(k) || o.isNull(k)) null else o.optLong(k)

internal object Store {
    /** Events kept unacknowledged at most: the oldest go first. */
    const val EVENTS = 1000

    private fun dir(c: Context): File = File(c.createDeviceProtectedStorageContext().filesDir, "pinkoo.nag").apply { mkdirs() }
    private fun file(c: Context, name: String) = AtomicFile(File(dir(c), name))
    private fun read(c: Context, name: String): String? = runCatching { String(file(c, name).readFully()) }.getOrNull()
    private fun write(c: Context, name: String, text: String) {
        val f = file(c, name)
        val out = f.startWrite()
        try { out.write(text.toByteArray()); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

    @Synchronized fun planText(c: Context): String? = read(c, "plan.json")
    @Synchronized fun plan(c: Context): Plan? = planText(c)?.let { runCatching { Plan.parse(JSONObject(it)) }.getOrNull() }
    @Synchronized fun savePlan(c: Context, text: String) = write(c, "plan.json", text)

    /**
     * The state, or one made anew when there is none or it cannot be read. The app records each event once by its id
     * (`nag_log`, `ON CONFLICT(id) DO NOTHING`), so a state made anew numbers its events above every id one before it
     * could have handed out: from the wall clock in milliseconds (the engine records far fewer than one event a
     * millisecond) and from the last id still waiting in the events file, whichever is higher.
     */
    @Synchronized fun state(c: Context): State = read(c, "state.json")?.let { runCatching { State.from(JSONObject(it)) }.getOrNull() }
        ?: State(event = maxOf(Engine.clock(), events(c).maxOfOrNull { it.id } ?: 0L), fresh = true)
    @Synchronized fun save(c: Context, s: State) = write(c, "state.json", s.json().toString())

    @Synchronized fun events(c: Context): List<Event> {
        val f = File(dir(c), "events.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { runCatching { Event.from(JSONObject(it)) }.getOrNull() }
    }

    /** Appends [events], keeping the newest [EVENTS]. */
    @Synchronized fun append(c: Context, events: List<Event>) {
        if (events.isEmpty()) return
        val f = File(dir(c), "events.jsonl")
        FileOutputStream(f, true).use { out -> for (e in events) out.write((e.json().toString() + "\n").toByteArray()) }
        val all = events(c)
        if (all.size > EVENTS) rewrite(c, all.takeLast(EVENTS))
    }

    /** Drops the events up to [through], which the app has recorded; whether it dropped any. */
    @Synchronized fun ack(c: Context, through: Long): Boolean {
        val all = events(c)
        val kept = all.filter { it.id > through }
        if (kept.size != all.size) rewrite(c, kept)
        return kept.size != all.size
    }

    private fun rewrite(c: Context, events: List<Event>) = write(c, "events.jsonl", events.joinToString("") { it.json().toString() + "\n" })

    @Synchronized fun clear(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }
}
