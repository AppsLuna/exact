package pinkoo.nag

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSettings
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * What the surface's tests share (engine-surface): São Paulo, a pinned clock, a clean store and surface, a plan of shape
 * v2, [Post] fixtures as the core hands them to [Out], and the ring service made as Android makes it. The surface is
 * tested on its own, fed posts; the core's tests drive it through the engine.
 */
internal abstract class SurfaceCase {
    protected val app = RuntimeEnvironment.getApplication()
    private val zone = TimeZone.getDefault()
    protected val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    protected fun at(local: String) = LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    protected var clock = 0L
    /** What the surface told the core ([Told]): `stopped <nag> <why>`, `nested <nag>`, `unlocked`. */
    protected val told = ArrayList<String>()
    private val services = ArrayList<ServiceController<NagService>>()
    private var starts = 0

    protected val lines: List<List<String>> = listOf(listOf("a0", "b0"), listOf("a1", "b1"), listOf("a2"), listOf("a3"), listOf("a4"))

    /** The plan's texts (shape v2): the reminder's, the alarm's, the service's and each channel's name. */
    protected fun texts(): JSONObject = JSONObject().apply {
        put("title", "Time for {names}").put("body", "It's {time}.").put("publicTitle", "Pinkoo").put("publicBody", "Medicine time")
        put("take", "Taken").put("promise", "In a bit…").put("notNow", "Not now").put("pending", "{names} waits").put("deadline", "You promised {time}")
        put("promiseChip", "{names} by {time}").put("bubble", "Tap here, it's time").put("lines", JSONArray(lines.map { JSONArray(it) }))
        put("notNowRemind", "Okay: again at {time}.").put("notNowAlarm", "Okay: the alarm rings at {time}.").put("notNowWait", "Okay: on Today.")
        put("alarmTitle", "Medication alarm").put("alarmBody", "Stop silences it; the dose keeps waiting").put("stop", "Stop")
        put("service", "Bolota is around").put("channelNag", "Full screen and alarm").put("channelPending", "Waiting").put("channelNotice", "Notices")
        put("channels", JSONArray().put(JSONObject().put("sound", "sininho").put("name", "Reminder · Sininho"))
            .put(JSONObject().put("sound", "kalimba").put("name", "Reminder · Kalimba")))
    }

    protected fun dose(key: String, group: String, sound: String = "sininho", alarm: String = "off", name: String = key): JSONObject =
        JSONObject().put("key", key).put("group", group).put("name", name).put("miss", group.substring(0, 11) + "23:00")
            .put("promiseUntil", JSONObject.NULL).put("lineSeed", 0).put("sound", sound).put("alarm", alarm)

    /** A mascot of one circle in [fill], so a test can tell the rungs' drawings apart. */
    protected fun mascot(fill: String): JSONObject = JSONObject().put("viewBox", JSONArray(listOf(0, 0, 200, 200)))
        .put("parts", JSONArray().put(JSONObject().put("t", "circle").put("cx", 100).put("cy", 100).put("r", 80).put("fill", fill)))

    protected fun planJson(vararg doses: JSONObject, made: Long = 1, texts: JSONObject = texts(), volume: Double = 0.6): JSONObject =
        JSONObject().put("v", 2).put("made", made).put("zone", "America/Sao_Paulo").put("locale", "en")
            .put("ladder", JSONObject().put("firstMin", 20).put("thenMin", 15).put("repeatFirstMin", 30).put("repeatEveryMin", 60).put("repeats", 3))
            .put("steps", JSONObject().put("bubble", true).put("fullScreen", true).put("unlock", false))
            .put("alarm", JSONObject().put("volume", volume)).put("liveUpdate", true)
            .put("doses", JSONArray(doses.toList().ifEmpty { listOf(dose("a@2026-11-18T08:00", "2026-11-18T08:00", name = "Pill A")) })).put("texts", texts)
            .put("mascot", mascot("#FF7A59")).put("mascots", JSONArray(listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF").map { mascot(it) }))
            .put("stock", JSONObject.NULL).put("idle", JSONObject().put("afterDays", 6).put("title", "Open the app").put("body", "to keep reminders"))

    protected fun plan(vararg doses: JSONObject): Plan = Plan.parse(planJson(*doses))

    /** A post as the core hands it: Pill A's 08:00 reminder at [step]. */
    protected fun post(nag: Int, step: Step = Step.NOTIFY, sounding: Boolean = true, fullScreen: Boolean = false, alarm: Boolean = false,
        bubble: Boolean = false, folded: Boolean = false, front: Boolean = false, rung: Int = 0, line: String = "a0",
        channel: String = "sininho", names: List<String> = listOf("Pill A")): Post =
        Post(nag, listOf("2026-11-18T08:00"), step, listOf("a@2026-11-18T08:00"), names, channel, sounding, fullScreen, alarm, bubble,
            folded, front, "Time for " + names.joinToString(", "), line, rung)

    protected fun out(p: Plan = plan()) = AndroidOut(app, p)

    /** The reminder notifications showing: never more than one. */
    protected fun nags(): List<StatusBarNotification> = nm.activeNotifications.filter { it.id == Shade.NAG || it.id == Shade.NAG_ALT }
    protected fun nag(): StatusBarNotification? = nags().also { check(it.size <= 1) { "two reminders: ${it.map { n -> n.id }}" } }.firstOrNull()
    protected fun shown(id: Int): StatusBarNotification? = nm.activeNotifications.firstOrNull { it.id == id }

    /** The service the last start asked for, made and started as Android would; null when none was asked for. */
    protected fun serve(): NagService? {
        val started = shadowOf(app).nextStartedService ?: return null
        if (started.component?.className != NagService::class.java.name) return null
        val s = Robolectric.buildService(NagService::class.java, started).create()
        services += s
        s.get().onStartCommand(started, 0, ++starts)
        return s.get()
    }

    /** The phone as Android sees it: [locked] (the keyguard) and [on] (the screen). */
    protected fun phone(locked: Boolean, on: Boolean) {
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(on)
    }

    protected fun broadcast(action: String) {
        for (w in shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(action) }) w.broadcastReceiver.onReceive(app, Intent(action))
    }

    protected fun listens(action: String): Boolean = shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(action) }

    @Before fun setUpSurface() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowSettings.setCanDrawOverlays(false)
        Settings.System.putString(app.contentResolver, Settings.System.ALARM_ALERT, "content://media/internal/audio/media/7")
        Store.clear(app)
        reset()
        clock = at("2026-11-18T07:00")
        Engine.clock = { clock }
        phone(locked = false, on = true)
    }

    private fun reset() {
        NagService.running = null
        NagService.starting = false
        NagService.ring = null
        NagService.rang = 0
        Engine.appInFront = false
        Engine.screen = emptySet()
        AndroidOut.last = null
        Shade.forget()
        Bubble.reset()
        told.clear()
        Told.stopped = { _, nag, why -> told += "stopped $nag $why" }
        Told.nested = { _, nag -> told += "nested $nag" }
        Told.unlocked = { _ -> told += "unlocked" }
    }

    @After fun tearDownSurface() {
        runCatching { Rehearsal.stop(app, "test") }
        services.forEach { runCatching { it.destroy() } }
        Bubble.hide()
        Unlock.stop(app)
        Preview.release()
        reset()
        Told.reset()
        TimeZone.setDefault(zone)
        Store.clear(app)
        app.createDeviceProtectedStorageContext().getSharedPreferences("pinkoo.nag.channels", 0).edit().clear().commit()
        Engine.clock = System::currentTimeMillis
    }
}
