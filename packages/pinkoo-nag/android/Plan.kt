// The plan the data module sends while the app is in front (`scheduleNags`, shape v2): the next days' doses as concrete
// local times, each with its sound and its alarm choice, the steps she has on and her pace, the texts already localized,
// and the mascot's parts. Kotlin holds no schedule rules: cycles, weekdays, course ends and each dose's `miss` were
// expanded in TypeScript.
package pinkoo.nag

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Her pace (`docs/product.md` › The pace): Notificação to De novo [firstMin]; to each next step on (Tela cheia, then
 * the alarm "Só se passar de tudo") [thenMin]; then up to [repeats] repeats (0 to 3), the first [repeatFirstMin] after
 * the last step and the others [repeatEveryMin] apart.
 */
internal class Ladder(val firstMin: Int, val thenMin: Int, val repeatFirstMin: Int, val repeatEveryMin: Int, val repeats: Int)

/** The steps she switches (Ajustes › Como eu te lembro): Bolinha at De novo, Tela cheia, and opening at the unlock
 * under it. The notification has no switch. */
internal class Steps(val bubble: Boolean, val fullScreen: Boolean, val unlock: Boolean)

/** A dose's alarm: none, its own last step ("Só se passar de tudo"), or at the dose time with the notification
 * folded into it ("Já na hora"). */
internal enum class Alarm(val word: String) {
    OFF("off"), AFTER_ALL("afterAll"), ON_TIME("onTime");
    companion object { fun of(w: String?): Alarm = entries.firstOrNull { it.word == w } ?: OFF }
}

/**
 * One dose: [group] is its scheduled local time (`2026-11-18T08:00`), shared by every dose due then; [miss] the local
 * time it becomes missed, with the offset the app knows it at when it sends one (`2026-11-01T01:00-05:00`); [lineSeed]
 * where its mascot lines start rotating; [sound] her reminder's sound (a preset's id, `vibrate`, `silent`, or a phone
 * sound's canonical address); [alarm] its alarm choice.
 */
internal class Dose(val key: String, val group: String, val name: String, val miss: String, val promiseUntil: Long?, val lineSeed: Long,
    val sound: String = Plan.DEFAULT_SOUND, val alarm: Alarm = Alarm.OFF) {
    private val offset: java.time.OffsetDateTime? = runCatching { java.time.OffsetDateTime.parse(miss) }.getOrNull()
    /** [miss]'s instant, when it carries its offset. */
    val missAt: Long? = offset?.toInstant()?.toEpochMilli()
    /** [miss]'s local time alone. */
    val missLocal: String = offset?.toLocalDateTime()?.toString() ?: miss
}

internal class Texts(private val o: JSONObject) {
    /** Any text by its key, '' when the plan has none: the surface reads its own (the alarm's, the service's). */
    fun s(k: String): String = o.optString(k)
    val title = s("title"); val body = s("body"); val publicTitle = s("publicTitle"); val publicBody = s("publicBody")
    val take = s("take"); val promise = s("promise"); val notNow = s("notNow"); val pending = s("pending")
    /** The line at a promise's deadline (`{time}` the deadline), in place of the rung's. */
    val deadline = s("deadline")
    val promiseChip = s("promiseChip"); val bubble = s("bubble")
    /** What the notification turns into after its "Agora não", by what comes next (`{time}`): a reminder, the alarm,
     * or nothing before the dose is missed. */
    val notNowRemind = s("notNowRemind"); val notNowAlarm = s("notNowAlarm"); val notNowWait = s("notNowWait")
    val alarmTitle = s("alarmTitle"); val alarmBody = s("alarmBody"); val stop = s("stop"); val service = s("service")
    /** The engine's own channels' names in Android's settings: Tela cheia and the alarm, the waiting one, the notices. */
    val channelNag = s("channelNag"); val channelPending = s("channelPending"); val channelNotice = s("channelNotice")
    /** Each reminder channel's name ("Lembrete · Sininho"), by the sound it plays. */
    val channels: Map<String, String> = (0 until (o.optJSONArray("channels")?.length() ?: 0)).mapNotNull { i ->
        o.getJSONArray("channels").optJSONObject(i)?.let { it.optString("sound") to it.optString("name") }
    }.filter { it.first.isNotEmpty() }.toMap()
    /** The mascot's lines, by rung (0 to the top, 4), which rotate. */
    val lines: List<List<String>> = (0 until (o.optJSONArray("lines")?.length() ?: 0)).map { r ->
        val a = o.getJSONArray("lines").optJSONArray(r)
        (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
    }
}

internal class Stock(val title: String, val body: String)

/** The notice when the app has not been in front for [afterDays] days: the plan holds seven days of reminders, and the
 * notice comes the day before they run out (decision 5: day 6). */
internal class Idle(val afterDays: Int, val title: String, val body: String)

internal class Plan(
    val made: Long, val ladder: Ladder, val steps: Steps,
    /** Her alarm volume, 0.2 to 1 of the alarm stream, which the ring maps through the decibel curve. */
    val alarmVolume: Double,
    val liveUpdate: Boolean, val doses: List<Dose>, val texts: Texts, val mascot: Mascot?, val mascots: List<Mascot>,
    val stock: Stock?, val idle: Idle,
    /** The zone the app built the plan in (IANA), '' when it did not say. */
    val zone: String = "",
) {
    private val madeIn: ZoneId? by lazy { runCatching { ZoneId.of(zone) }.getOrNull() }

    /**
     * When [d] becomes missed in [now]'s zone: the instant its offset names while the phone is still in the zone the
     * plan was made in, since a local time in a daylight-saving overlap names two instants (the second 01:00 of a
     * fall-back night, read with the earlier offset, would stop the reminder an hour before the app makes the dose
     * missed); in another zone (a trip), or with no offset, its local time there, as every dose's time.
     */
    fun miss(d: Dose, now: ZoneId = ZoneId.systemDefault()): Long {
        val at = d.missAt
        return if (at != null && madeIn?.let { it == now || it.rules == now.rules } == true) at else WallClock.epoch(d.missLocal, now)
    }

    /** The app's route for a group's nag. */
    val route = "/nag/{group}"

    /** Every sound the plan's doses name, in the plan's order: her reminder channels (`docs/exact.md` item 5). */
    fun sounds(): List<String> = doses.map { it.sound }.distinct()

    /** The mascot's line for a reminder [reached] reminders in: `lines[rung][(seed + reached) mod count]`, the rung
     * being [reached] up to the top (4), and the top rung's lines for any the plan has none of. The nag screen picks
     * the same (`src/app/plan.ts` nagRung, nagLineIndex). */
    fun line(rung: Int, seed: Long, reached: Int): String {
        val lines = texts.lines.getOrNull(rung) ?: texts.lines.lastOrNull() ?: return ""
        return if (lines.isEmpty()) "" else lines[Math.floorMod(seed + reached, lines.size.toLong()).toInt()]
    }

    /** The mascot at [rung], its face for that rung; rung 0's for any the plan has no drawing for. */
    fun mascot(rung: Int): Mascot? = mascots.getOrNull(rung)?.takeIf { it.parts.isNotEmpty() } ?: mascot

    fun doses(group: String): List<Dose> = doses.filter { it.group == group }

    companion object {
        /** The default preset (Sininho): the sound of a dose that names none, and of a plan from before sounds. */
        const val DEFAULT_SOUND = "sininho"
        /** The top rung: the mascot's own top face. */
        const val TOP = 4

        fun parse(o: JSONObject): Plan {
            val l = o.optJSONObject("ladder") ?: JSONObject()
            // A plan from before the gentle reminder (shape v1: `rungs`, `repeatMin`, `ringSec`), still stored after an
            // update until the app sends its first new one: her pace mapped to the nearest (`docs/product.md` ›
            // Nagging, Updating from the old ladder), the bubble as she had it, Tela cheia on, no unlock, no alarm.
            val old = !o.has("steps") && o.has("rungs")
            val ladder = if (old) oldLadder(l.optInt("firstMin", 20), l.optInt("repeatMin", 15))
            else Ladder(l.optInt("firstMin", 20).coerceIn(1, 24 * 60), l.optInt("thenMin", 15).coerceIn(1, 24 * 60),
                l.optInt("repeatFirstMin", 30).coerceIn(1, 24 * 60), l.optInt("repeatEveryMin", 60).coerceIn(1, 24 * 60),
                l.optInt("repeats", 3).coerceIn(0, 3))
            val s = o.optJSONObject("steps")
            val steps = if (s != null) Steps(s.optBoolean("bubble"), s.optBoolean("fullScreen"), s.optBoolean("unlock"))
            else Steps(old && oldWords(o).contains("bubble"), true, false)
            val alarmVolume = (o.optJSONObject("alarm")?.optDouble("volume", 1.0) ?: 1.0).let { if (it.isNaN()) 1.0 else it }.coerceIn(0.2, 1.0)
            val doses = objects(o.optJSONArray("doses")).map { d ->
                Dose(d.optString("key"), d.optString("group"), d.optString("name"), d.optString("miss"),
                    if (d.isNull("promiseUntil")) null else d.optLong("promiseUntil"), d.optLong("lineSeed"),
                    d.optString("sound").ifEmpty { DEFAULT_SOUND }, Alarm.of(d.optString("alarm")))
            }.filter { it.key.isNotEmpty() && WallClock.valid(it.group) && WallClock.valid(it.missLocal) }
            val stock = o.optJSONObject("stock")?.let { Stock(it.optString("title"), it.optString("body")) }
            val idle = o.optJSONObject("idle").let { Idle(it?.optInt("afterDays", 6)?.coerceIn(0, 366) ?: 6, it?.optString("title") ?: "", it?.optString("body") ?: "") }
            val mascots = whole(o.opt("mascots")).let { m -> if (m is JSONArray) objects(m).map(Mascot::parse) else emptyList() }
            return Plan(o.optLong("made"), ladder, steps, alarmVolume, o.optBoolean("liveUpdate", true), doses,
                Texts(o.optJSONObject("texts") ?: JSONObject()), (whole(o.opt("mascot")) as? JSONObject)?.let(Mascot::parse), mascots,
                stock, idle, o.optString("zone"))
        }

        /** The old ladder's two intervals to the nearest pace: Suave (30, 20) to Com calma, Padrão (20, 15) to No ritmo,
         * Insistente (10, 5) to De pertinho; Meu jeito keeps her two intervals with No ritmo's repeats. */
        private fun oldLadder(first: Int, repeat: Int): Ladder = when (first to repeat) {
            30 to 20 -> Ladder(30, 30, 60, 60, 2)
            20 to 15 -> Ladder(20, 15, 30, 60, 3)
            10 to 5 -> Ladder(10, 10, 20, 20, 3)
            else -> Ladder(first.coerceIn(5, 120), repeat.coerceIn(5, 120), 30, 60, 3)
        }

        /** Every nuisance an old plan's rungs named. */
        private fun oldWords(o: JSONObject): Set<String> = objects(o.optJSONArray("rungs")).flatMap { strings(it.optJSONArray("add")) }.toSet()

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

    /** [at] in the reminder's words: always 24 h ("12:10", never "12:10 PM"), as the app writes every time, whatever
     * the phone's 12/24 setting. */
    fun hhmm(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT).format(java.time.Instant.ofEpochMilli(at).atZone(zone))
}
