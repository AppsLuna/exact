package pinkoo.nag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Plans as the data module sends them, for the tests. Times are local wall-clock strings in the JVM's zone, which
 * each test pins. */
internal object NagFixtures {
    fun texts(): JSONObject = JSONObject().apply {
        for (k in listOf("take", "promise", "notNow", "publicBody", "bubble")) put(k, k)
        put("title", "Time for {names}").put("body", "at {time}").put("pending", "{names} waits").put("promiseChip", "{names} by {time}")
        put("publicTitle", "Reminder").put("channelNag", "Reminders").put("channelPending", "Waiting").put("channelNotice", "Notices")
        put("lines", JSONArray(listOf(JSONArray(listOf("a0", "b0")), JSONArray(listOf("a1")), JSONArray(listOf("a2")), JSONArray(listOf("a3")))))
    }

    fun rungs(): JSONArray = JSONArray()
        .put(JSONObject().put("ignores", 0).put("add", JSONArray(listOf("ring", "fullScreen"))))
        .put(JSONObject().put("ignores", 1).put("add", JSONArray(listOf("returning"))))
        .put(JSONObject().put("ignores", 2).put("add", JSONArray(listOf("bubble"))))
        .put(JSONObject().put("ignores", 3).put("add", JSONArray(listOf("unlock"))))

    fun dose(key: String, group: String, miss: String, name: String = key, promiseUntil: Long? = null): JSONObject =
        JSONObject().put("key", key).put("group", group).put("name", name).put("miss", miss).put("promiseUntil", promiseUntil ?: JSONObject.NULL)
            .put("lineSeed", 0)

    fun plan(vararg doses: JSONObject, made: Long = 0, first: Int = 20, repeat: Int = 15, ring: Int = 60, stock: Boolean = false): JSONObject =
        JSONObject().put("v", 1).put("made", made).put("zone", "America/Sao_Paulo").put("locale", "en")
            .put("ladder", JSONObject().put("firstMin", first).put("repeatMin", repeat).put("ringSec", ring))
            .put("rungs", rungs()).put("liveUpdate", true).put("volume", JSONArray(listOf(0.6, 0.8, 1.0))).put("doses", JSONArray(doses.toList()))
            .put("texts", texts()).put("mascot", JSONObject().put("viewBox", JSONArray(listOf(0, 0, 200, 200))).put("parts", JSONArray()))
            .put("mascots", JSONArray())
            .put("stock", if (stock) JSONObject().put("title", "Low").put("body", "Pill A: 3 left") else JSONObject.NULL)
            .put("idle", JSONObject().put("afterDays", 7).put("title", "Open the app").put("body", "to keep reminders"))

    fun clear(c: Context) = Store.clear(c)
}
