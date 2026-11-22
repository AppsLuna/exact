package pinkoo.nag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Plans as the data module sends them (shape v2), for the tests. Times are local wall-clock strings in the JVM's
 * zone, which each test pins. */
internal object NagFixtures {
    /** The mascot's lines as `src/mascot/lines.ts` counts them: three on rung 0, four on each rung above, up to 4. */
    val lines: List<List<String>> = listOf(
        listOf("a0", "b0", "c0"), listOf("a1", "b1", "c1", "d1"), listOf("a2", "b2", "c2", "d2"),
        listOf("a3", "b3", "c3", "d3"), listOf("a4", "b4", "c4", "d4"),
    )

    fun texts(): JSONObject = JSONObject().apply {
        for (k in listOf("take", "promise", "notNow", "publicBody", "bubble", "stop", "service", "alarmTitle", "alarmBody")) put(k, k)
        put("title", "Time for {names}").put("body", "at {time}").put("pending", "{names} waits").put("promiseChip", "{names} by {time}")
        put("deadline", "You promised {time}").put("notNowRemind", "Again at {time}.").put("notNowAlarm", "The alarm at {time}.")
        put("notNowWait", "It waits on Today.")
        put("publicTitle", "Reminder").put("channelNag", "Full screen and alarm").put("channelPending", "Waiting").put("channelNotice", "Notices")
        put("channels", JSONArray().put(JSONObject().put("sound", "sininho").put("name", "Reminder · Bell"))
            .put(JSONObject().put("sound", "kalimba").put("name", "Reminder · Kalimba")))
        put("lines", JSONArray(lines.map { JSONArray(it) }))
    }

    fun dose(key: String, group: String, miss: String, name: String = key, promiseUntil: Long? = null, seed: Long = 0,
        sound: String = "sininho", alarm: String = "off"): JSONObject =
        JSONObject().put("key", key).put("group", group).put("name", name).put("miss", miss).put("promiseUntil", promiseUntil ?: JSONObject.NULL)
            .put("lineSeed", seed).put("sound", sound).put("alarm", alarm)

    /** A mascot of one circle in [fill], so a test can tell the rungs' drawings apart. */
    fun mascot(fill: String): JSONObject = JSONObject().put("viewBox", JSONArray(listOf(0, 0, 200, 200)))
        .put("parts", JSONArray().put(JSONObject().put("t", "circle").put("cx", 100).put("cy", 100).put("r", 80).put("fill", fill)))

    /** A plan: No ritmo with Bolinha and Tela cheia on by default. */
    fun plan(vararg doses: JSONObject, made: Long = 0, first: Int = 20, then: Int = 15, repeatFirst: Int = 30, repeatEvery: Int = 60,
        repeats: Int = 3, bubble: Boolean = true, fullScreen: Boolean = true, unlock: Boolean = false, volume: Double = 1.0,
        stock: Boolean = false): JSONObject =
        JSONObject().put("v", 2).put("made", made).put("zone", "America/Sao_Paulo").put("locale", "en")
            .put("ladder", JSONObject().put("firstMin", first).put("thenMin", then).put("repeatFirstMin", repeatFirst)
                .put("repeatEveryMin", repeatEvery).put("repeats", repeats))
            .put("steps", JSONObject().put("bubble", bubble).put("fullScreen", fullScreen).put("unlock", unlock))
            .put("alarm", JSONObject().put("volume", volume))
            .put("liveUpdate", true).put("doses", JSONArray(doses.toList()))
            .put("texts", texts()).put("mascot", JSONObject().put("viewBox", JSONArray(listOf(0, 0, 200, 200))).put("parts", JSONArray()))
            .put("mascots", JSONArray())
            .put("stock", if (stock) JSONObject().put("title", "Low").put("body", "Pill A: 3 left") else JSONObject.NULL)
            .put("idle", JSONObject().put("afterDays", 6).put("title", "Open the app").put("body", "to keep reminders"))

    fun clear(c: Context) = Store.clear(c)
}
