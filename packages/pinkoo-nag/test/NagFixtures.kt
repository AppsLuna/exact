package pinkoo.nag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Plans as the data module sends them, for the tests. Times are local wall-clock strings in the JVM's zone, which
 * each test pins. */
internal object NagFixtures {
    /** The mascot's lines as `src/mascot/lines.ts` counts them: three on rung 0, four on each rung above, up to 4. */
    val lines: List<List<String>> = listOf(
        listOf("a0", "b0", "c0"), listOf("a1", "b1", "c1", "d1"), listOf("a2", "b2", "c2", "d2"),
        listOf("a3", "b3", "c3", "d3"), listOf("a4", "b4", "c4", "d4"),
    )

    fun texts(): JSONObject = JSONObject().apply {
        for (k in listOf("take", "promise", "notNow", "publicBody", "bubble")) put(k, k)
        put("title", "Time for {names}").put("body", "at {time}").put("pending", "{names} waits").put("promiseChip", "{names} by {time}")
        put("publicTitle", "Reminder").put("channelNag", "Reminders").put("channelPending", "Waiting").put("channelNotice", "Notices")
        put("channelRing", "Reminders with sound")
        put("lines", JSONArray(lines.map { JSONArray(it) }))
    }

    /** The rungs `src/app/plan.ts` builds with every nuisance on: one per ignore, 0 to 4; the top adds nothing but the
     * mascot's own face and lines. */
    fun rungs(): JSONArray = JSONArray()
        .put(JSONObject().put("ignores", 0).put("add", JSONArray(listOf("ring", "fullScreen"))))
        .put(JSONObject().put("ignores", 1).put("add", JSONArray(listOf("returning"))))
        .put(JSONObject().put("ignores", 2).put("add", JSONArray(listOf("bubble"))))
        .put(JSONObject().put("ignores", 3).put("add", JSONArray(listOf("unlock"))))
        .put(JSONObject().put("ignores", 4).put("add", JSONArray()))

    fun dose(key: String, group: String, miss: String, name: String = key, promiseUntil: Long? = null, seed: Long = 0): JSONObject =
        JSONObject().put("key", key).put("group", group).put("name", name).put("miss", miss).put("promiseUntil", promiseUntil ?: JSONObject.NULL)
            .put("lineSeed", seed)

    /** A mascot of one circle in [fill], so a test can tell the rungs' drawings apart. */
    fun mascot(fill: String): JSONObject = JSONObject().put("viewBox", JSONArray(listOf(0, 0, 200, 200)))
        .put("parts", JSONArray().put(JSONObject().put("t", "circle").put("cx", 100).put("cy", 100).put("r", 80).put("fill", fill)))

    fun plan(vararg doses: JSONObject, made: Long = 0, first: Int = 20, repeat: Int = 15, ring: Int = 60, stock: Boolean = false): JSONObject =
        JSONObject().put("v", 1).put("made", made).put("zone", "America/Sao_Paulo").put("locale", "en")
            .put("ladder", JSONObject().put("firstMin", first).put("repeatMin", repeat).put("ringSec", ring))
            .put("rungs", rungs()).put("liveUpdate", true).put("volume", JSONArray(listOf(0.6, 0.8, 1.0))).put("doses", JSONArray(doses.toList()))
            .put("texts", texts()).put("mascot", JSONObject().put("viewBox", JSONArray(listOf(0, 0, 200, 200))).put("parts", JSONArray()))
            .put("mascots", JSONArray())
            .put("stock", if (stock) JSONObject().put("title", "Low").put("body", "Pill A: 3 left") else JSONObject.NULL)
            .put("idle", JSONObject().put("afterDays", 6).put("title", "Open the app").put("body", "to keep reminders"))

    fun clear(c: Context) = Store.clear(c)
}
