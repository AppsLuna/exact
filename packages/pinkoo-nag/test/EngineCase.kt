package pinkoo.nag

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.service.notification.StatusBarNotification
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

/** What the engine's tests share: São Paulo, a pinned clock, a clean store, and the service made as Android makes it. */
internal abstract class EngineCase {
    protected val app = RuntimeEnvironment.getApplication()
    private val zone = TimeZone.getDefault()
    protected val alarms: AlarmManager get() = app.getSystemService(AlarmManager::class.java)
    protected val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    protected fun at(local: String) = LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    protected val pillA = NagFixtures.dose("a@2026-11-18T08:00", "2026-11-18T08:00", "2026-11-18T20:00", "Pill A")
    protected fun group(at: String = "2026-11-18T08:00") = Store.state(app).groups.getValue(at)

    /** The engine's clock, which the receivers and the service read. */
    protected var clock = 0L
    private val services = ArrayList<ServiceController<NagService>>()
    private var starts = 0

    /** The nag notifications showing: never more than one. */
    protected fun nags(): List<StatusBarNotification> = nm.activeNotifications.filter { it.id == Shade.NAG || it.id == Shade.NAG_ALT }
    protected fun nag(): StatusBarNotification? = nags().also { check(it.size <= 1) { "two nag notifications: ${it.map { n -> n.id }}" } }.firstOrNull()

    /** The service the last start asked for, made and started as Android would; null when none was asked for. */
    protected fun serve(): NagService? {
        val started = shadowOf(app).nextStartedService ?: return null
        if (started.component?.className != NagService::class.java.name) return null
        val s = Robolectric.buildService(NagService::class.java, started).create()
        services += s
        s.get().onStartCommand(started, 0, ++starts)
        return s.get()
    }

    /** Whether the unlock nuisance listens. */
    protected fun listensToUnlock(): Boolean = shadowOf(app).registeredReceivers.any { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) }

    /** Rings and ignores [times] nags of [pillA]'s group from 08:00, each run by the service: it reaches rung [times]. */
    protected fun climb(times: Int): Long {
        var t = at("2026-11-18T08:00")
        repeat(times) {
            clock = t
            Engine.fire(app, t)
            serve()
            Engine.ignore(app, group().nag, "notNow", t + 1_000)
            t = group().nextAt!!
        }
        return t
    }

    @Before fun setUpEngine() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowSettings.setCanDrawOverlays(false)
        // An alarm sound set, as on most phones.
        Settings.System.putString(app.contentResolver, Settings.System.ALARM_ALERT, "content://media/internal/audio/media/7")
        NagFixtures.clear(app)
        NagService.running = null
        NagService.starting = false
        Engine.appInFront = false
        Shade.forget()
        clock = at("2026-11-18T07:00")
        Engine.clock = { clock }
    }

    @After fun tearDownEngine() {
        services.forEach { runCatching { it.destroy() } }
        NagService.running = null
        Bubble.hide()
        Engine.appInFront = false
        Unlock.apply(app, Live(Plan.parse(NagFixtures.plan()), State()), on = false)
        TimeZone.setDefault(zone)
        NagFixtures.clear(app)
        Shade.forget()
        Engine.clock = System::currentTimeMillis
    }
}
