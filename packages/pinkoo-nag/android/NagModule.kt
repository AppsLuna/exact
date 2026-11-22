// @pinkoo/nag on Android: the module the session talks to while the app is in front. No JavaScript runs in the
// background: the data module sends a plan (`scheduleNags`) at every resume and change, and the engine (`Chain.kt`,
// `NagService`, the receivers) does the rest natively until the app is opened again, when the app reads what happened
// (`nagState`), records it, and acknowledges it (`ackNagEvents`).
//
// The interface says `"start": "load"`, so the module is made after the first frame of every launch: it sets the
// alarm again then, which a force-stop dropped on Android 14 and earlier (later releases send the boot broadcast at
// that launch).
package pinkoo.nag

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.exact.canvas.ExactExtension
import dev.exact.canvas.ExtensionContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

class NagModule(context: ExtensionContext) : ExactExtension(context) {
    companion object {
        /**
         * The longest the nag route may celebrate a confirmation over the lock screen before the keyguard covers it
         * again. The route says when its celebration ends (`leaveLockScreen`), which hands the screen back then; this is
         * the cap, for a route that never says.
         */
        const val CELEBRATE_MAX_MS = 5_000L
        /** `Settings.ACTION_ZEN_MODE_SETTINGS`, which the SDK hides: Do Not Disturb's page (Modes from Android 15). */
        const val ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
        /** Android's own volume slider for a stream, shown without changing it (`ADJUST_SAME`, `FLAG_SHOW_UI`); the tests
         * replace it, since Robolectric records no flags. */
        @Volatile internal var volumePanel: (AudioManager, Int) -> Unit = { a, stream -> a.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI) }
        @Volatile private var live: WeakReference<NagModule>? = null
        internal fun changed() { live?.get()?.let { it.context.changed("nag.nagState"); it.context.changed("nag.nagStatus") } }
    }

    private val app: Context get() = context.application
    private val main = Handler(Looper.getMainLooper())
    /**
     * The engine handed the screen back to the keyguard ([lockAgain]) and the activity hasn't been resumed since. Android
     * pauses it at once but keeps showing it until it sleeps (up to 5 s), so a route that says it is done then (a
     * celebration that outlasted the cap) must still keep its frame: Today would show over the lock screen otherwise.
     */
    private var handedBack = false
    /** The keyguard back over the app, unless she unlocked meanwhile. */
    private val lockAgain = Runnable { if (keyguardLocked()) { handedBack = true; context.showOverLock(false) } }

    init {
        if (!context.agent) {
            live = WeakReference(this)
            // Made at the first frame, after the activity resumed (a tap through the notification's alias, a launch):
            // no resume reaches this module for it, so it reads whether the activity is resumed now.
            front(context.resumed)
        }
    }

    override fun later(name: String, args: JSONObject, reply: (JSONObject) -> Unit) {
        when (name) {
            "nagStatus" -> reply(status())
            "nagState" -> reply(state())
            "scheduleNags" -> {
                val plan = args.optJSONObject("plan") ?: return reply(JSONObject().put("error", "TypeError"))
                // A plan that cannot be read is refused; the engine keeps reminding from the last one.
                reply(if (Engine.schedule(app, plan)) JSONObject() else JSONObject().put("error", "TypeError"))
            }
            "resolveNags" -> {
                val kind = args.optString("kind")
                if (kind !in setOf("taken", "skipped", "promised")) return reply(JSONObject().put("error", "TypeError"))
                val until = if (args.isNull("until") || !args.has("until")) null else args.optDouble("until").toLong()
                if (kind == "promised" && until == null) return reply(JSONObject().put("error", "TypeError"))
                val keys = strings(args.optJSONArray("keys"))
                Engine.resolve(app, keys, kind, until)
                if (!context.agent) celebrate(keys)
                reply(JSONObject())
            }
            // The nag screen's "Agora não": the next step moves to one gap after the tap, never sooner; keys the engine
            // does not know count nothing.
            "ignoreNags" -> { Engine.notNowKeys(app, strings(args.optJSONArray("keys"))); reply(JSONObject()) }
            "ackNagEvents" -> { args.optString("through").toLongOrNull()?.let { Engine.ack(app, it) }; reply(JSONObject()) }
            "nagScreen" -> {
                main.removeCallbacks(lockAgain)
                if (args.optBoolean("showing")) {
                    val keys = strings(args.optJSONArray("keys"))
                    Engine.screen = keys.toSet()
                    Engine.opened(app, keys)
                } else {
                    Engine.screen = emptySet()
                    context.showOverLock(false)
                }
                reply(JSONObject())
            }
            // The route shown over the lock screen is done (the celebration ended, "Agora não", the test's "Parar
            // teste"): the keyguard comes back in `after` ms, at most the celebration's cap. Whether it will: the route
            // then keeps its last frame, so what lies under it (Today) never shows over the lock screen.
            "leaveLockScreen" -> {
                val locking = !context.agent && keyguardLocked() && (context.resumed || handedBack)
                if (locking && context.resumed) {
                    main.removeCallbacks(lockAgain)
                    main.postDelayed(lockAgain, args.optDouble("after", 0.0).toLong().coerceIn(0, CELEBRATE_MAX_MS))
                }
                reply(JSONObject().put("locking", locking))
            }
            "requestNagAccess" -> request(args.optString("what"), args) { reply(status()) }
            "rehearse" -> {
                if (context.agent) return reply(JSONObject())
                if (args.optString("step") !in setOf("notify", "bubble", "full", "alarm")) return reply(JSONObject().put("error", "TypeError"))
                Rehearsal.start(app, args)
                reply(JSONObject())
            }
            "stopRehearsal" -> { Rehearsal.stop(app, "app"); reply(JSONObject()) }
            "previewSound" -> {
                // In front only: Android allows an app's sound while one of its activities shows.
                if (!context.agent && context.resumed) Preview.play(app, args.optString("sound"), args.optString("usage"), args.optDouble("volume", 1.0))
                reply(JSONObject())
            }
            "pickNagSound" -> pick(args.optString("current"), args.optString("title"), reply)
            "renewNagChannel" -> {
                val sound = args.optString("sound")
                if (sound.isNotEmpty()) Remind.renew(app, sound, Store.plan(app)?.texts?.channels?.get(sound))
                reply(status())
            }
            else -> reply(JSONObject().put("error", "NotSupportedError"))
        }
    }

    /** The bubble hides while the app's activity is resumed and comes back when it pauses. Back in front, everything is
     * set again: notifications turned back on in Settings meanwhile bring back the doses they silenced. */
    override fun resumed() = front(true)
    override fun paused() { Preview.stop(); front(false) }

    private fun front(shown: Boolean) {
        if (shown) handedBack = false
        Engine.appInFront = shown
        // In front the bubble hides at once, however the app came there; the engine's refresh brings it back after, and
        // the experience test's bubble shows once she leaves.
        if (shown && Bubble.shown) Bubble.hide()
        if (!shown && !context.agent) Rehearsal.away(app)
        if (!context.agent) Engine.restore(app, front = shown)
    }

    /** The person may have changed a switch in Settings meanwhile. */
    override fun foreground(shown: Boolean) { if (shown) changed() }

    override fun destroy(done: () -> Unit) {
        main.removeCallbacks(lockAgain)
        Preview.release()
        // A module that goes after the next one was made leaves the activity's state to that one.
        if (live?.get() === this) { live = null; Engine.appInFront = false; Engine.screen = emptySet() }
        done()
    }

    /**
     * Decision 5: [keys] confirmed (or promised) on the route the full screen showed over the lock screen, and nothing
     * of their reminder left to do: the route celebrates, says when it ends (`leaveLockScreen`), and the keyguard covers
     * the app again then, at the latest [CELEBRATE_MAX_MS] after the confirmation. A new reminder shown meanwhile
     * (`nagScreen`) keeps it. A promise is one dose's: while another dose is open and not promised, the route goes on to
     * it and the keyguard stays away ([Engine.settled]).
     */
    private fun celebrate(keys: List<String>) {
        if (!overLock() || !Engine.settled(app, keys)) return
        main.removeCallbacks(lockAgain)
        main.postDelayed(lockAgain, CELEBRATE_MAX_MS)
    }

    private fun keyguardLocked(): Boolean = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** The app shows over a locked keyguard: its activity can be resumed under one only over it, which an over-lock
     * activation alone allows. */
    private fun overLock(): Boolean = context.resumed && keyguardLocked()

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }

    private fun state(): JSONObject {
        val plan = Store.plan(app)
        val st = Store.state(app)
        val doses = if (plan == null) JSONArray() else JSONArray(Rules.doses(plan, st, Chain.exact(app)))
        return JSONObject().put("doses", doses).put("events", JSONArray(Store.events(app).map { it.json() }))
            .put("front", plan?.let { front(it, st) } ?: JSONObject.NULL)
    }

    /**
     * Decisions 7 and 17: the reminder that fired with the app in front and the nag screen has not shown yet. For a
     * notification step (`step`: the dose time's, De novo, a repeat, a deadline) the app shows the banner and plays her
     * sound itself (`previewSound`), or only the sound on Hoje; for the full screen and the alarm it opens `route` at
     * once (`?via=fsi`: the alarm rings there until her first touch), or a banner while she is in a form. Showing one of
     * its doses (`nagScreen`) clears it. `nag` is new for every step that fires, so the app acts once per step; `keys`
     * and `names` are the lead dose time's open doses under no promise, `at` when it fired.
     *
     * Item 20: a step that came due in front with notifications off showed nothing ([State.silent]). Its route says
     * `?via=silent`: the nag screen tells the engine it shows the dose at its first show. `at` is when it came due.
     */
    private fun front(plan: Plan, st: State): JSONObject? = Rules.front(plan, st)?.let { g ->
        val doses = Rules.nagged(plan, st, g)
        val silent = st.front == st.silent
        JSONObject().put("nag", st.front).put("group", g.group).put("route", Shade.route(plan, g.group, if (silent) "silent" else "fsi"))
            .put("keys", JSONArray(doses.map { it.key })).put("names", JSONArray(doses.map { it.name }.distinct()))
            .put("at", if (silent) st.silentAt else g.lastFiredAt ?: 0L)
            .put("step", Rules.word(Rules.frontStep(plan, st, g) ?: Step.NOTIFY))
    }

    // MARK: the status (Como eu te lembro)

    private fun notifications(): String {
        val enabled = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33) return if (enabled) "granted" else "denied"
        val shared = context.permission(Manifest.permission.POST_NOTIFICATIONS).state
        return if (shared != "prompt" && !enabled) "denied" else shared
    }

    /**
     * What the phone allows (item 13): each access, the battery's restriction (which switches the reminders to the
     * alarm clock, item 6), the ringer mode (silent mutes a notification's vibration too), each stream's level over its
     * maximum, whether Do Not Disturb lets alarms through and reminders (only a hint under Android 15's modes), and each
     * reminder channel read back.
     */
    private fun status(): JSONObject {
        val nm = app.getSystemService(NotificationManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        val audio = app.getSystemService(AudioManager::class.java)
        val promoted = when {
            !LiveUpdate.platform() -> "unavailable"
            nm.canPostPromotedNotifications() -> "granted"
            else -> "denied"
        }
        val filter = nm.currentInterruptionFilter
        val policy = runCatching { nm.notificationPolicy }.getOrNull()
        fun lets(category: Int): Boolean = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> false
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> category == NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> policy?.let { it.priorityCategories and category != 0 } ?: true
            else -> true
        }
        fun level(stream: Int) = audio.getStreamVolume(stream).toDouble() / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return JSONObject().put("available", true).put("notifications", notifications()).put("channelBlocked", Shade.blocked(app))
            .put("exactAlarms", Chain.exact(app))
            .put("fullScreen", FullScreen.allowed(app)).put("overlay", Settings.canDrawOverlays(app)).put("promoted", promoted)
            .put("batteryUnrestricted", power.isIgnoringBatteryOptimizations(app.packageName))
            .put("backgroundRestricted", app.getSystemService(ActivityManager::class.java).isBackgroundRestricted)
            .put("standbyBucket", app.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
            .put("alarmVolume", level(AudioManager.STREAM_ALARM)).put("notificationVolume", level(AudioManager.STREAM_NOTIFICATION))
            .put("ringerMode", ringer)
            .put("dndAllowsAlarms", lets(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS))
            .put("dndAllowsReminders", lets(NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS))
            .put("channels", Remind.status(app))
            .put("maker", Build.MANUFACTURER ?: "")
    }

    // MARK: the request: the one screen that grants [what], then the status

    private fun request(what: String, args: JSONObject, done: () -> Unit) {
        // Do Not Disturb's own page: on a Pixel with Android 15 or later it is Modes, which lists it (the sound page and
        // Notifications no longer have it there). The action is the platform's but hidden from the SDK. Where no app
        // opens it, Android's sound page.
        if (what == "dnd") {
            context.startActivityForResult(Intent(ZEN_MODE_SETTINGS), { why ->
                if (why == "NotFoundError") context.startActivityForResult(Intent(Settings.ACTION_SOUND_SETTINGS)) { _, _ -> done() } else done()
            }) { _, _ ->
                Engine.restore(app)
                done()
            }
            return
        }
        // "Ajustar no Android": Android's own volume slider for the stream, shown without changing it; Pinkoo never sets
        // the system volume. On One UI the alarm's row may not show (its volume lives in Relógio).
        if (what == "volume") {
            val stream = if (args.optString("stream") == "alarm") AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
            runCatching { volumePanel(app.getSystemService(AudioManager::class.java), stream) }
                .onFailure { android.util.Log.w("pinkoo.nag", "volume: $it") }
            return done()
        }
        val pkg = Uri.parse("package:${app.packageName}")
        val intent = when (what) {
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= 33 && notifications() == "prompt") return context.askPermission(Manifest.permission.POST_NOTIFICATIONS, done)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            }
            // A reminder channel's own page (one she blocked or changed): Android keeps her settings, so Pinkoo never
            // remakes it under a new id behind her back.
            "channel" -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Remind.id(app, args.optString("sound").ifEmpty { Sounds.DEFAULT }))
            "exactAlarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            "fullScreen" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "promoted" -> if (LiveUpdate.platform()) Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName) else null
            // Pinkoo's own battery page ("Arrumar" on the restricted-battery row): Android's list where it has none.
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            // One UI's "Never sleeping apps" list (Samsung, "App management"); elsewhere the app's own settings.
            "sleeping" -> Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").putExtra("activity_type", 2)
                .takeIf { it.resolveActivity(app.packageManager) != null } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> null
        } ?: return done()
        context.startActivityForResult(intent) { _, _ ->
            // An access granted re-arms what it allows (exact alarms, the full screen, the bubble).
            Engine.restore(app)
            done()
        }
    }

    // MARK: the phone's sounds

    /**
     * "Um som do celular" (item 8): Android's sound picker. Android swaps a sound the app can't read for its default,
     * silently and in the posted notification only, and a sound she added herself needs a media permission the grant
     * table lacks, so the picked sound is opened from Pinkoo's own process first and refused when it can't be
     * (`unreadable`). The answer is the canonical address (MediaStore ids can change after an update), which then
     * names that dose's channel, and the sound's title.
     */
    private fun pick(current: String, title: String, reply: (JSONObject) -> Unit) {
        fun answer(status: String, sound: String = "", name: String = "") = reply(JSONObject().put("status", status).put("sound", sound).put("title", name))
        if (context.agent) return answer("cancelled")
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
        if (title.isNotEmpty()) intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        if (Sounds.phone(current)) intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
        context.startActivityForResult(intent, { answer("cancelled") }) { _, data ->
            @Suppress("DEPRECATION")
            val uri: Uri = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return@startActivityForResult answer("cancelled")
            if (!Sounds.readable(app, uri)) return@startActivityForResult answer("unreadable")
            val canonical = runCatching { app.contentResolver.canonicalize(uri) }.getOrNull() ?: uri
            val name = runCatching { RingtoneManager.getRingtone(app, uri)?.getTitle(app) }.getOrNull() ?: ""
            answer("picked", canonical.toString(), name)
        }
    }
}
