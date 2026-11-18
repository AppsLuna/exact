// The engine's notifications and channels. One nag notification for everything ringing or waiting, loud while it rings
// and silent on the waiting channel after; one for a promise; one each for the low-stock and the stale notices. The
// texts are the plan's, already localized; times are formatted here, in the phone's 12 or 24 hour setting. On the lock
// screen each shows its public version, without the medication's name.
//
// Every loud nag is a new notification: Android launches a full-screen intent only for a notification newly posted,
// never for an update of one that shows (measured on Android 16: a dose's third nag showed no full-screen nag), so the
// nag notification's id alternates between two from one nag to the next ([nagId]). A notification is posted only when
// what it shows changed or it went (an update of the loud nag popped its heads-up again), and an update never alerts.
package pinkoo.nag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.exact.canvas.ExtensionContext
import org.json.JSONArray

/** What rings and waits now, read off the plan and the state. */
internal class Live(val plan: Plan, val st: State) {
    private val open = st.groups.values.filter { Rules.open(plan, st, it).isNotEmpty() }
    val ringing = open.filter { it.state == "ringing" }
    val waiting = open.filter { it.state == "pending" }
    val promised = open.filter { it.state == "promised" }
    /** Ringing and waiting: what the nag notification shows. */
    val groups = ringing + waiting
    val nag = groups.maxOfOrNull { it.nag } ?: 0
    val rung = groups.maxOfOrNull { Rules.rung(plan, it) } ?: 0
    /** The ring's rung: its volume and vibration. */
    val ringingRung = ringing.maxOfOrNull { Rules.rung(plan, it) } ?: 0
    /** Every nuisance the live groups' rungs have. */
    val words: Set<String> = groups.flatMap { plan.words(Rules.rung(plan, it)) }.toSet()
    /** The group the nag opens at: the one ringing, else the one waiting longest. */
    val lead: Group? = ringing.firstOrNull() ?: groups.minByOrNull { it.group }
    /** The lead group's rung: the mascot the notification and the bubble show, as the nag route they open does. */
    val face: Int = lead?.let { Rules.rung(plan, it) } ?: 0

    fun names(gs: List<Group>): String = gs.flatMap { Rules.open(plan, st, it) }.map { it.name }.distinct().joinToString(", ")
}

internal object Shade {
    const val NAG = 0x6e6100
    const val PROMISE = NAG + 1
    const val STOCK = NAG + 2
    const val STALE = NAG + 3
    /** The nag notification's other id ([nagId]). */
    const val NAG_ALT = NAG + 4
    const val CH_NAG = "pinkoo.nag"
    const val CH_WAITING = "pinkoo.nag.waiting"
    const val CH_NOTICE = "pinkoo.nag.notice"
    /** The loud nag when no service can play the ring: it sounds itself (decision 5). */
    const val CH_RING = "pinkoo.nag.ring"

    /** The nag notification's id for nag [nag]: odd and even nags alternate, so each loud nag is a new notification
     * and launches its full-screen nag; a nag's waiting notification is its loud one, updated. */
    fun nagId(nag: Int): Int = if (nag % 2 == 0) NAG_ALT else NAG

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private val alarmAttributes: AudioAttributes get() =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** The channels, named in the plan's language: made once, renamed when the language changes. The nag channel is
     * silent, since the service plays the ring on the alarm stream; the ring channel sounds for a nag no service can
     * ring: her alarm sound (her ringtone when it is None) on the alarm stream, chosen when it is made. */
    fun channels(c: Context, t: Texts) {
        val nm = nm(c)
        nm.createNotificationChannel(NotificationChannel(CH_NAG, t.channelNag.ifEmpty { CH_NAG }, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CH_WAITING, t.channelPending.ifEmpty { CH_WAITING }, NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CH_NOTICE, t.channelNotice.ifEmpty { CH_NOTICE }, NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CH_RING, t.channelRing.ifEmpty { CH_RING }, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Sounds.ring(c).first(), alarmAttributes)
            enableVibration(true); vibrationPattern = longArrayOf(0, 900, 250)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun blocked(c: Context): Boolean = nm(c).getNotificationChannel(CH_NAG)?.importance == NotificationManager.IMPORTANCE_NONE

    /** Whether a ring can be stopped from a notification: no sound starts without one (as `@exact/alarms`). */
    fun stoppable(c: Context): Boolean = nm(c).areNotificationsEnabled() && !blocked(c)

    /** The app's notification glyph (`monochrome` in app.json), else the platform's alarm clock. */
    fun icon(c: Context): Int = c.resources.getIdentifier("exact_notification", "drawable", c.packageName).takeIf { it != 0 }
        ?: android.R.drawable.ic_lock_idle_alarm

    fun route(plan: Plan, group: String, via: String, action: String? = null): String {
        val path = plan.route.replace("{group}", Uri.encode(group))
        return path + (if ('?' in path) "&" else "?") + "via=$via" + (action?.let { "&a=$it" } ?: "")
    }

    /** The app at the nag route: a tap's, an action's, the full-screen nag's (over the keyguard), the bubble's or an
     * unlock's. */
    fun opening(c: Context, plan: Plan, group: String, via: String, action: String? = null, overLock: Boolean = false): Intent =
        ExtensionContext.activation(c, "nag", "", JSONArray(), route(plan, group, via, action), overLock)

    private fun activity(c: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(c, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun broadcast(c: Context, code: Int, action: String, nag: Int = 0, at: Long = 0): PendingIntent = PendingIntent.getBroadcast(c, code,
        Intent(c, NagReceiver::class.java).setAction(action).putExtra("nag", nag).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun time(c: Context, at: Long): String = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(at))

    private fun fill(c: Context, template: String, names: String, at: Long?): String =
        template.replace("{names}", names).replace("{time}", at?.let { time(c, it) } ?: "")

    private var picture: Pair<Long, Bitmap>? = null

    /** The mascot at [rung] as the notification's picture, drawn once per plan and rung. */
    private fun picture(c: Context, plan: Plan, rung: Int): Bitmap? {
        val key = plan.made * 16 + rung
        picture?.takeIf { it.first == key }?.let { return it.second }
        val px = (64 * c.resources.displayMetrics.density).toInt().coerceIn(64, 256)
        return MascotDrawable.of(plan, rung)?.bitmap(px)?.also { picture = key to it }
    }

    private fun base(c: Context, channel: String, plan: Plan): Notification.Builder = Notification.Builder(c, channel)
        .setSmallIcon(icon(c)).setColor(0xFFFF7A59.toInt()).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(Notification.Builder(c, channel).setSmallIcon(icon(c)).setContentTitle(plan.texts.publicTitle)
            .setContentText(plan.texts.publicBody).build())

    // MARK: posting only what changed

    /** What each notification last posted shows, by id. In memory: after a process death the first post repeats, an
     * update of the one showing, which alerts nothing (`setOnlyAlertOnce`). */
    private val shown = HashMap<Int, String>()

    fun forget() = synchronized(shown) { shown.clear() }

    private fun showing(c: Context): List<StatusBarNotification> = runCatching { nm(c).activeNotifications.toList() }.getOrDefault(emptyList())

    /** [n] posted as [id], unless notifications are off. */
    fun notify(c: Context, id: Int, n: Notification) { if (nm(c).areNotificationsEnabled()) nm(c).notify(id, n) }

    fun cancel(c: Context, id: Int) {
        nm(c).cancel(id)
        synchronized(shown) { shown.remove(id) }
    }

    /** Posts [build]'s notification as [id] through [post] unless the one showing there (of [showing]) already shows
     * [look]. */
    private fun put(c: Context, id: Int, look: String, force: Boolean, showing: List<StatusBarNotification>,
        post: (Int, Notification) -> Unit, build: () -> Notification): Boolean {
        val there = showing.firstOrNull { it.id == id }
        if (!force && there != null && synchronized(shown) { shown[id] } == look) return false
        // Going quiet, a nag that sounded itself is posted anew: an insistent sound is not left to an update to stop.
        if (there != null && there.notification.channelId == CH_RING && !look.startsWith(CH_RING)) nm(c).cancel(id)
        post(id, build())
        synchronized(shown) { shown[id] = look }
        return true
    }

    // MARK: the nag

    /** What the nag notification says, before it is built: equal ones show the same. */
    private data class NagLook(
        val channel: String, val title: String, val line: String, val sub: String, val at: Long?, val face: Int,
        val lead: String, val nag: Int, val on: List<String>, val plan: Long,
    )

    private fun look(c: Context, live: Live, loud: Boolean, sounding: Boolean): NagLook {
        val plan = live.plan
        val lead = live.lead ?: live.groups.first()
        val names = live.names(if (loud) live.ringing else live.groups)
        val at = runCatching { WallClock.epoch(lead.group) }.getOrNull()
        val sub = fill(c, plan.texts.body, names, at)
        val channel = if (!loud) CH_WAITING else if (sounding) CH_RING else CH_NAG
        return NagLook(channel, fill(c, if (loud) plan.texts.title else plan.texts.pending, names, at),
            // The mascot's line says it; the body (the time) goes in the header. Without a line, the body is the text.
            Rules.line(plan, live.st, lead).ifEmpty { sub }, sub, at, live.face, lead.group, live.nag,
            Nuisances.all.filter { Nuisances.on(c, it, live) }.map { it.word }, plan.made)
    }

    /**
     * The nag notification brought to [live] through [post] (the service's foreground, or a plain post): loud while
     * something rings (heads-up, the alarm category, the full-screen nag when its nuisance is on), [sounding] when no
     * service plays the ring, silent while it waits. Posted only when what it shows changed or it went (swiped,
     * cleared), or when [force]d; the other id goes, so there is one. Whether it posted.
     */
    fun nag(c: Context, live: Live, loud: Boolean, sounding: Boolean = false, force: Boolean = false, post: (Int, Notification) -> Unit): Boolean {
        val id = nagId(live.nag)
        val look = look(c, live, loud, sounding)
        val there = showing(c)
        val posted = put(c, id, look.channel + "|" + look.toString(), force, there, post) { build(c, live, loud, look) }
        val other = if (id == NAG) NAG_ALT else NAG
        if (synchronized(shown) { shown.containsKey(other) } || there.any { it.id == other }) cancel(c, other)
        return posted
    }

    /** The nag notification gone, both ids. */
    fun cancelNag(c: Context) {
        val there = showing(c).map { it.id }
        for (id in listOf(NAG, NAG_ALT)) if (id in there || synchronized(shown) { shown.containsKey(id) }) cancel(c, id)
    }

    /**
     * Swiping either away is the nag's ignore ([NagReceiver.DISMISS]); "Not now" ignores it too, as a broadcast, so the
     * app does not open; "Take now" and "Pinky promise" open the nag route.
     */
    private fun build(c: Context, live: Live, loud: Boolean, look: NagLook): Notification {
        val plan = live.plan
        val b = base(c, look.channel, plan)
            .setContentTitle(look.title).setContentText(look.line).setSubText(look.sub)
            .setStyle(Notification.BigTextStyle().bigText(look.line))
            .setCategory(if (loud) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(true).setWhen(look.at ?: System.currentTimeMillis())
            .setContentIntent(activity(c, 1, opening(c, plan, look.lead, "tap")))
            .setDeleteIntent(broadcast(c, 2, NagReceiver.DISMISS, live.nag))
            .addAction(Notification.Action.Builder(null, plan.texts.take, activity(c, 3, opening(c, plan, look.lead, "action", "take"))).build())
            .addAction(Notification.Action.Builder(null, plan.texts.promise, activity(c, 4, opening(c, plan, look.lead, "action", "promise"))).build())
        if (loud) b.addAction(Notification.Action.Builder(null, plan.texts.notNow, broadcast(c, 5, NagReceiver.NOT_NOW, live.nag)).build())
        picture(c, plan, look.face)?.let { b.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT >= 34) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        Nuisances.nag(c, b, live, loud)
        // Sounding itself, it repeats until the ring's end posts it silent.
        return b.build().apply { if (look.channel == CH_RING) flags = flags or Notification.FLAG_INSISTENT }
    }

    // MARK: the promise and the notices

    /** A promise: its deadline counting down, promoted to a Live Update where the platform and the person allow it.
     * Dismissing it is no ignore; it is not shown again for that promise. Posted only when it changed. */
    fun promise(c: Context, live: Live) {
        val plan = live.plan
        val until = live.promised.mapNotNull { it.until }.minOrNull()
        val there = showing(c)
        if (until == null || live.st.promiseDismissed == until) {
            if (synchronized(shown) { shown.containsKey(PROMISE) } || there.any { it.id == PROMISE }) cancel(c, PROMISE)
            return
        }
        val lead = live.promised.minByOrNull { it.until ?: Long.MAX_VALUE }!!
        val names = live.names(live.promised)
        val title = fill(c, plan.texts.promiseChip, names, until)
        val on = Nuisances.all.filter { Nuisances.on(c, it, live) }.map { it.word }
        put(c, PROMISE, listOf(title, until, lead.group, on, plan.made).toString(), false, there, { id, n -> notify(c, id, n) }) {
            val b = base(c, CH_WAITING, plan)
                .setContentTitle(title)
                .setCategory(Notification.CATEGORY_REMINDER).setOngoing(true).setOnlyAlertOnce(true)
                .setWhen(until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setContentIntent(activity(c, 6, opening(c, plan, lead.group, "promise")))
                .setDeleteIntent(broadcast(c, 7, NagReceiver.PROMISE_DISMISSED, at = until))
            Nuisances.promise(c, b, live)
            b.build()
        }
    }

    fun stock(c: Context, plan: Plan): Notification? {
        val s = plan.stock ?: return null
        // It rides on a dose's ring, so it makes no sound of its own: the waiting channel's.
        return base(c, CH_WAITING, plan).setContentTitle(s.title).setContentText(s.body).setAutoCancel(true)
            .setStyle(Notification.BigTextStyle().bigText(s.body))
            .setContentIntent(activity(c, 8, ExtensionContext.activation(c, "nag", "", JSONArray(), "/"))).build()
    }

    fun stale(c: Context, plan: Plan): Notification = base(c, CH_NOTICE, plan)
        .setContentTitle(plan.idle.title).setContentText(plan.idle.body).setAutoCancel(true)
        .setStyle(Notification.BigTextStyle().bigText(plan.idle.body))
        .setContentIntent(activity(c, 9, ExtensionContext.activation(c, "nag", "", JSONArray(), "/"))).build()

    fun post(c: Context, id: Int, n: Notification?) { if (n == null) cancel(c, id) else notify(c, id, n) }
}
