// The engine's notifications and channels. One nag notification for everything ringing or waiting, loud on the nag
// channel while it rings and silent on the waiting channel after; one for a promise; one each for the low-stock and the
// stale notices. The texts are the plan's, already localized; times are formatted here, in the phone's 12 or 24 hour
// setting. On the lock screen each shows its public version, without the medication's name.
package pinkoo.nag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
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
    val ringingRung = ringing.maxOfOrNull { Rules.rung(plan, it) } ?: 0
    /** Every nuisance the live groups' rungs have. */
    val words: Set<String> = groups.flatMap { plan.words(Rules.rung(plan, it)) }.toSet()
    /** The group the nag opens at: the one ringing, else the one waiting longest. */
    val lead: Group? = ringing.firstOrNull() ?: groups.minByOrNull { it.group }

    fun names(gs: List<Group>): String = gs.flatMap { Rules.open(plan, st, it) }.map { it.name }.distinct().joinToString(", ")
}

internal object Shade {
    const val NAG = 0x6e6100
    const val PROMISE = NAG + 1
    const val STOCK = NAG + 2
    const val STALE = NAG + 3
    const val CH_NAG = "pinkoo.nag"
    const val CH_WAITING = "pinkoo.nag.waiting"
    const val CH_NOTICE = "pinkoo.nag.notice"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    /** The channels, named in the plan's language: made once, renamed when the language changes. The nag channel is
     * silent, since the service plays the ring on the alarm stream. */
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

    /**
     * The nag notification: loud while something rings (heads-up, the alarm category, the full-screen nag when its
     * nuisance is on), silent while it waits. Swiping either away is the nag's ignore ([NagReceiver.DISMISS]); "Not
     * now" ignores it too, as a broadcast, so the app does not open; "Take now" and "Pinky promise" open the nag route.
     */
    fun nag(c: Context, live: Live, loud: Boolean): Notification {
        val plan = live.plan
        val lead = live.lead ?: live.groups.first()
        val shown = if (loud) live.ringing else live.groups
        val names = live.names(shown)
        val at = runCatching { WallClock.epoch(lead.group) }.getOrNull()
        val rung = if (loud) live.ringingRung else live.rung
        val seed = Rules.open(plan, live.st, lead).firstOrNull()?.lineSeed ?: 0
        // The mascot's line says it; the body (the time) goes in the header. Without a line, the body is the text.
        val line = plan.line(rung, seed, live.nag).ifEmpty { fill(c, plan.texts.body, names, at) }
        val b = base(c, if (loud) CH_NAG else CH_WAITING, plan)
            .setContentTitle(fill(c, if (loud) plan.texts.title else plan.texts.pending, names, at))
            .setContentText(line).setSubText(fill(c, plan.texts.body, names, at))
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setCategory(if (loud) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setOngoing(true).setOnlyAlertOnce(!loud).setShowWhen(true).setWhen(at ?: System.currentTimeMillis())
            .setContentIntent(activity(c, 1, opening(c, plan, lead.group, "tap")))
            .setDeleteIntent(broadcast(c, 2, NagReceiver.DISMISS, live.nag))
            .addAction(Notification.Action.Builder(null, plan.texts.take, activity(c, 3, opening(c, plan, lead.group, "action", "take"))).build())
            .addAction(Notification.Action.Builder(null, plan.texts.promise, activity(c, 4, opening(c, plan, lead.group, "action", "promise"))).build())
        if (loud) b.addAction(Notification.Action.Builder(null, plan.texts.notNow, broadcast(c, 5, NagReceiver.NOT_NOW, live.nag)).build())
        picture(c, plan, rung)?.let { b.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT >= 34) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        Nuisances.nag(c, b, live, loud)
        return b.build()
    }

    /** A promise: its deadline counting down, promoted to a Live Update where the platform and the person allow it.
     * Dismissing it is no ignore; it is not shown again for that promise. */
    fun promise(c: Context, live: Live): Notification? {
        val plan = live.plan
        val until = live.promised.mapNotNull { it.until }.minOrNull() ?: return null
        if (live.st.promiseDismissed == until) return null
        val lead = live.promised.minByOrNull { it.until ?: Long.MAX_VALUE }!!
        val names = live.names(live.promised)
        val b = base(c, CH_WAITING, plan)
            .setContentTitle(fill(c, plan.texts.promiseChip, names, until))
            .setCategory(Notification.CATEGORY_REMINDER).setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setContentIntent(activity(c, 6, opening(c, plan, lead.group, "promise")))
            .setDeleteIntent(broadcast(c, 7, NagReceiver.PROMISE_DISMISSED, at = until))
        Nuisances.promise(c, b, live)
        return b.build()
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

    fun post(c: Context, id: Int, n: Notification?) { if (n == null) nm(c).cancel(id) else if (nm(c).areNotificationsEnabled()) nm(c).notify(id, n) }
}
