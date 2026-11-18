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
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import dev.exact.canvas.ExactExtension
import dev.exact.canvas.ExtensionContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

class NagModule(context: ExtensionContext) : ExactExtension(context) {
    companion object {
        @Volatile private var live: WeakReference<NagModule>? = null
        internal fun changed() { live?.get()?.let { it.context.changed("nag.nagState"); it.context.changed("nag.nagStatus") } }
    }

    private val app: Context get() = context.application
    private var preview: MediaPlayer? = null

    init {
        if (!context.agent) {
            live = WeakReference(this)
            Engine.restore(app)
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
                Engine.resolve(app, strings(args.optJSONArray("keys")), kind, until)
                reply(JSONObject())
            }
            "ackNagEvents" -> { args.optString("through").toLongOrNull()?.let { Engine.ack(app, it) }; reply(JSONObject()) }
            "nagScreen" -> {
                if (args.optBoolean("showing")) Engine.opened(app, strings(args.optJSONArray("keys")))
                else context.showOverLock(false)
                reply(JSONObject())
            }
            "requestNagAccess" -> request(args.optString("what")) { reply(status()) }
            "previewNag" -> { preview(args.optInt("rung")); reply(JSONObject()) }
            else -> reply(JSONObject().put("error", "NotSupportedError"))
        }
    }

    /** The bubble hides while the app's activity is resumed and comes back when it pauses. Back in front, everything is
     * set again: notifications turned back on in Settings meanwhile bring back the doses they silenced. */
    override fun resumed() = front(true)
    override fun paused() = front(false)

    private fun front(shown: Boolean) {
        Bubble.appInFront = shown
        if (!context.agent) Engine.restore(app, front = shown)
    }

    /** The person may have changed a switch in Settings meanwhile. */
    override fun foreground(shown: Boolean) { if (shown) changed() }

    override fun destroy(done: () -> Unit) {
        if (live?.get() === this) live = null
        Bubble.appInFront = false
        done()
    }

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }

    private fun state(): JSONObject {
        val plan = Store.plan(app)
        val doses = if (plan == null) JSONArray() else JSONArray(Rules.doses(plan, Store.state(app)))
        return JSONObject().put("doses", doses).put("events", JSONArray(Store.events(app).map { it.json() }))
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

    // MARK: the preview: five seconds of the rung's ring, for onboarding and the panel's test

    private fun preview(rung: Int) {
        preview?.runCatching { stop(); release() }
        val volume = Store.plan(app)?.volume(rung) ?: 1.0
        preview = NagService.player(app, volume)
        app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
        Handler(Looper.getMainLooper()).postDelayed({
            preview?.runCatching { stop(); release() }
            preview = null
            app.getSystemService(Vibrator::class.java)?.cancel()
        }, 5_000)
    }

}
