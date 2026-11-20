// The plan the data module sends while the app is in front (`scheduleNags`): the next days' doses as concrete local
// times, the ladder, the texts already localized, and the mascot's parts. Kotlin holds no schedule rules: cycles,
// weekdays, course ends and each dose's `miss` were expanded in TypeScript.
package pinkoo.nag

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

internal class Ladder(val firstMin: Int, val repeatMin: Int, val ringSec: Int)

/** A rung: reached at [ignores] ignores, it adds the nuisances [add] names. */
internal class Rung(val ignores: Int, val add: List<String>)

/**
 * One dose: [group] is its scheduled local time (`2026-11-18T08:00`), shared by every dose due then; [miss] the local
 * time it becomes missed, with the offset the app knows it at when it sends one (`2026-11-01T01:00-05:00`); [lineSeed]
 * where its mascot lines start rotating.
 */
internal class Dose(val key: String, val group: String, val name: String, val miss: String, val promiseUntil: Long?, val lineSeed: Long) {
    private val offset: java.time.OffsetDateTime? = runCatching { java.time.OffsetDateTime.parse(miss) }.getOrNull()
    /** [miss]'s instant, when it carries its offset. */
    val missAt: Long? = offset?.toInstant()?.toEpochMilli()
    /** [miss]'s local time alone. */
    val missLocal: String = offset?.toLocalDateTime()?.toString() ?: miss
}

internal class Texts(private val o: JSONObject) {
    private fun s(k: String) = o.optString(k)
    val title = s("title"); val body = s("body"); val publicTitle = s("publicTitle"); val publicBody = s("publicBody")
    val take = s("take"); val promise = s("promise"); val notNow = s("notNow"); val pending = s("pending")
    val promiseChip = s("promiseChip"); val bubble = s("bubble")
    /** The channels' names in Android's settings: the loud nag the service rings, the waiting one, the notices, and the
     * loud nag that sounds itself when no service can ring ([Shade.CH_RING]). */
    val channelNag = s("channelNag"); val channelPending = s("channelPending"); val channelNotice = s("channelNotice")
    val channelRing = s("channelRing")
    /** The mascot's lines, by rung (0 to the top, 4), which rotate. */
    val lines: List<List<String>> = (0 until (o.optJSONArray("lines")?.length() ?: 0)).map { r ->
        val a = o.getJSONArray("lines").optJSONArray(r)
        (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
    }
}

internal class Stock(val title: String, val body: String)

/** The notice when the app has not been in front for [afterDays] days: the plan holds seven days of nags, and the
 * notice comes the day before they run out (decision 5: day 6). */
internal class Idle(val afterDays: Int, val title: String, val body: String)

internal class Plan(
    val made: Long, val ladder: Ladder, val rungs: List<Rung>, val liveUpdate: Boolean, val volume: List<Double>,
    val doses: List<Dose>, val texts: Texts, val mascot: Mascot?, val mascots: List<Mascot>, val stock: Stock?, val idle: Idle,
    /** The zone the app built the plan in (IANA), '' when it did not say. */
    val zone: String = "",
) {
    private val madeIn: ZoneId? by lazy { runCatching { ZoneId.of(zone) }.getOrNull() }

    /**
     * When [d] becomes missed in [now]'s zone: the instant its offset names while the phone is still in the zone the
     * plan was made in, since a local time in a daylight-saving overlap names two instants (the second 01:00 of a
     * fall-back night, read with the earlier offset, would stop the nag an hour before the app makes the dose missed);
     * in another zone (a trip), or with no offset, its local time there, as every dose's time.
     */
    fun miss(d: Dose, now: ZoneId = ZoneId.systemDefault()): Long {
        val at = d.missAt
        return if (at != null && madeIn?.let { it == now || it.rules == now.rules } == true) at else WallClock.epoch(d.missLocal, now)
    }

    /** The app's route for a group's nag. */
    val route = "/nag/{group}"

    /** The rung [ignores] ignores reach: the last whose threshold they meet. The app's plan has one per ignore, 0 to 4,
     * the mascot's top (`src/app/plan.ts` nagRung): the nag screen shows the same. */
    fun rung(ignores: Int): Int = rungs.indexOfLast { it.ignores <= ignores }.coerceAtLeast(0)

    /** Every nuisance a group at [rung] has: its rung's and every rung's below. */
    fun words(rung: Int): Set<String> = rungs.take(rung + 1).flatMap { it.add }.toSet()

    /** The wait before the next loud nag once a nag is ignored, the [ignores]-th time: the first interval after the
     * first ignore, the second after each later one. */
    fun interval(ignores: Int): Long = (if (ignores <= 1) ladder.firstMin else ladder.repeatMin) * 60_000L

    fun volume(rung: Int): Double = volume.getOrNull(rung) ?: volume.lastOrNull() ?: 1.0

    /** The mascot's line at [rung] for a nag: `lines[rung][(seed + ignores) mod count]`, the top rung's for any above.
     * The nag screen picks the same (`src/app/plan.ts` nagLineIndex, `mascotNagLine`). */
    fun line(rung: Int, seed: Long, ignores: Int): String {
        val lines = texts.lines.getOrNull(rung) ?: texts.lines.lastOrNull() ?: return ""
        return if (lines.isEmpty()) "" else lines[Math.floorMod(seed + ignores, lines.size.toLong()).toInt()]
    }

    /** The mascot at [rung], its face for that rung; rung 0's for any the plan has no drawing for. */
    fun mascot(rung: Int): Mascot? = mascots.getOrNull(rung)?.takeIf { it.parts.isNotEmpty() } ?: mascot

    fun doses(group: String): List<Dose> = doses.filter { it.group == group }

    companion object {
        fun parse(o: JSONObject): Plan {
            val l = o.optJSONObject("ladder") ?: JSONObject()
            val ladder = Ladder(l.optInt("firstMin", 20).coerceIn(1, 24 * 60), l.optInt("repeatMin", 15).coerceIn(1, 24 * 60),
                l.optInt("ringSec", 60).coerceIn(5, 600))
            val rungs = objects(o.optJSONArray("rungs")).map { r -> Rung(r.optInt("ignores"), strings(r.optJSONArray("add"))) }
                .sortedBy { it.ignores }
            val volume = (0 until (o.optJSONArray("volume")?.length() ?: 0)).map { o.getJSONArray("volume").optDouble(it, 1.0).coerceIn(0.0, 1.0) }
            val doses = objects(o.optJSONArray("doses")).map { d ->
                Dose(d.optString("key"), d.optString("group"), d.optString("name"), d.optString("miss"),
                    if (d.isNull("promiseUntil")) null else d.optLong("promiseUntil"), d.optLong("lineSeed"))
            }.filter { it.key.isNotEmpty() && WallClock.valid(it.group) && WallClock.valid(it.missLocal) }
            val stock = o.optJSONObject("stock")?.let { Stock(it.optString("title"), it.optString("body")) }
            val idle = o.optJSONObject("idle").let { Idle(it?.optInt("afterDays", 6)?.coerceIn(1, 366) ?: 6, it?.optString("title") ?: "", it?.optString("body") ?: "") }
            val mascots = whole(o.opt("mascots")).let { m -> if (m is JSONArray) objects(m).map(Mascot::parse) else emptyList() }
            return Plan(o.optLong("made"), ladder, rungs.ifEmpty { listOf(Rung(0, listOf("ring", "fullScreen"))) },
                o.optBoolean("liveUpdate", true), volume, doses, Texts(o.optJSONObject("texts") ?: JSONObject()),
                (whole(o.opt("mascot")) as? JSONObject)?.let(Mascot::parse), mascots, stock, idle, o.optString("zone"))
        }

        /** A value passed whole (`json<…>`): the object itself, or its JSON text. */
        private fun whole(v: Any?): Any? = if (v is String) runCatching { org.json.JSONTokener(v).nextValue() }.getOrNull() else v

        private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
        private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
    }
}

/**
 * A local wall-clock time to an instant in the zone the phone is in now, read at every arm: a trip or a zone change
 * moves every dose to the same local time there. A time in a daylight-saving gap moves later by the gap; one in an
 * overlap takes the earlier offset (`LocalDateTime.atZone`).
 */
internal object WallClock {
    fun epoch(local: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    fun valid(local: String): Boolean = runCatching { LocalDateTime.parse(local) }.isSuccess

    /** The local date of [at], `2026-11-18`. */
    fun day(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
}
