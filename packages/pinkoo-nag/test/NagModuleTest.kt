@file:Suppress("DEPRECATION") // Robolectric's scheduled alarms are read by their fields

package pinkoo.nag

import android.app.Activity
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import dev.exact.canvas.Activations
import dev.exact.canvas.Exact
import dev.exact.canvas.ExtensionContext
import dev.exact.canvas.ExtensionInvokeTest
import dev.exact.canvas.Extensions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayInputStream
import java.time.Duration

/** The module the app's session makes: the lock screen, what the phone allows, the experience test, the previews, the
 * phone's sounds and Android's volume panel (items 8, 13, 16 and 18). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [ExtensionInvokeTest.Host::class])
@LooperMode(LooperMode.Mode.PAUSED)
internal class NagModuleTest : SurfaceCase() {
    private lateinit var activity: Activity
    private var module: NagModule? = null
    private val pillA = dose("a@2026-11-18T08:00", "2026-11-18T08:00", name = "Pill A")
    private val pillB = dose("b@2026-11-18T08:00", "2026-11-18T08:00", name = "Pill B")
    private val volumes = ArrayList<Int>()

    @Before fun show() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Extensions.front = { activity }
        Extensions.started()
        Extensions.resumed()
        NagModule.volumePanel = { _, stream -> volumes += stream }
    }

    @After fun leave() {
        module?.destroy {}
        Extensions.paused()
        Extensions.stopped(false)
        Extensions.front = { Exact.reader?.get() }
        phone(locked = false, on = true)
        NagModule.volumePanel = { a, stream -> a.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI) }
    }

    private fun made() = NagModule(ExtensionContext(app, "nag")).also { module = it }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun send(name: String, args: JSONObject) = module!!.later(name, args) {}
    /** A send's reply, which the module gives at once. */
    private fun ask(name: String, args: JSONObject): JSONObject { var r: JSONObject? = null; module!!.later(name, args) { r = it }; return r!! }
    private fun keysOf(vararg d: JSONObject) = JSONObject().put("keys", JSONArray(d.map { it.getString("key") }))
    private val overLock get() = shadowOf(activity).showWhenLocked
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private fun test(): android.service.notification.StatusBarNotification? = shown(Shade.TEST)
    private fun rehearsal(step: String, after: Long = 0, quiet: Boolean = false, sound: String = "kalimba") = JSONObject()
        .put("step", step).put("after", after).put("quiet", quiet).put("title", "Pinkoo test").put("body", "This is how I remind you")
        .put("line", "Here I am!").put("publicTitle", "Pinkoo · Reminder test").put("stop", "Stop").put("channel", if (quiet) "Reminder · Vibrate only" else "Reminder · Kalimba")
        .put("mascot", mascot("#FF7A59")).put("sound", sound).put("volume", 0.6).put("route", "/nag/test?via=test&step=$step")
    private fun rehearseAlarms() = shadowOf(alarmManager).scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == Rehearsal.ACTION }

    /** The module is made at the first frame, after the activity resumed: made while it is resumed, the bubble hides at
     * once (a tap through the notification's alias resumes it before any module hears of it). */
    @Test fun aModuleMadeWhileTheActivityIsResumedHidesTheBubble() {
        ShadowSettings.setCanDrawOverlays(true)
        out().bubble(post(2, Step.AGAIN, bubble = true))
        assertTrue(Bubble.shown)
        made()
        assertFalse(Bubble.shown)
        assertTrue(Engine.appInFront)
    }

    /** A module that goes (its session ended) after the next one was made leaves the activity's state alone. */
    @Test fun anOldModuleGoingLeavesTheNewOnesFront() {
        val old = NagModule(ExtensionContext(app, "nag"))
        made()
        old.destroy {}
        assertTrue(Engine.appInFront)
    }

    // MARK: the lock screen (the celebration, `leaveLockScreen`)

    /** Confirmed over the lock screen, the route celebrates, says when it ends, and the keyguard covers the app again
     * then; only once nothing of the reminder is left: one medication confirmed of two keeps it. */
    @Test fun confirmedOverTheLockScreenTheKeyguardComesBackWhenTheCelebrationEnds() {
        Engine.schedule(app, planJson(pillA, pillB), at("2026-11-18T07:00"))
        phone(locked = true, on = true)
        Activations.overLock(activity, true)
        made()
        send("nagScreen", JSONObject().put("showing", true).put("keys", keysOf(pillA, pillB).getJSONArray("keys")))
        send("resolveNags", keysOf(pillA).put("kind", "taken"))
        idle(6_000)
        assertTrue("pill B is still to confirm", overLock)
        send("resolveNags", keysOf(pillB).put("kind", "taken"))
        idle(4_300)
        assertTrue("the celebration still shows", overLock)
        assertTrue(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
        idle(1)
        assertFalse("the keyguard covers the app again", overLock)
    }

    /** A route that never says its celebration ended is covered at the cap; said late, the keyguard is still on its way
     * and the route keeps its frame. */
    @Test fun aCelebrationThatNeverEndsIsCoveredAtTheCap() {
        Engine.schedule(app, planJson(pillA), at("2026-11-18T07:00"))
        phone(locked = true, on = true)
        Activations.overLock(activity, true)
        made()
        send("resolveNags", keysOf(pillA).put("kind", "taken"))
        idle(NagModule.CELEBRATE_MAX_MS - 100)
        assertTrue(overLock)
        idle(200)
        assertFalse(overLock)
        Extensions.paused()
        assertTrue(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
        phone(locked = false, on = true)
        Extensions.resumed()
        assertFalse(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
    }

    /** Unlocked, nothing is handed back: the route leaves as usual. */
    @Test fun leavingWhenUnlockedChangesNothing() {
        made()
        Activations.overLock(activity, true)
        assertFalse(ask("leaveLockScreen", JSONObject().put("after", 0)).getBoolean("locking"))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertTrue(overLock)
    }

    /** A promise on one of two doses over the lock screen keeps the route for the other; answering it hands the screen
     * back. */
    @Test fun aPromiseOnOneOfTwoDosesOverTheLockScreenKeepsTheRouteForTheOther() {
        Engine.schedule(app, planJson(pillA, pillB), at("2026-11-18T07:00"))
        clock = at("2026-11-18T08:00")
        phone(locked = true, on = true)
        Activations.overLock(activity, true)
        made()
        send("resolveNags", keysOf(pillA).put("kind", "promised").put("until", clock + 600_000))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertTrue("the other dose is still open", overLock)
        send("resolveNags", keysOf(pillB).put("kind", "taken"))
        idle(NagModule.CELEBRATE_MAX_MS + 100)
        assertFalse(overLock)
    }

    // MARK: what the phone allows (item 13)

    /** `nagStatus` gains the ringer mode, each stream's level over its maximum, Do Not Disturb's hints and each reminder
     * channel read back. */
    @Test fun theStatusReadsTheRingerTheVolumesDoNotDisturbAndTheChannels() {
        val audio = app.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        shadowOf(audio).setStreamMaxVolume(20)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 4, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        Shade.channels(app, plan())
        made()
        val s = ask("nagStatus", JSONObject())
        assertEquals("vibrate", s.getString("ringerMode"))
        assertEquals(0.2, s.getDouble("notificationVolume"), 0.001)
        assertEquals(0.25, s.getDouble("alarmVolume"), 0.001)
        assertTrue(s.getBoolean("dndAllowsReminders"))
        assertTrue(s.getBoolean("dndAllowsAlarms"))
        val channels = s.getJSONArray("channels").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf("sininho"), channels.map { it.getString("sound") })
        assertTrue(channels.all { it.getInt("importance") == android.app.NotificationManager.IMPORTANCE_HIGH && !it.getBoolean("blocked") && !it.getBoolean("changed") })
        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
        val quiet = ask("nagStatus", JSONObject())
        assertFalse(quiet.getBoolean("dndAllowsReminders"))
        assertFalse(quiet.getBoolean("dndAllowsAlarms"))
        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS)
        val alarms = ask("nagStatus", JSONObject())
        assertFalse(alarms.getBoolean("dndAllowsReminders"))
        assertTrue(alarms.getBoolean("dndAllowsAlarms"))
        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    // MARK: Android's pages (item 18 and the rest)

    /** "Ajustar no Android": Android's own volume panel for the notification stream or the alarm's, shown without
     * changing it; nothing opens. */
    @Test fun theVolumeShowsAndroidsPanelForEachStream() {
        made()
        val before = app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        assertTrue(ask("requestNagAccess", JSONObject().put("what", "volume").put("stream", "notification")).getBoolean("available"))
        send("requestNagAccess", JSONObject().put("what", "volume").put("stream", "alarm"))
        assertEquals(listOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM), volumes)
        assertEquals(before, app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM))
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    /** A blocked or changed reminder channel opens Android's page for that channel. */
    @Test fun aChannelOpensItsOwnPage() {
        Shade.channels(app, plan())
        made()
        send("requestNagAccess", JSONObject().put("what", "channel").put("sound", "kalimba"))
        idle(1)
        val i = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, i.action)
        assertEquals("pinkoo.remind.kalimba", i.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    /** Como eu te lembro's Do Not Disturb card opens Do Not Disturb's own page (Modes from Android 15)... */
    @Test fun dndOpensDoNotDisturbsPage() {
        made()
        send("requestNagAccess", JSONObject().put("what", "dnd"))
        idle(1)
        assertEquals(NagModule.ZEN_MODE_SETTINGS, shadowOf(activity).nextStartedActivityForResult?.intent?.action)
    }

    /** ...and Android's sound page where no app opens that one. */
    @Test fun dndFallsBackToTheSoundPage() {
        val settings = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.android.settings"; name = "Sound" }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SOUND_SETTINGS), settings)
        shadowOf(app).checkActivities(true)
        try {
            made()
            send("requestNagAccess", JSONObject().put("what", "dnd"))
            idle(1)
            assertEquals(Settings.ACTION_SOUND_SETTINGS, shadowOf(activity).nextStartedActivityForResult?.intent?.action)
        } finally {
            shadowOf(app).checkActivities(false)
        }
    }

    /** "Voltar pro {som}": a new version of the sound's channel, as Pinkoo sets it. */
    @Test fun renewMakesANewVersionOfTheChannel() {
        Engine.schedule(app, planJson(), at("2026-11-18T07:00"))
        Shade.channels(app, plan())
        made()
        send("renewNagChannel", JSONObject().put("sound", "sininho"))
        assertNotNull(nm.getNotificationChannel("pinkoo.remind.sininho.v2"))
        assertNull(nm.getNotificationChannel("pinkoo.remind.sininho"))
        assertEquals("Reminder · Sininho", nm.getNotificationChannel("pinkoo.remind.sininho.v2").name.toString())
    }

    // MARK: the phone's sounds (item 8)

    /** "Um som do celular": Android's picker for notification sounds, without "silent" or "default"; a sound Pinkoo's
     * own process can read answers its address and title; one it can't is refused; a back press is cancelled. */
    @Test fun thePhonesSoundPickerAnswersAReadableSoundOrRefusesIt() {
        made()
        val answers = ArrayList<JSONObject>()
        val readable = Uri.parse("content://media/internal/audio/media/12")
        shadowOf(app.contentResolver).registerInputStream(readable, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        for (uri in listOf(readable, Uri.parse("content://media/external/audio/media/99"), null)) {
            module!!.later("pickNagSound", JSONObject().put("current", "sininho").put("title", "Reminder sound")) { answers += it }
            idle(1)
            val started = shadowOf(activity).nextStartedActivityForResult
            assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, started.intent.action)
            assertEquals(RingtoneManager.TYPE_NOTIFICATION, started.intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
            assertFalse(started.intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
            assertFalse(started.intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true))
            val data = uri?.let { Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, it) }
            Extensions.activityResult(started.requestCode, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, data)
        }
        assertEquals(listOf("picked", "unreadable", "cancelled"), answers.map { it.getString("status") })
        assertEquals(readable.toString(), answers[0].getString("sound"))
    }

    // MARK: the experience test (item 16)

    /**
     * "Ver como é" for Notificação: her real channel (made on the spot, as from the onboarding before any plan), public
     * with "Parar" (the test's own stop, never the core's), a delete intent and a 15 s timeout. Nothing is recorded:
     * no state, no event, no alarm of the chain.
     */
    @Test fun theNotificationTestPostsOnHerChannelAndRecordsNothing() {
        made()
        send("rehearse", rehearsal("notify"))
        val n = test()!!.notification
        assertEquals("pinkoo.remind.kalimba", n.channelId)
        assertEquals("Reminder · Kalimba", nm.getNotificationChannel("pinkoo.remind.kalimba").name.toString())
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals("Pinkoo test", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Rehearsal.NOTIFY_MS, n.timeoutAfter)
        assertEquals(listOf("Stop"), n.actions.map { it.title.toString() })
        assertEquals(Rehearsal.STOP, shadowOf(n.actions[0].actionIntent).savedIntent.action)
        assertEquals(Rehearsal.STOP, shadowOf(n.deleteIntent).savedIntent.action)
        assertTrue("no event", Store.events(app).isEmpty())
        assertNull("no state", Store.plan(app))
        assertTrue("no alarm of the chain", shadowOf(alarmManager).scheduledAlarms.isEmpty())
        // "Parar".
        NagReceiver().onReceive(app, shadowOf(n.actions[0].actionIntent).savedIntent)
        assertNull(test())
        // "Testar sem som": the "Só vibrar" channel, since a channel's sound is fixed, named as the app says even with
        // no plan yet (Android lists it by that name).
        send("rehearse", rehearsal("notify", quiet = true))
        assertEquals("pinkoo.remind.vibrate", test()!!.notification.channelId)
        assertEquals("Reminder · Vibrate only", app.getSystemService(NotificationManager::class.java).getNotificationChannel("pinkoo.remind.vibrate").name.toString())
        send("stopRehearsal", JSONObject())
        assertNull(test())
    }

    /** "Em 5 s": the wait is an exact alarm under the test's own request code, which wakes a frozen app; stopped
     * before it, nothing comes. */
    @Test fun aTestsWaitIsItsOwnExactAlarm() {
        made()
        send("rehearse", rehearsal("notify", after = 5_000))
        assertNull(test())
        val wait = rehearseAlarms().single()
        assertEquals(AlarmManager.RTC_WAKEUP, wait.type)
        assertTrue(wait.allowWhileIdle)
        assertNull("nothing of the chain", alarmManager.nextAlarmClock)
        NagReceiver().onReceive(app, shadowOf(wait.operation).savedIntent)
        assertEquals("pinkoo.remind.kalimba", test()!!.notification.channelId)
        send("rehearse", rehearsal("notify", after = 5_000))
        send("stopRehearsal", JSONObject())
        assertTrue(rehearseAlarms().isEmpty())
        assertNull(test())
    }

    /** Bolinha's test: the real bubble with its line, over her apps (once she leaves Pinkoo), for 20 s. */
    @Test fun theBubbleTestShowsTheBubbleForTwentySeconds() {
        ShadowSettings.setCanDrawOverlays(true)
        made()
        val shown = android.os.SystemClock.uptimeMillis()
        send("rehearse", rehearsal("bubble"))
        assertFalse("not over Pinkoo itself", Bubble.shown)
        // She leaves the app to see it.
        Extensions.paused()
        module!!.paused()
        assertTrue(Bubble.shown)
        // Making the service plays the wiggle on Robolectric's clock (about 1.2 s).
        assertNotNull("the service holds it", serve())
        idle(shown + Rehearsal.BUBBLE_MS - 500 - android.os.SystemClock.uptimeMillis())
        assertTrue(Bubble.shown)
        idle(1_000)
        assertFalse(Bubble.shown)
        assertNull(NagService.running)
    }

    /** Tela cheia's test is counted from the screen turning off: 5 s after it, the nag route in test mode over the
     * keyguard (the silent channel, its full-screen intent). */
    @Config(sdk = [33])
    @Test fun theFullScreenTestComesFiveSecondsAfterTheScreenGoesOff() {
        made()
        send("rehearse", rehearsal("full"))
        assertTrue(listens(Intent.ACTION_SCREEN_OFF))
        assertTrue(rehearseAlarms().isEmpty())
        broadcast(Intent.ACTION_SCREEN_OFF)
        val wait = rehearseAlarms().single()
        assertTrue(kotlin.math.abs(System.currentTimeMillis() + Rehearsal.FULL_AFTER_OFF_MS - wait.triggerAtTime) < 1_000)
        NagReceiver().onReceive(app, shadowOf(wait.operation).savedIntent)
        val n = test()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertNotNull(n.fullScreenIntent)
        assertFalse("its screen signal goes", listens(Intent.ACTION_SCREEN_OFF))
    }

    /** With no screen-off within 15 s, the step comes as with the phone in use: her reminder, a heads-up. */
    @Test fun theFullScreenTestFallsBackToTheReminderWhenThePhoneStaysInUse() {
        made()
        send("rehearse", rehearsal("full"))
        idle(Rehearsal.FULL_IN_USE_MS + 100)
        assertEquals("pinkoo.remind.kalimba", test()!!.notification.channelId)
        assertFalse(listens(Intent.ACTION_SCREEN_OFF))
    }

    /**
     * Alarme's test: the ring service starts at the call while Pinkoo shows (Android allows that start in front), the
     * sample rings at its time with the alarm's public notification and "Parar", for at most 10 s. The screen turning
     * off before its sound stops nothing ("Em 5 s, pra eu bloquear"). Nothing is told to the core.
     */
    @Test fun theAlarmTestRingsAtMostTenSecondsInTheServiceStartedInFront() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, Settings.System.DEFAULT_ALARM_ALERT_URI), ShadowMediaPlayer.MediaInfo(600_000, 0))
        made()
        send("rehearse", rehearsal("alarm", after = 5_000))
        assertNotNull("started in front", serve())
        broadcast(Intent.ACTION_SCREEN_OFF)
        NagReceiver().onReceive(app, shadowOf(rehearseAlarms().single().operation).savedIntent)
        assertTrue(NagService.ring!!.test)
        val n = test()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(listOf("Stop"), n.actions.map { it.title.toString() })
        assertNull("no full-screen access: no full screen", n.fullScreenIntent)
        assertFalse(NagService.ring!!.wakes)
        idle(NagService.TEST_MS + 100)
        assertNull("rang out", NagService.ring)
        assertNull(test())
        assertNull(NagService.running)
        assertTrue("nothing told to the core", told.isEmpty())
    }

    /** As the real alarm (Glauco, 2026-11-24): with full-screen access the test's alarm notification carries the
     * full-screen intent to the route the app names (its nag in test mode), over the keyguard, and its ring knows it
     * wakes the screen; without the access, none (`theAlarmTestRingsAtMostTenSecondsInTheServiceStartedInFront`). */
    @Config(sdk = [33])
    @Test fun theAlarmTestBringsItsFullScreenLikeTheRealAlarm() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, Settings.System.DEFAULT_ALARM_ALERT_URI), ShadowMediaPlayer.MediaInfo(600_000, 0))
        made()
        send("rehearse", rehearsal("alarm", after = 5_000))
        broadcast(Intent.ACTION_SCREEN_OFF)
        NagReceiver().onReceive(app, shadowOf(rehearseAlarms().single().operation).savedIntent)
        assertTrue(NagService.ring!!.wakes)
        val n = test()!!.notification
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertNotNull(n.fullScreenIntent)
        val opens = shadowOf(n.fullScreenIntent).savedIntent
        assertEquals("/nag/test?via=test&step=alarm", opens.data.toString())
        assertTrue(opens.getBooleanExtra("dev.exact.activation.overLock", false))
        send("stopRehearsal", JSONObject())
        assertNull(test())
    }

    // MARK: the previews (item 16)

    /** A sound previews only in front: a phone sound through a player on the notification stream; with the app not in
     * in front, nothing plays. */
    @Test fun aPreviewPlaysOnlyInFront() {
        val sound = "content://media/internal/audio/media/12"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(app, Uri.parse(sound)), ShadowMediaPlayer.MediaInfo(2_000, 0))
        made()
        send("previewSound", JSONObject().put("sound", sound).put("usage", "notification").put("volume", 1.0))
        val player = Preview::class.java.getDeclaredField("player").apply { isAccessible = true }.get(Preview) as android.media.MediaPlayer?
        assertNotNull(player)
        assertEquals(android.media.AudioAttributes.USAGE_NOTIFICATION, shadowOf(player!!).audioAttributes?.usage ?: android.media.AudioAttributes.USAGE_NOTIFICATION)
        module!!.paused()
        Extensions.paused()
        send("previewSound", JSONObject().put("sound", sound).put("usage", "notification").put("volume", 1.0))
        assertNull(Preview::class.java.getDeclaredField("player").apply { isAccessible = true }.get(Preview))
    }
}
