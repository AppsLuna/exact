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
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Vibrator
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
        /** The latest a ring test may be asked for, and how long its ring lasts unanswered. */
        const val PREVIEW_AFTER_MAX_MS = 60_000L
        const val PREVIEW_RING_MS = 20_000L
        @Volatile private var live: WeakReference<NagModule>? = null
        internal fun changed() { live?.get()?.let { it.context.changed("nag.nagState"); it.context.changed("nag.nagStatus") } }
    }

    private val app: Context get() = context.application
    private var preview: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    /** The keyguard back over the app, unless she unlocked meanwhile. */
    private val lockAgain = Runnable { if (keyguardLocked()) context.showOverLock(false) }
    /** The ring test's ring, due ([previewFire]) and then ringing until [previewEnd]. */
    private var previewRung = 0
    private var previewOpen: Intent? = null
    private var previewTitle = ""
    private var previewBody = ""
    private var previewWake: PowerManager.WakeLock? = null
    private var previewRinging = false
    private val previewFire = Runnable { previewRing() }
    private val previewEnd = Runnable { previewStop(timedOut = true) }
    /** TalkBack (touch exploration) turned on or off: the status changes. */
    private val touchExploration = AccessibilityManager.TouchExplorationStateChangeListener { changed() }

    init {
        if (!context.agent) {
            live = WeakReference(this)
            // Made at the first frame, after the activity resumed (a tap through the notification's alias, a launch):
            // no resume reaches this module for it, so it reads whether the activity is resumed now.
            front(context.resumed)
            accessibility()?.addTouchExplorationStateChangeListener(touchExploration)
        }
    }

    override fun later(name: String, args: JSONObject, reply: (JSONObject) -> Unit) {
        when (name) {
            "nagStatus" -> reply(status())
            "nagState" -> reply(state())
            "scheduleNags" -> {
                val plan = args.optJSONObject("plan") ?: return reply(JSONObject().put("error", "TypeError"))
                // A plan that cannot be read is refused; the engine keeps nagging from the last one.
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
            // The nag screen's "Not now": keys the engine does not know, or a nag already ignored, count nothing.
            "ignoreNags" -> { Engine.ignoreKeys(app, strings(args.optJSONArray("keys"))); reply(JSONObject()) }
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
            // The route shown over the lock screen is done (the celebration ended, "Agora não", the ring test answered):
            // the keyguard comes back in `after` ms, at most the celebration's cap. Whether it will: the route then keeps
            // its last frame, so what lies under it (Today) never shows over the lock screen.
            "leaveLockScreen" -> {
                val locking = !context.agent && overLock()
                if (locking) {
                    main.removeCallbacks(lockAgain)
                    main.postDelayed(lockAgain, args.optDouble("after", 0.0).toLong().coerceIn(0, CELEBRATE_MAX_MS))
                }
                reply(JSONObject().put("locking", locking))
            }
            "requestNagAccess" -> request(args.optString("what")) { reply(status()) }
            "previewNag" -> {
                if (context.agent) return reply(JSONObject())
                preview(args.optInt("rung"), args.optDouble("after", 0.0).toLong(), args.optString("title"),
                    args.optString("body"), args.optString("route"))
                reply(JSONObject())
            }
            "stopNagPreview" -> { previewStop(timedOut = false); reply(JSONObject()) }
            else -> reply(JSONObject().put("error", "NotSupportedError"))
        }
    }

    /** The bubble hides while the app's activity is resumed and comes back when it pauses. Back in front, everything is
     * set again: notifications turned back on in Settings meanwhile bring back the doses they silenced. */
    override fun resumed() = front(true)
    override fun paused() = front(false)

    private fun front(shown: Boolean) {
        Engine.appInFront = shown
        if (!context.agent) Engine.restore(app, front = shown)
    }

    /** The person may have changed a switch in Settings meanwhile. */
    override fun foreground(shown: Boolean) { if (shown) changed() }

    override fun destroy(done: () -> Unit) {
        main.removeCallbacks(lockAgain)
        previewStop(timedOut = false)
        accessibility()?.removeTouchExplorationStateChangeListener(touchExploration)
        // A module that goes after the next one was made leaves the activity's state to that one.
        if (live?.get() === this) { live = null; Engine.appInFront = false; Engine.screen = emptySet() }
        done()
    }

    /**
     * Decision 5: [keys] confirmed (or promised) on the route the full-screen nag showed over the lock screen, and
     * nothing of their nag left to do: the route celebrates, says when it ends (`leaveLockScreen`), and the keyguard
     * covers the app again then, at the latest [CELEBRATE_MAX_MS] after the confirmation. A new nag shown meanwhile
     * (`nagScreen`) keeps it. A promise is one dose's: while another dose of the nag is open and not promised, the
     * route goes on to it and the keyguard stays away ([Engine.settled]).
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

    private fun accessibility(): AccessibilityManager? = app.getSystemService(AccessibilityManager::class.java)

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }

    private fun state(): JSONObject {
        val plan = Store.plan(app)
        val st = Store.state(app)
        val doses = if (plan == null) JSONArray() else JSONArray(Rules.doses(plan, st))
        return JSONObject().put("doses", doses).put("events", JSONArray(Store.events(app).map { it.json() }))
            .put("front", plan?.let { front(it, st) } ?: JSONObject.NULL)
    }

    /**
     * Decision 7: the nag that rang with the app in front and the nag screen has not shown yet. The app opens `route`
     * (`?via=fsi`: it rings there until her first touch) at once, or shows a banner while she is in a form and opens it
     * when she leaves; showing one of its doses (`nagScreen`) clears it. `nag` is new for every loud nag, so the app acts
     * once per nag; `keys` and `names` are the lead group's open doses under no promise (the ones it rang for), `at`
     * when it rang.
     *
     * Item 20: a nag that came due in front with notifications off rang nothing ([State.silent]). Its route says
     * `?via=silent`: the nag screen tells the engine it shows the dose at its first show, as when she opens it herself,
     * which ends the front (with `fsi` it would wait for a touch that silences a ring). `at` is when it came due.
     */
    private fun front(plan: Plan, st: State): JSONObject? = Rules.front(plan, st)?.let { g ->
        val doses = Rules.nagged(plan, st, g)
        val silent = st.front == st.silent
        JSONObject().put("nag", st.front).put("group", g.group).put("route", Shade.route(plan, g.group, if (silent) "silent" else "fsi"))
            .put("keys", JSONArray(doses.map { it.key })).put("names", JSONArray(doses.map { it.name }.distinct()))
            .put("at", if (silent) st.silentAt else g.lastFiredAt ?: 0L)
    }

    // MARK: the status (the alarm-health panel)

    private fun notifications(): String {
        val enabled = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33) return if (enabled) "granted" else "denied"
        val shared = context.permission(Manifest.permission.POST_NOTIFICATIONS).state
        return if (shared != "prompt" && !enabled) "denied" else shared
    }

    private fun status(): JSONObject {
        val nm = app.getSystemService(NotificationManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        val audio = app.getSystemService(AudioManager::class.java)
        val promoted = when {
            !LiveUpdate.platform() -> "unavailable"
            nm.canPostPromotedNotifications() -> "granted"
            else -> "denied"
        }
        val dnd = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> false
            NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                runCatching { nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0 }.getOrDefault(true)
            else -> true
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        return JSONObject().put("available", true).put("notifications", notifications()).put("channelBlocked", Shade.blocked(app))
            .put("exactAlarms", Chain.exact(app))
            .put("fullScreen", FullScreen.allowed(app)).put("overlay", Settings.canDrawOverlays(app)).put("promoted", promoted)
            .put("batteryUnrestricted", power.isIgnoringBatteryOptimizations(app.packageName))
            .put("backgroundRestricted", app.getSystemService(ActivityManager::class.java).isBackgroundRestricted)
            .put("standbyBucket", app.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
            .put("alarmVolume", audio.getStreamVolume(AudioManager.STREAM_ALARM).toDouble() / max)
            .put("dndAllowsAlarms", dnd).put("maker", Build.MANUFACTURER ?: "")
            // TalkBack (or any service exploring by touch): a hold is hard there, so the app seals with one tap.
            .put("talkBack", accessibility()?.isTouchExplorationEnabled == true)
    }

    // MARK: the request: the one screen that grants [what], then the status

    private fun request(what: String, done: () -> Unit) {
        val pkg = Uri.parse("package:${app.packageName}")
        val intent = when (what) {
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= 33 && notifications() == "prompt") return context.askPermission(Manifest.permission.POST_NOTIFICATIONS, done)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            }
            "exactAlarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            "fullScreen" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "promoted" -> if (LiveUpdate.platform()) Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName) else null
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            // The ring test's "Didn't hear it": Android's sound page (the alarm volume, Do Not Disturb, the sounds).
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            // One UI's "Never sleeping apps" list (Samsung, "App management"); elsewhere the app's own settings.
            "sleeping" -> Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").putExtra("activity_type", 2)
                .takeIf { it.resolveActivity(app.packageManager) != null } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> null
        } ?: return done()
        context.startActivityForResult(intent) { _, _ ->
            // An access granted re-arms what it allows (exact alarms, the full-screen nag, the bubble).
            Engine.restore(app)
            done()
        }
    }

    // MARK: the ring test: the real full-screen nag, never a dose

    /**
     * "Testar o toque": in [after] ms (at most [PREVIEW_AFTER_MAX_MS]) the rung's ring and vibration, as a nag at
     * [rung] rings them, and the full-screen nag as a real nag shows it: with the app not in front (the screen locked,
     * off, or another app open) a loud notification ([title], [body]) whose full-screen intent opens [route] over the
     * keyguard and turns the screen on; with the app in front, only the ring. It rings until `stopNagPreview` or for
     * [PREVIEW_RING_MS]. Nothing is recorded and nothing climbs: no dose, no event, no alarm of the chain.
     *
     * The wait runs in the process, under a wake lock (the screen may go off meanwhile): a few seconds after her tap,
     * neither Doze nor the freezer has come yet.
     */
    private fun preview(rung: Int, after: Long, title: String, body: String, route: String) {
        previewStop(timedOut = false)
        previewRung = rung
        previewTitle = title
        previewBody = body
        previewOpen = Shade.openingAt(app, if (route.startsWith("/")) route else "/", overLock = true)
        val wait = after.coerceIn(0, PREVIEW_AFTER_MAX_MS)
        previewWake = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pinkoo:preview")
            ?.apply { acquire(wait + 2_000) }
        main.postDelayed(previewFire, wait)
    }

    private fun previewRing() {
        previewWake?.let { if (it.isHeld) it.release() }
        previewWake = null
        val plan = Store.plan(app)
        preview = NagService.player(app, plan?.volume(previewRung) ?: 1.0)
        previewRinging = true
        NagService.vibrate(app, previewRung)
        // In front, the screen she is on already shows the ring: a notification over it would only cover it.
        if (!context.resumed) runCatching { Shade.preview(app, plan, previewTitle, previewBody, previewOpen) }
            .onFailure { android.util.Log.w("pinkoo.nag", "preview: $it") }
        main.postDelayed(previewEnd, PREVIEW_RING_MS)
    }

    /** The ring test ended: answered (`stopNagPreview`), left, or rung out. Rung out over the lock screen, nobody
     * answered there: the keyguard covers the app again. */
    private fun previewStop(timedOut: Boolean) {
        main.removeCallbacks(previewFire)
        main.removeCallbacks(previewEnd)
        previewWake?.let { if (it.isHeld) it.release() }
        previewWake = null
        preview?.runCatching { stop(); release() }
        preview = null
        if (previewRinging) app.getSystemService(Vibrator::class.java)?.cancel()
        previewRinging = false
        runCatching { Shade.cancel(app, Shade.PREVIEW) }
        if (timedOut && overLock()) context.showOverLock(false)
    }

}
