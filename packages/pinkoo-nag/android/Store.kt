// What the engine keeps, in device-protected storage so the receivers read it before the first unlock after a boot: the
// last plan, the ladder's state per group, and the events the app has not acknowledged yet.
package pinkoo.nag

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A group's place on the ladder: one nag, one ring and one ignore count for every dose due at the same time. */
internal class Group(
    val group: String,
    /** `upcoming`, `ringing`, `pending`, `promised` or `done`. */
    var state: String = "upcoming",
    var ignores: Int = 0,
    /** The last nag (a fire's number) the group rang in, and the last one counted as ignored. */
    var nag: Int = 0,
    var ignoredNag: Int = 0,
    /** The next loud nag while pending; while ringing, the one that follows should the process die mid-ring. */
    var nextAt: Long? = null,
    var until: Long? = null,
    var lastFiredAt: Long? = null,
) {
    fun json(): JSONObject = JSONObject().put("group", group).put("state", state).put("ignores", ignores).put("nag", nag)
        .put("ignoredNag", ignoredNag).put("nextAt", nextAt ?: JSONObject.NULL).put("until", until ?: JSONObject.NULL)
        .put("lastFiredAt", lastFiredAt ?: JSONObject.NULL)

    companion object {
        fun from(o: JSONObject) = Group(o.getString("group"), o.optString("state", "upcoming"), o.optInt("ignores"), o.optInt("nag"),
            o.optInt("ignoredNag"), long(o, "nextAt"), long(o, "until"), long(o, "lastFiredAt"))
    }
}

/** A dose's own outcome: `open`, `resolved` (taken, skipped) or `lapsed` (missed). */
internal class DoseState(val key: String, var state: String = "open", var at: Long? = null) {
    fun json(): JSONObject = JSONObject().put("key", key).put("state", state).put("at", at ?: JSONObject.NULL)
    companion object { fun from(o: JSONObject) = DoseState(o.getString("key"), o.optString("state", "open"), long(o, "at")) }
}

internal class State(
    val groups: LinkedHashMap<String, Group> = LinkedHashMap(),
    val doses: LinkedHashMap<String, DoseState> = LinkedHashMap(),
    /** The last nag's number. */
    var nag: Int = 0,
    var event: Long = 0,
    /** The plan's text the state was merged from, which a plan sent again unchanged skips. */
    var planHash: Int = 0,
    /** When the app last sent a plan: the stale notice comes the plan's idle days after it. */
    var planAt: Long = 0,
    var staleShown: Long = 0,
    /** The local date the low-stock notice was last posted on. */
    var stockDay: String = "",
    /** The nag whose waiting notification was swiped while nothing brings it back. */
    var swiped: Int = 0,
    /** The promise deadline whose notification the person dismissed: not shown again for it. */
    var promiseDismissed: Long = 0,
) {
    fun json(): JSONObject = JSONObject().put("v", 1)
        .put("groups", JSONArray(groups.values.map { it.json() })).put("doses", JSONArray(doses.values.map { it.json() }))
        .put("nag", nag).put("event", event).put("planHash", planHash).put("planAt", planAt).put("staleShown", staleShown)
        .put("stockDay", stockDay).put("swiped", swiped).put("promiseDismissed", promiseDismissed)

    companion object {
        fun from(o: JSONObject): State {
            val s = State(nag = o.optInt("nag"), event = o.optLong("event"), planHash = o.optInt("planHash"), planAt = o.optLong("planAt"),
                staleShown = o.optLong("staleShown"), stockDay = o.optString("stockDay"), swiped = o.optInt("swiped"),
                promiseDismissed = o.optLong("promiseDismissed"))
            o.optJSONArray("groups")?.let { a -> for (i in 0 until a.length()) Group.from(a.getJSONObject(i)).let { s.groups[it.group] = it } }
            o.optJSONArray("doses")?.let { a -> for (i in 0 until a.length()) DoseState.from(a.getJSONObject(i)).let { s.doses[it.key] = it } }
            return s
        }
    }
}

/** What happened to a dose that the app records in its history: `fired`, `ignored` (with its reason), `opened`, `lapsed`. */
internal class Event(val id: Long, val key: String, val kind: String, val reason: String?, val at: Long) {
    fun json(): JSONObject = JSONObject().put("id", id.toString()).put("key", key).put("kind", kind)
        .put("reason", reason ?: JSONObject.NULL).put("at", at)
    companion object {
        fun from(o: JSONObject) = Event(o.optString("id").toLongOrNull() ?: 0, o.optString("key"), o.optString("kind"),
            if (o.isNull("reason")) null else o.optString("reason"), o.optLong("at"))
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

    @Synchronized fun state(c: Context): State = read(c, "state.json")?.let { runCatching { State.from(JSONObject(it)) }.getOrNull() } ?: State()
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

    /** Drops the events up to [through], which the app has recorded. */
    @Synchronized fun ack(c: Context, through: Long) {
        val all = events(c)
        val kept = all.filter { it.id > through }
        if (kept.size != all.size) rewrite(c, kept)
    }

    private fun rewrite(c: Context, events: List<Event>) = write(c, "events.jsonl", events.joinToString("") { it.json().toString() + "\n" })

    @Synchronized fun clear(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }
}
