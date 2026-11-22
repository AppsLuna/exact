// The experience test ("Sentir meu lembrete", `rehearse` / `stopRehearsal`) and the sound previews (`previewSound`).
//
// The test runs the real path and records nothing: no state write, no event, no alarm of the chain. It carries its own
// texts, mascot shapes and sound (the onboarding runs it before any plan) and makes the channel it needs on the spot.
// Its waits are an exact alarm under its own request code ([CODE], a `REHEARSE` broadcast to `NagReceiver`), since a
// handler waiting under a wake lock in a cached process isn't reliably woken (Android freezes cached apps); without
// exact access they may come late, and the app's screen says so.
// - Notificação: her real channel (the "Só vibrar" channel for "Testar sem som", since a channel's sound is fixed),
//   public, with "Parar", a delete intent and a 15 s timeout.
// - Bolinha: the real bubble, its balloon and wiggle, for 20 s.
// - Tela cheia: armed at the screen's turning off plus 5 s, opening the nag route in test mode over the keyguard; with
//   no screen-off within 15 s, the step as with the phone in use (her sound, a heads-up).
// - Alarme: the ring service started while Pinkoo still shows, the sample at most 10 s, with the alarm's public
//   notification and "Parar"; the stop contract's screen signals count only once its sound started.
//
// The previews play only in front, so Android allows them: `SoundPool` for the short presets (a `MediaPlayer` takes
// hundreds of ms to prepare on the A06, too slow for tap-to-hear), `USAGE_NOTIFICATION` for reminder sounds (muted on
// vibrate and silent, which the sheet says) and `USAGE_ALARM` at the slider's level.
package pinkoo.nag

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import org.json.JSONObject

internal object Rehearsal {
    /** `NagReceiver`'s action for the test's waits. */
    const val ACTION = "pinkoo.nag.REHEARSE"
    /** `NagReceiver`'s action for the test's "Parar" and its notification's delete intent: never the core's stop. */
    const val STOP = "pinkoo.nag.REHEARSE_STOP"
    /** The test's own alarm request code: never the chain's (0, its backup, 1). */
    const val CODE = 7
    /** The ring's number for the test's sample: never a nag's. */
    const val NAG = -1
    const val NOTIFY_MS = 15_000L
    const val BUBBLE_MS = 20_000L
    const val FULL_AFTER_OFF_MS = 5_000L
    const val FULL_IN_USE_MS = 15_000L

    /** What the app asks (`rehearse`'s options). */
    class Ask(val o: JSONObject) {
        val step: String = o.optString("step", "notify")
        val after: Long = o.optDouble("after", 0.0).toLong().coerceIn(0, 60_000)
        val quiet: Boolean = o.optBoolean("quiet")
        val title: String = o.optString("title"); val body: String = o.optString("body"); val line: String = o.optString("line")
        val publicTitle: String = o.optString("publicTitle"); val stop: String = o.optString("stop")
        val channel: String = o.optString("channel")
        val sound: String = o.optString("sound").ifEmpty { Sounds.DEFAULT }
        val volume: Double = o.optDouble("volume", 1.0)
        val route: String = o.optString("route").ifEmpty { "/" }
        val mascot: Mascot? = o.opt("mascot").let { v ->
            val m = if (v is String) runCatching { org.json.JSONTokener(v).nextValue() }.getOrNull() else v
            (m as? JSONObject)?.let { runCatching { Mascot.parse(it) }.getOrNull() }?.takeIf { it.parts.isNotEmpty() }
        }
        /** The channel this test's notification posts on: "Só vibrar" for "Testar sem som". */
        val notifySound: String get() = if (quiet) "vibrate" else sound
    }

    @Volatile private var asked: Ask? = null
    /** The alarm sample waits for its time: the service holds the process from the call (started while Pinkoo shows). */
    @Volatile var holding = false
        private set
    /** The test's bubble shows: the real one waits for it to end. */
    @Volatile var bubbling = false
        private set
    private var screen: Pair<Context, BroadcastReceiver>? = null
    /** The test's bubble, while it lasts. */
    private var face: Bubble.Face? = null
    private val main = Handler(Looper.getMainLooper())
    private var timer: Runnable? = null

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun wake(c: Context, phase: String, a: Ask): PendingIntent = PendingIntent.getBroadcast(c, CODE,
        Intent(c, NagReceiver::class.java).setAction(ACTION).putExtra("phase", phase).putExtra("ask", a.o.toString()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** `rehearse`: one step of the test, [args] as the app sends them. A test already running ends first. */
    fun start(c: Context, args: JSONObject) {
        val app = c.applicationContext
        stop(app, "again")
        val a = Ask(args)
        asked = a
        Shade.baseIfMissing(app)
        when (a.step) {
            "full" -> {
                // Counted from the screen turning off: she locks the phone to see it.
                val r = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        unlisten()
                        cancelTimer()
                        arm(app, FULL_AFTER_OFF_MS, "full", a)
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_EXPORTED)
                else app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF))
                screen = app to r
                later(FULL_IN_USE_MS) { unlisten(); fire(app, "inUse", a) }
            }
            "alarm" -> {
                holding = true
                NagService.words = Store.plan(app)?.texts?.service ?: ""
                NagService.sync(app)
                arm(app, a.after, "ring", a)
            }
            else -> arm(app, a.after, a.step, a)
        }
    }

    /** [phase] in [after] ms, by the test's own exact alarm (inexact without the access), or now. */
    private fun arm(c: Context, after: Long, phase: String, a: Ask) {
        if (after <= 0) return fire(c, phase, a)
        val at = System.currentTimeMillis() + after
        if (Chain.exact(c)) am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, wake(c, phase, a))
        else am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, wake(c, phase, a))
    }

    /** `NagReceiver`'s [ACTION]: the test's wait is over. */
    fun fire(c: Context, intent: Intent) {
        val a = intent.getStringExtra("ask")?.let { runCatching { Ask(JSONObject(it)) }.getOrNull() } ?: asked ?: return
        // A test stopped meanwhile (the app said so, or another began) fires nothing.
        if (asked != null && asked?.o?.toString() != a.o.toString()) return
        asked = a
        fire(c.applicationContext, intent.getStringExtra("phase") ?: a.step, a)
    }

    private fun fire(c: Context, phase: String, a: Ask) {
        when (phase) {
            // The test's channel is named as the app says; "Só vibrar"'s by the plan when the app names none.
            "notify", "inUse" -> notify(c, a, Remind.ensure(c, a.notifySound,
                a.channel.ifEmpty { null } ?: if (a.quiet) Store.plan(c)?.texts?.channels?.get("vibrate") else null), fullScreen = false)
            "full" -> notify(c, a, Shade.CH_NAG, fullScreen = true)
            "bubble" -> {
                val app = c.applicationContext
                bubbling = true
                val f = Bubble.Face(NAG, a.line, a.title, a.o.optString("mascot").hashCode().toLong(), { a.mascot?.let(::MascotDrawable) },
                    tapped = { ctx -> runCatching { ctx.startActivity(Shade.openingAt(ctx, a.route, overLock = false)) }; stop(ctx, "tap") },
                    nested = { ctx -> stop(ctx, "nest") })
                face = f
                // Over her apps only: asked from Pinkoo, it shows once she leaves it ([away]).
                Bubble.show(app, f)
                NagService.sync(app)
                later(BUBBLE_MS) { stop(app, "cap") }
            }
            "ring" -> {
                holding = false
                // A real alarm ringing meanwhile wins: the test gives way.
                if (NagService.ring?.test == false) return stop(c, "alarm")
                NagService.ring = NagService.Companion.Ring(NAG, a.sound, a.volume, NagService.TEST_MS, test = true)
                alarmNotice(c, a)
                NagService.sync(c)
            }
        }
    }

    /**
     * The test's notification: on [channel], public (it names no medication, so the lock screen shows it whole, "Parar"
     * included), a new post so it sounds, gone after 15 s. With [fullScreen], on the silent channel with the full-screen
     * intent opening the nag route in test mode over the keyguard.
     */
    private fun notify(c: Context, a: Ask, channel: String, fullScreen: Boolean) {
        val b = Notification.Builder(c, channel).setSmallIcon(Shade.icon(c)).setColor(Shade.BRAND)
            .setContentTitle(a.title).setContentText(a.line.ifEmpty { a.body }).setStyle(Notification.BigTextStyle().bigText(a.line.ifEmpty { a.body }))
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false).setOnlyAlertOnce(true).setTimeoutAfter(NOTIFY_MS)
            .setDeleteIntent(Shade.stop(c, 31, NAG, "swipe", test = true))
            .setContentIntent(Shade.activity(c, 32, Shade.openingAt(c, a.route, overLock = false)))
            .addAction(Notification.Action.Builder(null, a.stop, Shade.stop(c, 30, NAG, "stop", test = true)).build())
        if (a.publicTitle.isNotEmpty()) b.setPublicVersion(Notification.Builder(c, channel).setSmallIcon(Shade.icon(c)).setContentTitle(a.publicTitle).build())
        if (fullScreen && FullScreen.allowed(c)) b.setFullScreenIntent(Shade.activity(c, 33, Shade.openingAt(c, a.route, overLock = true)), true)
        a.mascot?.let { m -> runCatching { b.setLargeIcon(MascotDrawable(m).bitmap((64 * c.resources.displayMetrics.density).toInt().coerceIn(64, 256))) } }
        Shade.cancel(c, Shade.TEST)
        Shade.notify(c, Shade.TEST, b.build())
    }

    /** The alarm sample's notification: the alarm's, public with "Parar". */
    private fun alarmNotice(c: Context, a: Ask) {
        val b = Notification.Builder(c, Shade.CH_NAG).setSmallIcon(Shade.icon(c)).setColor(Shade.BRAND)
            .setContentTitle(a.title).setContentText(a.body).setStyle(Notification.BigTextStyle().bigText(a.body))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true)
            .setShowWhen(false).setOnlyAlertOnce(true)
            .setDeleteIntent(Shade.stop(c, 31, NAG, "swipe", test = true))
            .setContentIntent(Shade.activity(c, 32, Shade.openingAt(c, a.route, overLock = false)))
            .addAction(Notification.Action.Builder(null, a.stop, Shade.stop(c, 30, NAG, "stop", test = true)).build())
        Shade.notify(c, Shade.TEST, b.build())
    }

    private fun later(ms: Long, f: () -> Unit) {
        cancelTimer()
        val r = Runnable { timer = null; f() }
        timer = r
        main.postDelayed(r, ms)
    }

    private fun cancelTimer() { timer?.let { main.removeCallbacks(it) }; timer = null }

    private fun unlisten() { screen?.let { (c, r) -> runCatching { c.unregisterReceiver(r) } }; screen = null }

    /** She left Pinkoo during Bolinha's test: its bubble shows over her apps now. */
    fun away(c: Context) {
        val f = face ?: return
        if (!bubbling) return
        Bubble.show(c.applicationContext, f)
        NagService.sync(c.applicationContext)
    }

    /** `NagReceiver`'s [STOP]. */
    fun stopped(c: Context, intent: Intent) = stop(c, intent.getStringExtra("why") ?: "stop")

    /** The test over (`stopRehearsal`, "Parar", its cap, a swipe, the unlock or the power key, another test): its alarm,
     * receivers, notification, bubble and ring gone. Nothing was recorded, so nothing is undone. */
    fun stop(c: Context, @Suppress("UNUSED_PARAMETER") why: String) {
        val app = c.applicationContext
        val a = asked
        asked = null
        holding = false
        cancelTimer()
        unlisten()
        if (a != null) runCatching { am(app).cancel(wake(app, a.step, a)) }
        runCatching { Shade.cancel(app, Shade.TEST) }
        if (NagService.ring?.test == true) NagService.ring = null
        NagService.running?.update()
        face = null
        if (bubbling) {
            bubbling = false
            Bubble.hide()
            // A real bubble the test covered comes back with the engine's refresh, posted so it never runs inside one.
            main.post { runCatching { Engine.restore(app) } }
        }
        NagService.sync(app)
    }
}

/** `previewSound`: a sound played once, in front only. */
internal object Preview {
    private var pools = HashMap<Int, SoundPool>()
    private val loaded = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private var stream = 0
    private var streamPool: SoundPool? = null
    private var player: MediaPlayer? = null
    /** What was asked last and not ready yet: it plays once loaded. */
    private var pending: Pair<Int, Float>? = null

    private fun pool(usage: Int): SoundPool = pools.getOrPut(usage) {
        SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
            AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().apply {
            setOnLoadCompleteListener { pool, id, status ->
                if (status == 0) ready += id
                pending?.let { (want, gain) -> if (want == id && status == 0) { pending = null; stream = pool.play(id, gain, gain, 1, 0, 1f); streamPool = pool } }
            }
        }
    }

    /** [sound] played once with [usage] (`notification` or `alarm`, at [volume] through the alarm's decibel curve). */
    fun play(c: Context, sound: String, usage: String, volume: Double) {
        stop()
        val u = if (usage == "alarm") AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
        val gain = if (usage == "alarm") Sounds.gain(volume) else 1f
        when {
            sound == "silent" -> {}
            sound == "vibrate" -> Sounds.vibrate(c, repeat = false, usage = VibrationAttributes.USAGE_NOTIFICATION)
            Sounds.phone(sound) -> player = Sounds.player(c, sound, u, gain, looping = false)
            else -> {
                val raw = Sounds.raw(c, sound).takeIf { it != 0 } ?: Sounds.raw(c, Sounds.DEFAULT)
                if (raw == 0) { player = Sounds.player(c, sound, u, gain, looping = false); return }
                val pool = pool(u)
                val key = "$u:$raw"
                val id = loaded.getOrPut(key) { pool.load(c, raw, 1) }
                if (id in ready) { stream = pool.play(id, gain, gain, 1, 0, 1f); streamPool = pool } else pending = id to gain
            }
        }
    }

    fun stop() {
        pending = null
        streamPool?.let { if (stream != 0) it.stop(stream) }
        stream = 0
        streamPool = null
        player?.runCatching { stop(); release() }
        player = null
    }

    /** Everything let go (the module went). */
    fun release() {
        stop()
        pools.values.forEach { runCatching { it.release() } }
        pools = HashMap()
        loaded.clear()
        ready.clear()
    }
}
