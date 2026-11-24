package pinkoo.nag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.provider.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** The reminder notification and her channels (items 4, 5 and 17's posting side), fed the core's posts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ShadeTest : SurfaceCase() {
    private fun only(n: Notification) = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
    /** A post that must not sound: in a group that alerts only for its summary, which never comes. */
    private fun quiet(n: Notification) = n.group != null && n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY
    private fun actions(n: Notification) = n.actions?.map { it.title.toString() } ?: emptyList()
    private fun intentOf(p: android.app.PendingIntent) = shadowOf(p).savedIntent

    /**
     * Item 4: each step that sounds is a new post on her reminder channel (Android plays a channel's sound only for a
     * notification posted anew), the id alternating and the other one cancelled. A reminder: the reminder category, not
     * ongoing, "Tomei", "Tomo daqui a…" and "Agora não" on every step, its swipe and "Agora não" broadcasts, and a public
     * version without the medication's name.
     */
    @Test fun everySoundingStepIsANewPostOnHerReminderChannel() {
        val o = out()
        val ids = ArrayList<Int>()
        for ((nag, step) in listOf(1 to Step.NOTIFY, 2 to Step.AGAIN, 3 to Step.REPEAT, 4 to Step.REPEAT)) {
            o.post(post(nag, step))
            val sbn = nag()!!
            val n = sbn.notification
            ids += sbn.id
            assertEquals("$step", "pinkoo.remind.sininho", n.channelId)
            assertEquals(Notification.CATEGORY_REMINDER, n.category)
            assertEquals("a swipe dismisses it anywhere", 0, n.flags and Notification.FLAG_ONGOING_EVENT)
            assertFalse("it sounds", quiet(n))
            assertNull(n.fullScreenIntent)
            assertEquals(listOf("Taken", "In a bit…", "Not now"), actions(n))
            assertEquals(NagReceiver.NOT_NOW, intentOf(n.actions[2].actionIntent).action)
            assertEquals(nag, intentOf(n.actions[2].actionIntent).getIntExtra("nag", 0))
            assertEquals(NagReceiver.DISMISS, intentOf(n.deleteIntent).action)
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals("Pinkoo", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Time for Pill A", n.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("a0", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals("It's 08:00.", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        }
        assertEquals(listOf(Shade.NAG, Shade.NAG_ALT, Shade.NAG, Shade.NAG_ALT), ids)
    }

    /** The core calls [Out.post] again for a nag it already posted (a refresh, a restore): nothing sounds again. */
    @Test fun aPostAlreadyMadeIsOnlyRefreshed() {
        val o = out()
        o.post(post(1))
        val first = shadowOf(nm).getNotification(Shade.NAG)
        o.post(post(1))
        assertSame(first, shadowOf(nm).getNotification(Shade.NAG))
        // Across a process death too: the notification itself says which nag it shows.
        Shade.forget()
        o.post(post(1))
        assertSame(first, shadowOf(nm).getNotification(Shade.NAG))
    }

    /**
     * Item 4: a silent refresh stays in place with `setOnlyAlertOnce`, on her reminder channel, posted only when what it
     * shows changed. With nothing showing at its id (a process death, Android cleared it), it is posted silently.
     */
    @Test fun aRefreshIsSilentInPlaceAndOnlyWhenItChanged() {
        val o = out()
        o.post(post(1))
        val first = shadowOf(nm).getNotification(Shade.NAG)
        assertTrue("a later post of the same never alerts again", only(first))
        o.refresh(post(1, sounding = false))
        assertSame("nothing changed: nothing posted", first, shadowOf(nm).getNotification(Shade.NAG))
        o.refresh(post(1, sounding = false, names = listOf("Pill A", "Pill B")))
        val second = shadowOf(nm).getNotification(Shade.NAG)
        assertNotSame(first, second)
        assertEquals("Time for Pill A, Pill B", second.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(only(second))
        assertEquals("pinkoo.remind.sininho", second.channelId)
        nm.cancelAll()
        o.refresh(post(1, sounding = false))
        assertTrue("posted anew, silently", quiet(nag()!!.notification))
    }

    /**
     * The next step after the reminder was restored silently (a reboot, a cleared notification): the silent one goes
     * before the step posts, never beside it. Posted beside it, the two joined an automatic group on the emulator
     * (Android 16), which posted the step silently: De novo after a reboot made no sound (engine-device, 2026-11-22).
     */
    @Test fun aStepAfterASilentRestoreCancelsItBeforeItPosts() {
        val o = out()
        o.refresh(post(1, sounding = false))
        assertTrue(quiet(shown(Shade.nagId(1))!!.notification))
        val seen = ArrayList<Pair<Int, List<Int>>>()
        Shade.posting = { id, before -> seen += id to before }
        try { o.post(post(2)) } finally { Shade.posting = null }
        assertEquals(listOf(Shade.nagId(2) to emptyList<Int>()), seen)
        assertFalse("it sounds", quiet(nag()!!.notification))
        assertEquals(Shade.nagId(2), nag()!!.id)
    }

    /** A step folded into the alarm, or posted while nothing may sound, is a new post that doesn't alert. In front
     * (decision 17) it goes on the waiting channel: no heads-up, no full-screen intent. */
    @Test fun aPostThatMustNotSoundIsSilentAndInFrontItIsOnTheWaitingChannel() {
        val o = out()
        o.post(post(1, sounding = false))
        assertTrue(quiet(nag()!!.notification))
        o.post(post(2, sounding = false, front = true, fullScreen = true))
        val n = nag()!!.notification
        assertEquals(Shade.CH_WAITING, n.channelId)
        assertNull(n.fullScreenIntent)
    }

    /**
     * Item 17: Tela cheia posts with its full-screen intent on the silent channel only while the phone is locked or dark
     * and Do Not Disturb is off. With the phone in use, Do Not Disturb on, or no access, it is her reminder posted anew,
     * sounding, as a heads-up.
     */
    @Config(sdk = [33]) // full-screen access allowed by default on Android 12 and 13
    @Test fun theFullScreenOpensOnlyOnALockedOrDarkPhoneWithDoNotDisturbOff() {
        val o = out()
        phone(locked = true, on = true)
        o.post(post(1, Step.FULL, fullScreen = true))
        var n = nag()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertNotNull(n.fullScreenIntent)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        // Dark and unlocked: the same.
        phone(locked = false, on = false)
        o.post(post(2, Step.FULL, fullScreen = true))
        assertNotNull(nag()!!.notification.fullScreenIntent)
        // In use: her reminder, sounding.
        phone(locked = false, on = true)
        o.post(post(3, Step.FULL, fullScreen = true))
        n = nag()!!.notification
        assertEquals("pinkoo.remind.sininho", n.channelId)
        assertNull(n.fullScreenIntent)
        assertFalse(quiet(n))
        // Do Not Disturb on, the phone locked: her reminder too.
        phone(locked = true, on = false)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        o.post(post(4, Step.FULL, fullScreen = true))
        assertNull(nag()!!.notification.fullScreenIntent)
        assertEquals("pinkoo.remind.sininho", nag()!!.notification.channelId)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        // A refresh never re-attaches it.
        o.post(post(5, Step.FULL, fullScreen = true))
        assertNotNull(nag()!!.notification.fullScreenIntent)
        o.refresh(post(5, Step.FULL, sounding = false, fullScreen = true, line = "a1"))
        assertNull(nag()!!.notification.fullScreenIntent)
        assertEquals("pinkoo.remind.sininho", nag()!!.notification.channelId)
    }

    /** Without full-screen access (Android 14 and later, a store install: Robolectric's default) the step is her
     * reminder posted anew, sounding, even on a locked phone. */
    @Test fun withoutTheAccessTheFullScreenIsHerReminder() {
        assertFalse(FullScreen.allowed(app))
        phone(locked = true, on = false)
        out().post(post(1, Step.FULL, fullScreen = true))
        val n = nag()!!.notification
        assertNull(n.fullScreenIntent)
        assertEquals("pinkoo.remind.sininho", n.channelId)
        assertFalse(quiet(n))
    }

    /**
     * Items 4 and 10: the alarm's notification is the alarm category, ongoing, public with neutral text (no medication's
     * name) and "Parar", a broadcast that runs on the locked phone; its delete intent stops the ring too. When the ring
     * has stopped, the refresh brings the reminder back on her channel, silently, in place.
     */
    @Test fun theAlarmsNotificationIsPublicWithStopAndTheReminderComesBackAfter() {
        val o = out()
        o.post(post(1, Step.ALARM, alarm = true))
        val n = nag()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Medication alarm", n.extras.getString(Notification.EXTRA_TITLE))
        assertFalse("no medication's name", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Pill"))
        assertEquals(listOf("Stop"), actions(n))
        val stop = intentOf(n.actions[0].actionIntent)
        assertEquals(AndroidOut.STOP, stop.action)
        assertEquals(1, stop.getIntExtra("nag", 0))
        assertTrue(shadowOf(n.actions[0].actionIntent).isBroadcast)
        assertEquals(AndroidOut.STOP, intentOf(n.deleteIntent).action)
        assertEquals("swipe", intentOf(n.deleteIntent).getStringExtra("why"))
        // Nothing rings (the ring stopped): the refresh is the reminder again, in place.
        o.refresh(post(1, Step.ALARM, sounding = false, alarm = true))
        val back = nag()!!
        assertEquals(Shade.NAG, back.id)
        assertEquals("pinkoo.remind.sininho", back.notification.channelId)
        assertEquals("Time for Pill A", back.notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(only(back.notification))
    }

    /**
     * Glauco, 2026-11-24: the alarm's notification, posted as its fire rings, carries the nag route's full-screen intent
     * (over the keyguard, `via=fsi`) whenever the access is granted: on a locked or dark phone, and on one in use too
     * (Android shows the heads-up), Do Not Disturb left to Android. A refresh while it rings never re-attaches it.
     */
    @Config(sdk = [33]) // full-screen access allowed by default on Android 12 and 13
    @Test fun theAlarmBringsTheFullScreenEveryTimeItRingsWithTheAccess() {
        val o = out()
        phone(locked = true, on = false)
        o.post(post(1, Step.ALARM, sounding = false, alarm = true, fullScreen = true))
        var n = nag()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertNotNull(n.fullScreenIntent)
        val opens = intentOf(n.fullScreenIntent)
        assertTrue("the nag route, ringing there", opens.data.toString().contains("via=fsi"))
        assertTrue("over the keyguard", opens.getBooleanExtra("dev.exact.activation.overLock", false))
        // The phone in use: attached all the same (Android shows a heads-up), unlike Tela cheia's.
        phone(locked = false, on = true)
        o.post(post(2, Step.ALARM, sounding = false, alarm = true, fullScreen = true))
        assertNotNull(nag()!!.notification.fullScreenIntent)
        // "Já na hora": the notification folded into the ring.
        phone(locked = true, on = false)
        o.post(post(3, Step.NOTIFY, sounding = false, alarm = true, folded = true, fullScreen = true))
        n = nag()!!.notification
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertNotNull(n.fullScreenIntent)
        // In front none: the app opens the nag screen itself.
        o.post(post(4, Step.ALARM, sounding = false, alarm = true, fullScreen = true, front = true))
        assertNull(nag()!!.notification.fullScreenIntent)
        // Another dose time's step folded in while it rings (no ring of its own): none.
        o.post(post(5, Step.NOTIFY, sounding = false, alarm = true, folded = true))
        assertNull(nag()!!.notification.fullScreenIntent)
    }

    /** Without full-screen access (Android 14 and later, a store install: Robolectric's default) the alarm rings exactly
     * as before: its own notification, with no full-screen intent. */
    @Test fun withoutTheAccessTheAlarmIsItsNotificationAlone() {
        assertFalse(FullScreen.allowed(app))
        phone(locked = true, on = false)
        out().post(post(1, Step.ALARM, sounding = false, alarm = true, fullScreen = true))
        val n = nag()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(listOf("Stop"), actions(n))
        assertNull(n.fullScreenIntent)
    }

    /** "Já na hora": the dose time's step folded into the alarm is the alarm's notification, on the silent channel: the
     * ring is the only sound. */
    @Test fun aStepFoldedIntoTheAlarmIsTheAlarmsNotification() {
        out().post(post(1, Step.NOTIFY, sounding = false, alarm = true, folded = true))
        val n = nag()!!.notification
        assertEquals(Shade.CH_NAG, n.channelId)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
    }

    /**
     * Item 1: the notification's "Agora não" turns the post, silently and in place, into the confirmation for 5 s
     * (`setTimeoutAfter`), then it goes; its delete intent (the timeout sends it too) is the dismissal. A refresh in the
     * meantime leaves it.
     */
    @Test fun notNowFromTheNotificationShowsTheConfirmationForFiveSeconds() {
        val o = out()
        o.post(post(3))
        o.confirm(3, "Okay: again at 08:30.")
        val n = nag()!!
        assertEquals(Shade.nagId(3), n.id)
        assertEquals("Okay: again at 08:30.", n.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Shade.CONFIRM_MS, n.notification.timeoutAfter)
        assertTrue(only(n.notification))
        assertEquals("pinkoo.remind.sininho", n.notification.channelId)
        assertTrue(actions(n.notification).isEmpty())
        assertEquals(NagReceiver.DISMISS, intentOf(n.notification.deleteIntent).action)
        assertEquals(3, intentOf(n.notification.deleteIntent).getIntExtra("nag", 0))
        o.refresh(post(3, sounding = false, line = "b0"))
        assertEquals("Okay: again at 08:30.", nag()!!.notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun clearTakesTheReminderAwayBothIds() {
        val o = out()
        o.post(post(1))
        o.clear(1)
        assertTrue(nags().isEmpty())
        o.post(post(2))
        o.clear(0)
        assertTrue(nags().isEmpty())
    }

    /** The rung's face and line: the notification shows the mascot at the post's rung, and its line. */
    @Test fun theNotificationShowsThePostsRungAndLine() {
        val o = out()
        val reds = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        for (rung in 0..4) {
            o.post(post(rung + 1, rung = rung, line = lines[rung][0]))
            val n = nag()!!.notification
            assertEquals(lines[rung][0], n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            val picture = (n.getLargeIcon().loadDrawable(app) as BitmapDrawable).bitmap
            assertEquals("rung $rung", reds[rung], picture.getPixel(picture.width / 2, picture.height / 2))
        }
    }

    /** A promise: its deadline counting down on the waiting channel, a public version without the medication's name,
     * its dismissal answering nothing; once dismissed, not shown again for that deadline. */
    @Test fun aPromiseCountsDownToItsDeadline() {
        val p = plan()
        val st = State()
        val until = at("2026-11-18T08:30")
        st.doses["a@2026-11-18T08:00"] = DoseState("a@2026-11-18T08:00", until = until)
        Shade.channels(app, p)
        Shade.promise(app, p, st)
        val n = shown(Shade.PROMISE)!!.notification
        assertEquals(Shade.CH_WAITING, n.channelId)
        assertEquals("Pill A by 08:30", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(until, n.`when`)
        assertEquals("Pinkoo", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(NagReceiver.PROMISE_DISMISSED, intentOf(n.deleteIntent).action)
        val first = shadowOf(nm).getNotification(Shade.PROMISE)
        Shade.promise(app, p, st)
        assertSame("posted only when it changed", first, shadowOf(nm).getNotification(Shade.PROMISE))
        st.promiseDismissed = until
        Shade.promise(app, p, st)
        assertNull(shown(Shade.PROMISE))
    }

    // MARK: channels (item 5)

    /**
     * A plan makes one reminder channel per sound it names: importance high, the notification usage with sonification,
     * a soft vibration, its name from the plan's texts. "Só vibrar" has the vibration and no sound, "Sem som" neither.
     * The engine's own channels are made too, `pinkoo.nag` renamed, and the old ring channel goes.
     */
    @Test fun aPlanMakesOneReminderChannelPerSound() {
        nm.createNotificationChannel(NotificationChannel(Shade.CH_RING_GONE, "old", NotificationManager.IMPORTANCE_HIGH))
        val p = plan(dose("a@2026-11-18T08:00", "2026-11-18T08:00"), dose("b@2026-11-18T09:00", "2026-11-18T09:00", sound = "vibrate"),
            dose("c@2026-11-18T10:00", "2026-11-18T10:00", sound = "silent"))
        Shade.channels(app, p)
        val bell = nm.getNotificationChannel("pinkoo.remind.sininho")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, bell.importance)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, bell.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, bell.audioAttributes.contentType)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, bell.sound)
        assertTrue(bell.shouldVibrate())
        assertArrayEquals(Remind.SOFT, bell.vibrationPattern)
        assertEquals("Reminder · Sininho", bell.name.toString())
        assertNull("made only when a dose names it: the texts only name it", nm.getNotificationChannel("pinkoo.remind.kalimba"))
        val buzz = nm.getNotificationChannel("pinkoo.remind.vibrate")
        assertNull(buzz.sound)
        assertTrue(buzz.shouldVibrate())
        val none = nm.getNotificationChannel("pinkoo.remind.silent")
        assertNull(none.sound)
        assertFalse(none.shouldVibrate())
        assertEquals("Full screen and alarm", nm.getNotificationChannel(Shade.CH_NAG).name.toString())
        assertNull(nm.getNotificationChannel(Shade.CH_NAG).sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(Shade.CH_WAITING).importance)
        assertNull("the old ring channel goes", nm.getNotificationChannel(Shade.CH_RING_GONE))
    }

    /** A phone sound's channel is named by a hash of its canonical address (MediaStore ids can change after an
     * update), and plays that address. */
    @Test fun aPhoneSoundsChannelIsAHashOfItsAddress() {
        val address = "content://media/internal/audio/media/42?title=Chime&canonical=1"
        val id = Remind.ensure(app, address, "Reminder · Chime")
        assertTrue(id, Regex("pinkoo\\.remind\\.phone\\.[0-9a-f]{12}").matches(id))
        assertEquals(address, nm.getNotificationChannel(id).sound.toString())
        assertEquals(id, Remind.ensure(app, address, "Reminder · Chime"))
    }

    /** A channel no plan names is deleted at a later plan; one still named stays, renamed when the language changes. */
    @Test fun aChannelNoPlanNamesGoesAndARenamedOneKeepsItsId() {
        val texts = texts().put("channels", org.json.JSONArray())
        Shade.channels(app, Plan.parse(planJson(dose("a@2026-11-18T08:00", "2026-11-18T08:00"), dose("b@2026-11-18T09:00", "2026-11-18T09:00", sound = "kalimba"), texts = texts)))
        assertNotNull(nm.getNotificationChannel("pinkoo.remind.kalimba"))
        val pt = texts().put("channels", org.json.JSONArray().put(org.json.JSONObject().put("sound", "sininho").put("name", "Lembrete · Sininho")))
        Shade.channels(app, Plan.parse(planJson(dose("a@2026-11-18T08:00", "2026-11-18T08:00"), texts = pt)))
        assertNull(nm.getNotificationChannel("pinkoo.remind.kalimba"))
        assertEquals("Lembrete · Sininho", nm.getNotificationChannel("pinkoo.remind.sininho").name.toString())
    }

    /** A medication with no dose in the plan's days still names its channel in the texts: the channel stays (with
     * whatever she changed in Android), and isn't made for it until a dose names it. */
    @Test fun aChannelHerMedicationsStillNameStaysWithoutADose() {
        Shade.channels(app, plan(dose("a@2026-11-18T08:00", "2026-11-18T08:00"), dose("b@2026-11-18T09:00", "2026-11-18T09:00", sound = "kalimba")))
        assertNotNull(nm.getNotificationChannel("pinkoo.remind.kalimba"))
        // Only sininho's doses now; texts() still names kalimba's channel.
        Shade.channels(app, plan(dose("a@2026-11-18T08:00", "2026-11-18T08:00")))
        assertNotNull("named by her medications, kept", nm.getNotificationChannel("pinkoo.remind.kalimba"))
    }

    /**
     * A channel she blocked or changed in Android is never remade under a new id: making it again keeps her settings,
     * and Como eu te lembro reads it back. "Voltar pro {som}" (`renewNagChannel`) makes a new version as Pinkoo sets it,
     * the old one deleted; never for a blocked one.
     */
    @Test fun aChangedChannelIsKeptAndRenewedOnlyWhenSheAsks() {
        Shade.channels(app, plan())
        val id = "pinkoo.remind.sininho"
        // She lowers it in Android.
        nm.getNotificationChannel(id).let { it.importance = NotificationManager.IMPORTANCE_LOW; nm.createNotificationChannel(it) }
        Shade.channels(app, plan())
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(id).importance)
        val read = Remind.status(app).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.getString("sound") == "sininho" }
        assertEquals(id, read.getString("id"))
        assertTrue(read.getBoolean("changed"))
        assertFalse(read.getBoolean("blocked"))
        // "Voltar pro Sininho": a new version, high again; the old one goes; reminders post on it.
        assertEquals("$id.v2", Remind.renew(app, "sininho", "Reminder · Sininho"))
        assertNull(nm.getNotificationChannel(id))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel("$id.v2").importance)
        out().post(post(1))
        assertEquals("$id.v2", nag()!!.notification.channelId)
        // Blocked: kept, never renewed.
        nm.getNotificationChannel("$id.v2").let { it.importance = NotificationManager.IMPORTANCE_NONE; nm.createNotificationChannel(it) }
        assertEquals("$id.v2", Remind.renew(app, "sininho", null))
        val blocked = Remind.status(app).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.single { it.getString("sound") == "sininho" }
        assertTrue(blocked.getBoolean("blocked"))
        assertFalse(blocked.getBoolean("changed"))
    }
}
