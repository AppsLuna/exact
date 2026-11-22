// The engine's notifications and channels: one reminder notification for every open dose time, one for a promise,
// one each for the low-stock and the stale notices, the ring service's own and the experience test's. The texts are the
// plan's, already localized; times are formatted here. A reminder's lock-screen version is public without the
// medication's name; the alarm's and the test's notifications are public and name no medication, so "Parar" shows
// whole on a lock screen that hides sensitive content.
//
// Every step that sounds is a new notification ([post]): Android plays a channel's sound only for a notification
// posted anew, and launches a full-screen intent only then too, so the reminder's id alternates between two from one
// step to the next ([nagId]) and the other one is cancelled. Silent refreshes ([refresh]) stay in place with
// `setOnlyAlertOnce` on her reminder channel, posted only when what they show changed.
//
// Her reminder channels ([Remind]) are one per sound a plan names, made lazily, versioned, never remade under a new id
// when she blocked or muted one, and deleted only when no plan names them. `docs/exact.md` › Planned: the gentle
// reminder, items 4, 5 and 17; `docs/architecture.md` › Nagging on the phone.
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
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import dev.exact.canvas.ExtensionContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Her reminder channels, one per sound: a preset (`pinkoo.remind.sininho`), "Só vibrar" (`pinkoo.remind.vibrate`),
 * "Sem som" (`pinkoo.remind.silent`) or a phone sound (`pinkoo.remind.phone.<hash of its canonical address>`). Each
 * is versioned: a channel made again under the same id brings back what she changed in Android, so only
 * [renew] ("Voltar pro {som}") makes a new version, `.v2` and on. Importance high, `USAGE_NOTIFICATION` with
 * `CONTENT_TYPE_SONIFICATION`; presets and phone sounds have a soft vibration, "Só vibrar" the vibration alone, "Sem
 * som" neither. A channel's sound, importance and vibration are fixed when it is made: only she changes them after.
 */
internal object Remind {
    /** The soft vibration every sounding reminder channel and "Só vibrar" have. */
    val SOFT = longArrayOf(0, 180, 120, 180)

    private val attributes: AudioAttributes get() =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    /** Each sound's version, in device-protected storage (the receivers post before the first unlock). */
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("pinkoo.nag.channels", Context.MODE_PRIVATE)

    /** [sound]'s part of its channel id. */
    fun key(sound: String): String = when {
        sound == "vibrate" || sound == "silent" -> sound
        Sounds.phone(sound) -> "phone." + MessageDigest.getInstance("SHA-256").digest(sound.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        else -> sound.lowercase().filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { Sounds.DEFAULT }
    }

    fun version(c: Context, sound: String): Int = prefs(c).getInt("v:" + key(sound), 1)

    /** [sound]'s channel id, at its version. */
    fun id(c: Context, sound: String): String {
        val base = Shade.CH_REMIND + key(sound)
        val v = version(c, sound)
        return if (v <= 1) base else "$base.v$v"
    }

    /** What [sound]'s channel was made to play: null for "Só vibrar" and "Sem som". */
    fun uri(c: Context, sound: String): Uri? = Sounds.notification(c, sound)

    /** [sound]'s channel, made when missing and renamed to [name] (the plan's "Lembrete · {som}"); its id. A channel she
     * blocked or changed is only renamed: Android keeps her settings. */
    fun ensure(c: Context, sound: String, name: String?): String {
        val id = id(c, sound)
        val nm = nm(c)
        val there = nm.getNotificationChannel(id)
        val label = name?.takeIf { it.isNotEmpty() } ?: there?.name?.toString() ?: sound
        if (there != null) {
            // Only renamed: the channel as Android has it, her changes included, with its new name.
            if (there.name?.toString() != label) nm.createNotificationChannel(there.apply { setName(label) })
            prefs(c).edit().putString("s:$id", sound).apply()
            return id
        }
        nm.createNotificationChannel(NotificationChannel(id, label, NotificationManager.IMPORTANCE_HIGH).apply {
            when (sound) {
                "silent" -> { setSound(null, null); enableVibration(false) }
                "vibrate" -> { setSound(null, null); enableVibration(true); vibrationPattern = SOFT }
                else -> { setSound(uri(c, sound), attributes); enableVibration(true); vibrationPattern = SOFT }
            }
        })
        prefs(c).edit().putString("s:$id", sound).apply()
        return id
    }

    /** "Voltar pro {som}": she changed [sound]'s channel in Android, so a new version is made as Pinkoo sets it, and the
     * old one goes. A blocked channel is never renewed: its row opens Android's page for it. */
    fun renew(c: Context, sound: String, name: String?): String {
        val old = id(c, sound)
        val ch = nm(c).getNotificationChannel(old)
        if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return old
        prefs(c).edit().putInt("v:" + key(sound), version(c, sound) + 1).apply()
        val id = ensure(c, sound, name ?: ch?.name?.toString())
        if (ch != null) runCatching { nm(c).deleteNotificationChannel(old) }
        return id
    }

    /** Every reminder channel no plan names now ([keep], sounds) deleted: rarely, since Android lists a count of
     * deleted categories in Pinkoo's notification settings. */
    fun prune(c: Context, keep: Set<String>) {
        val ids = keep.map { id(c, it) }.toSet()
        for (ch in runCatching { nm(c).notificationChannels }.getOrDefault(emptyList())) {
            if (ch.id.startsWith(Shade.CH_REMIND) && ch.id !in ids) runCatching { nm(c).deleteNotificationChannel(ch.id) }
        }
    }

    /** The sound a channel id was made for, or null when it is not one of hers. */
    fun soundOf(c: Context, id: String): String? = prefs(c).getString("s:$id", null)

    /**
     * Each reminder channel read back (`nagStatus().channels`): its importance, whether she blocked it, and whether
     * she changed it in Android (a quieter importance, another sound or the vibration). Android's swap of an unreadable
     * sound for its default can't be read here: [Sounds.readable] guards that at the pick.
     */
    fun status(c: Context): JSONArray {
        val out = JSONArray()
        for (ch in runCatching { nm(c).notificationChannels }.getOrDefault(emptyList())) {
            if (!ch.id.startsWith(Shade.CH_REMIND)) continue
            val sound = soundOf(c, ch.id) ?: continue
            val blocked = ch.importance == NotificationManager.IMPORTANCE_NONE
            val vibrates = sound != "silent"
            val changed = !blocked && (ch.importance < NotificationManager.IMPORTANCE_HIGH || ch.sound != uri(c, sound) ||
                ch.shouldVibrate() != vibrates)
            out.put(JSONObject().put("sound", sound).put("id", ch.id).put("importance", ch.importance).put("blocked", blocked).put("changed", changed))
        }
        return out
    }
}

internal object Shade {
    const val NAG = 0x6e6100
    const val PROMISE = NAG + 1
    const val STOCK = NAG + 2
    const val STALE = NAG + 3
    /** The reminder's other id ([nagId]). */
    const val NAG_ALT = NAG + 4
    /** The experience test's notification ([Rehearsal]). */
    const val TEST = NAG + 5
    /** The ring service's own notification ([NagService]): never the reminder. */
    const val SERVICE = NAG + 6
    /** "Tela cheia e alarme": high and silent, for the full screen and the alarm's notification (the service rings). */
    const val CH_NAG = "pinkoo.nag"
    /** Low: posts while Pinkoo is in front, the low-stock notice and the service's own notification. */
    const val CH_WAITING = "pinkoo.nag.waiting"
    const val CH_NOTICE = "pinkoo.nag.notice"
    /** Her reminder channels' ids start so ([Remind]). */
    const val CH_REMIND = "pinkoo.remind."
    /** The old ladder's loud nag that sounded itself without exact access: gone, the alarm needs that access. */
    const val CH_RING_GONE = "pinkoo.nag.ring"
    /** A notification's extras: the nag it shows, and what it shows. */
    const val EXTRA_NAG = "pinkoo.nag.nag"
    private const val EXTRA_LOOK = "pinkoo.nag.look"
    /** The group a fresh post that must not sound joins, alerting only for a summary that never comes. */
    private const val QUIET = "pinkoo.nag.quiet"
    /** How long the notification's "Agora não" confirmation shows. */
    const val CONFIRM_MS = 5_000L
    const val BRAND = 0xFFFF7A59.toInt()

    /** The reminder's id for nag [nag]: odd and even nags alternate, so each step that sounds is a new notification. */
    fun nagId(nag: Int): Int = if (nag % 2 == 0) NAG_ALT else NAG

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    // MARK: channels

    /** The engine's own channels, named in the plan's language (made once, renamed when the language changes). */
    fun base(c: Context, t: Texts?) {
        val nm = nm(c)
        nm.createNotificationChannel(NotificationChannel(CH_NAG, t?.channelNag?.ifEmpty { null } ?: CH_NAG, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WAITING, t?.channelPending?.ifEmpty { null } ?: CH_WAITING, NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_NOTICE, t?.channelNotice?.ifEmpty { null } ?: CH_NOTICE, NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Made on the spot when missing (the experience test from the onboarding, before any plan). */
    fun baseIfMissing(c: Context) { if (nm(c).getNotificationChannel(CH_WAITING) == null || nm(c).getNotificationChannel(CH_NAG) == null) base(c, Store.plan(c)?.texts) }

    /** Every sound [plan]'s doses name: the reminder channels it needs (the texts only name them). */
    fun sounds(plan: Plan): Set<String> = plan.doses.map { it.sound }.filter { it.isNotEmpty() }.toSet()

    /**
     * A plan's channels: the engine's own, one reminder channel per sound it names (made lazily, renamed to its
     * language), the reminder channels it no longer names deleted (all of them with the empty plan "Apagar meus dados"
     * sends: channels outlive an app's data), and the old ladder's ring channel gone. Called from `Engine.schedule`,
     * before the plan's first post.
     */
    fun channels(c: Context, plan: Plan) {
        base(c, plan.texts)
        runCatching { if (nm(c).getNotificationChannel(CH_RING_GONE) != null) nm(c).deleteNotificationChannel(CH_RING_GONE) }
        val sounds = sounds(plan)
        for (s in sounds) Remind.ensure(c, s, plan.texts.channels[s])
        Remind.prune(c, sounds)
    }

    fun blocked(c: Context): Boolean = nm(c).getNotificationChannel(CH_NAG)?.importance == NotificationManager.IMPORTANCE_NONE

    /** Whether anything Pinkoo posts can reach her (notifications on, the alarm's channel not blocked). */
    fun stoppable(c: Context): Boolean = nm(c).areNotificationsEnabled() && !blocked(c)

    /** The app's notification glyph (`monochrome` in app.json), else the platform's alarm clock. */
    fun icon(c: Context): Int = c.resources.getIdentifier("exact_notification", "drawable", c.packageName).takeIf { it != 0 }
        ?: android.R.drawable.ic_lock_idle_alarm

    // MARK: intents

    fun route(plan: Plan, group: String, via: String, action: String? = null): String {
        val path = plan.route.replace("{group}", Uri.encode(group))
        return path + (if ('?' in path) "&" else "?") + "via=$via" + (action?.let { "&a=$it" } ?: "")
    }

    /** The app at the nag route: a tap's, an action's, the full screen's (over the keyguard), the bubble's or an
     * unlock's. */
    fun opening(c: Context, plan: Plan, group: String, via: String, action: String? = null, overLock: Boolean = false): Intent =
        ExtensionContext.activation(c, "nag", "", JSONArray(), route(plan, group, via, action), overLock)

    /** The app at [route] (the experience test's): over the keyguard when [overLock]. */
    fun openingAt(c: Context, route: String, overLock: Boolean): Intent =
        ExtensionContext.activation(c, "nag", "", JSONArray(), if (route.startsWith("/")) route else "/", overLock)

    fun activity(c: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(c, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun broadcast(c: Context, code: Int, action: String, nag: Int = 0, at: Long = 0): PendingIntent = PendingIntent.getBroadcast(c, code,
        Intent(c, NagReceiver::class.java).setAction(action).putExtra("nag", nag).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The alarm's stop ([AndroidOut.STOP]), or the test's ([Rehearsal.STOP]): a broadcast, so "Parar" works on the
     * locked phone. */
    fun stop(c: Context, code: Int, nag: Int, why: String, test: Boolean = false): PendingIntent = PendingIntent.getBroadcast(c, code,
        Intent(c, NagReceiver::class.java).setAction(if (test) Rehearsal.STOP else AndroidOut.STOP).putExtra("nag", nag).putExtra("why", why),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** A time in the notification's words: always 24 h ("12:10"), as the app writes every time. */
    fun time(@Suppress("UNUSED_PARAMETER") c: Context, at: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(at))

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

    private fun builder(c: Context, channel: String): Notification.Builder = Notification.Builder(c, channel)
        .setSmallIcon(icon(c)).setColor(BRAND)

    // MARK: posting only what changed

    /** What each notification last posted shows, by id. In memory; the notification's own extras carry it across a
     * process death. */
    private val shown = HashMap<Int, String>()
    /** The notification's "Agora não" confirmation showing: its nag, and until when (uptime). */
    private var confirming: Pair<Int, Long>? = null

    fun forget() = synchronized(shown) { shown.clear(); confirming = null }

    fun showing(c: Context): List<StatusBarNotification> = runCatching { nm(c).activeNotifications.toList() }.getOrDefault(emptyList())

    /** [n] posted as [id], unless notifications are off. */
    fun notify(c: Context, id: Int, n: Notification) { if (nm(c).areNotificationsEnabled()) nm(c).notify(id, n) }

    fun cancel(c: Context, id: Int) {
        nm(c).cancel(id)
        synchronized(shown) { shown.remove(id) }
    }

    // MARK: the reminder

    private enum class Kind { REMIND, FULL, ALARM }

    /** What the reminder says, before it is built: equal ones show the same. */
    private data class Look(
        val kind: Kind, val channel: String, val title: String, val line: String, val sub: String, val at: Long?,
        val rung: Int, val lead: String, val nag: Int, val plan: Long,
    )

    /**
     * How [p] shows. The alarm's own notification while it rings (or at its post); the full screen when a fresh post
     * enters Tela cheia and Android would open it (the access granted, the phone locked or dark, Do Not Disturb off);
     * otherwise her reminder channel, or the waiting channel in front (decision 17: no heads-up over the app).
     */
    private fun look(c: Context, plan: Plan, p: Post, fresh: Boolean): Look {
        val lead = p.groups.firstOrNull() ?: ""
        val names = p.names.joinToString(", ")
        val at = runCatching { WallClock.epoch(lead) }.getOrNull()
        val sub = fill(c, plan.texts.body, names, at)
        val alarm = p.alarm && (fresh || NagService.ringing(p.nag))
        return when {
            alarm -> Look(Kind.ALARM, if (p.front) CH_WAITING else CH_NAG, plan.texts.alarmTitle, plan.texts.alarmBody, "", at, p.rung, lead, p.nag, plan.made)
            fresh && p.fullScreen && !p.front && FullScreen.allowed(c) && FullScreen.now(c) ->
                Look(Kind.FULL, CH_NAG, p.title, p.line.ifEmpty { sub }, sub, at, p.rung, lead, p.nag, plan.made)
            else -> Look(Kind.REMIND, if (p.front) CH_WAITING else Remind.ensure(c, p.channel, plan.texts.channels[p.channel]),
                p.title, p.line.ifEmpty { sub }, sub, at, p.rung, lead, p.nag, plan.made)
        }
    }

    /**
     * A step fired ([Out.post]): posted anew, the other id cancelled, so it sounds on her channel when [Post.sounding]
     * (and launches its full screen); a post that must not sound (folded into the alarm, in front) is posted silently.
     * Idempotent: a nag already posted is only refreshed.
     */
    fun post(c: Context, plan: Plan, p: Post) {
        val id = nagId(p.nag)
        val there = showing(c).firstOrNull { it.id == id }
        if (there != null && there.notification.extras.getInt(EXTRA_NAG, -1) == p.nag) return refresh(c, plan, p)
        val look = look(c, plan, p, fresh = true)
        // The one showing at this id is an older step's: it goes first, so this one is new and alerts.
        if (there != null) nm(c).cancel(id)
        // Tela cheia is silent over the lock screen; where Android wouldn't open it, it is her reminder, sounding.
        val sounds = p.sounding || (p.fullScreen && !p.front && look.kind == Kind.REMIND)
        val quiet = !sounds && look.channel.startsWith(CH_REMIND)
        notify(c, id, build(c, plan, p, look, quiet))
        synchronized(shown) { shown[id] = look.toString(); confirming = null }
        cancelOther(c, id)
    }

    /**
     * The same nag, changed or not ([Out.refresh]): in place and silent (`setOnlyAlertOnce`), on her reminder channel
     * (the waiting one in front), posted only when what it shows changed. With nothing showing at its id (a process
     * death, a channel's notification cleared) it is posted silently. The "Agora não" confirmation keeps its 5 s.
     */
    fun refresh(c: Context, plan: Plan, p: Post) {
        val id = nagId(p.nag)
        confirming?.let { (nag, until) -> if (nag == p.nag && SystemClock.uptimeMillis() < until) return }
        val look = look(c, plan, p, fresh = false)
        val there = showing(c).firstOrNull { it.id == id }
        val key = look.toString()
        if (there != null && (synchronized(shown) { shown[id] } ?: there.notification.extras.getString(EXTRA_LOOK)) == key) return
        notify(c, id, build(c, plan, p, look, quiet = there == null))
        synchronized(shown) { shown[id] = key }
        cancelOther(c, id)
    }

    private fun cancelOther(c: Context, id: Int) {
        val other = if (id == NAG) NAG_ALT else NAG
        if (synchronized(shown) { shown.containsKey(other) } || showing(c).any { it.id == other }) cancel(c, other)
    }

    /** The reminder gone, both ids ([Out.clear]). */
    fun clear(c: Context) {
        val there = showing(c).map { it.id }
        for (id in listOf(NAG, NAG_ALT)) if (id in there || synchronized(shown) { shown.containsKey(id) }) cancel(c, id)
        synchronized(shown) { confirming = null }
    }

    /**
     * The notification's "Agora não" answered ([Out.confirm]): [nag]'s post turns, silently and in place, into [text]
     * for 5 s (`setTimeoutAfter`), then goes. Its delete intent, which the timeout sends too, is the dismissal: the
     * reminder then stays away until the next step, as after a swipe.
     */
    fun confirm(c: Context, nag: Int, text: String) {
        val id = nagId(nag)
        val there = showing(c).firstOrNull { it.id == id }
        val channel = there?.notification?.channelId ?: CH_WAITING
        val b = builder(c, channel).setContentTitle(text).setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true).setShowWhen(false).setTimeoutAfter(CONFIRM_MS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(broadcast(c, 2, NagReceiver.DISMISS, nag))
            .addExtras(android.os.Bundle().apply { putInt(EXTRA_NAG, nag) })
        if (there == null) b.setGroup(QUIET).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        notify(c, id, b.build())
        synchronized(shown) { shown[id] = "confirm|$nag|$text"; confirming = nag to SystemClock.uptimeMillis() + CONFIRM_MS }
        cancelOther(c, id)
    }

    /**
     * The reminder as [look] says. A reminder: her channel (or the full screen's, silent), the reminder category,
     * not ongoing (a swipe dismisses it anywhere), "Tomei", "Tomo daqui a…" and "Agora não" on every step, and a public
     * version without the medication's name. The alarm's: the alarm category, ongoing, public with neutral text and
     * "Parar" (a broadcast, so it works on the locked phone); its delete intent stops the ring too.
     */
    private fun build(c: Context, plan: Plan, p: Post, look: Look, quiet: Boolean): Notification {
        val t = plan.texts
        val b = builder(c, look.channel).setContentTitle(look.title).setContentText(look.line)
            .setOnlyAlertOnce(true).setShowWhen(true).setWhen(look.at ?: System.currentTimeMillis())
            .addExtras(android.os.Bundle().apply { putInt(EXTRA_NAG, p.nag); putString(EXTRA_LOOK, look.toString()) })
        when (look.kind) {
            Kind.ALARM -> {
                b.setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
                    .setStyle(Notification.BigTextStyle().bigText(look.line))
                    .setContentIntent(activity(c, 1, opening(c, plan, look.lead, "tap")))
                    .setDeleteIntent(stop(c, 21, p.nag, "swipe"))
                    .addAction(Notification.Action.Builder(null, t.stop, stop(c, 20, p.nag, "stop")).build())
                if (p.fullScreen && !p.front && FullScreen.allowed(c)) b.setFullScreenIntent(activity(c, 10, opening(c, plan, look.lead, "fsi", overLock = true)), true)
            }
            else -> {
                b.setSubText(look.sub).setStyle(Notification.BigTextStyle().bigText(look.line))
                    .setCategory(Notification.CATEGORY_REMINDER).setOngoing(false)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(builder(c, look.channel).setContentTitle(t.publicTitle).setContentText(t.publicBody).build())
                    .setContentIntent(activity(c, 1, opening(c, plan, look.lead, "tap")))
                    .setDeleteIntent(broadcast(c, 2, NagReceiver.DISMISS, p.nag))
                    .addAction(Notification.Action.Builder(null, t.take, activity(c, 3, opening(c, plan, look.lead, "action", "take"))).build())
                    .addAction(Notification.Action.Builder(null, t.promise, activity(c, 4, opening(c, plan, look.lead, "action", "promise"))).build())
                    .addAction(Notification.Action.Builder(null, t.notNow, broadcast(c, 5, NagReceiver.NOT_NOW, p.nag)).build())
                picture(c, plan, look.rung)?.let { b.setLargeIcon(it) }
                if (look.kind == Kind.FULL) b.setFullScreenIntent(activity(c, 10, opening(c, plan, look.lead, "fsi", overLock = true)), true)
            }
        }
        if (quiet) b.setGroup(QUIET).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        return b.build()
    }

    // MARK: the promise and the notices

    /** A promise: its deadline counting down, promoted to a Live Update where the platform and the person allow it.
     * Dismissing it answers nothing; it is not shown again for that promise. Posted only when it changed. */
    fun promise(c: Context, plan: Plan, st: State) {
        val promised = Rules.promised(plan, st)
        val until = promised.mapNotNull { Rules.until(st, it) }.minOrNull()
        val lead = promised.minByOrNull { Rules.until(st, it) ?: Long.MAX_VALUE }
        val there = showing(c)
        if (until == null || lead == null || st.promiseDismissed == until) {
            if (synchronized(shown) { shown.containsKey(PROMISE) } || there.any { it.id == PROMISE }) cancel(c, PROMISE)
            return
        }
        val names = promised.map { it.name }.distinct().joinToString(", ")
        val title = fill(c, plan.texts.promiseChip, names, until)
        val live = LiveUpdate.allowed(c) && plan.liveUpdate
        val look = listOf(title, until, lead.group, live, plan.made).toString()
        if (there.any { it.id == PROMISE } && synchronized(shown) { shown[PROMISE] } == look) return
        val b = builder(c, CH_WAITING).setContentTitle(title).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(builder(c, CH_WAITING).setContentTitle(plan.texts.publicTitle).setContentText(plan.texts.publicBody).build())
            .setCategory(Notification.CATEGORY_REMINDER).setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setContentIntent(activity(c, 6, opening(c, plan, lead.group, "promise")))
            .setDeleteIntent(broadcast(c, 7, NagReceiver.PROMISE_DISMISSED, at = until))
        if (live) LiveUpdate.promote(b)
        notify(c, PROMISE, b.build())
        synchronized(shown) { shown[PROMISE] = look }
    }

    fun stock(c: Context, plan: Plan): Notification? {
        val s = plan.stock ?: return null
        // It rides on a dose's reminder, so it makes no sound of its own: the waiting channel's.
        return builder(c, CH_WAITING).setContentTitle(s.title).setContentText(s.body).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setStyle(Notification.BigTextStyle().bigText(s.body))
            .setContentIntent(activity(c, 8, ExtensionContext.activation(c, "nag", "", JSONArray(), "/"))).build()
    }

    fun stale(c: Context, plan: Plan): Notification = builder(c, CH_NOTICE)
        .setContentTitle(plan.idle.title).setContentText(plan.idle.body).setAutoCancel(true)
        .setStyle(Notification.BigTextStyle().bigText(plan.idle.body))
        .setContentIntent(activity(c, 9, ExtensionContext.activation(c, "nag", "", JSONArray(), "/"))).build()

    fun post(c: Context, id: Int, n: Notification?) { if (n == null) cancel(c, id) else notify(c, id, n) }

    // MARK: the ring service's own notification

    /** The ring service's foreground notification: its own, low and ongoing on the waiting channel ("Bolota está por
     * aqui"), so the reminder stays swipeable and keeps its flags when the service stops. */
    fun service(c: Context, words: String): Notification {
        baseIfMissing(c)
        return builder(c, CH_WAITING).setContentTitle(words.ifEmpty { "Pinkoo" }).setOngoing(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(activity(c, 13, ExtensionContext.activation(c, "nag", "", JSONArray(), "/"))).build()
    }
}
